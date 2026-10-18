#!/usr/bin/env python3
"""IIDX -> BMS on-demand conversion bridge for lr2oraja-endlessdream.

The upstream iidx2bms converter works on a whole song: one call turns every
difficulty of a chart into a separate .bme file and stages the audio/BGA/
stagefile next to it. This bridge drives that one call and then prints a JSON
manifest so the Java side never has to guess generated file names.

stdout protocol -- exactly one JSON object per line:

    {"type": "progress", "percent": 0-100, "stage": "init"}
    {"type": "warning",  "message": "..."}
    {"type": "result",   "result_dir": "...", "charts": [...], "song": {...}}
    {"type": "error",    "message": "...", "traceback": "..."}

The "result" object is additionally written to <result_dir>/manifest.json, and
carries the conversion options it was produced with, so a cache hit can be
validated against the currently configured options without re-running Python.

Every diagnostic the converter prints on stdout/stderr is redirected to stderr
so that stdout stays machine readable.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
import traceback
from contextlib import redirect_stdout
from pathlib import Path

# Token -> difficulty id. These tokens are what one2bme puts into the generated
# .bme file names; conversion._difficulty_for_bme_name matches the very same
# strings, so the two stay in lockstep with upstream.
DIFFICULTY_TOKENS: tuple[tuple[str, str], ...] = (
    ("[LEGGENDARIA14]", "dpl"),
    ("[LEGGENDARIA7]", "spl"),
    ("[ANOTHER14]", "dpa"),
    ("[ANOTHER7]", "spa"),
    ("[HYPER14]", "dph"),
    ("[HYPER7]", "sph"),
    ("[NORMAL14]", "dpn"),
    ("[NORMAL7]", "spn"),
    ("[BEGINNER14]", "dpb"),
    ("[BEGINNER7]", "spb"),
)

LEVEL_FIELDS = {
    "spb": "spb_level",
    "spn": "spn_level",
    "sph": "sph_level",
    "spa": "spa_level",
    "spl": "spl_level",
    "dpb": "dpb_level",
    "dpn": "dpn_level",
    "dph": "dph_level",
    "dpa": "dpa_level",
    "dpl": "dpl_level",
}

# The same manifest that goes to stdout is also written into the result
# directory, so a cached conversion can be reused without re-running Python.
MANIFEST_FILE = "manifest.json"

# 一部分歌曲把 SPL/DPL(LEGGENDARIA) 谱面藏在补丁层 <id>-p0.ifs 里。
# 该文件是引用同目录 <id>.ifs 的 super-IFS，上游的谱面来源选择器（_find_chart_source）
# 只认 <id>.ifs 或 <id>.1+<id>.s3p+<id>_pre.2dx 目录，完全不认补丁层，
# 所以那一层从来没人读过，转换结果必然缺少 LEGGENDARIA 难度。
P0_SUFFIX = "-p0.ifs"

# 一部分歌曲的主音频被切成了多个容器：包内没有 <id>.2dx / <id>.s3p，
# 只有 <id><后缀>.2dx(或 .s3p)。后缀按「无、1-9、a-z」升序拼接，
# 同名 wav 后解出的覆盖先解出的，因此这一串必须按这个次序提取。
_SPLIT_AUDIO_SUFFIX_ORDER: tuple[str, ...] = ("",) + tuple(
    str(number) for number in range(1, 10)
) + tuple(chr(code) for code in range(ord("a"), ord("z") + 1))

# (work_dir 绝对路径, 首片文件名) -> 已复制进 work_dir 的有序分片清单。
# 谱面准备层登记，提取层据此把各分片的输出合并到首片的 .out 目录。
_SPLIT_AUDIO_REGISTRY: dict[tuple[str, str], list[Path]] = {}

# 直读索引的文件名与结构版本，必须与 Java 侧 LDJConversionService 的常量一致。
AUDIO_INDEX_FILE = "ldj_audio.json"
AUDIO_INDEX_VERSION = 1
AUDIO_MODE_DIRECT = "direct"
AUDIO_MODE_EXTRACT = "extract"

# 当前生效的音频模式。apply_chart_source_patch 里的包装函数在每次调用时读取它，
# 因此索引生成失败后可以在同一进程内切回 extract 并重跑转换。
_AUDIO_MODE_ACTIVE = AUDIO_MODE_EXTRACT

# (work_dir 绝对路径, 音频文件名) 集合：direct 模式下这些容器不需要解压。
_DIRECT_AUDIO_SUPPRESSION: set[tuple[str, str]] = set()

# 第 6 步：BGA 改为运行时从 movie 根按同名读取，转换侧不再保留复制进来的视频文件。
# 上游 convert_chart 仍会把 BGA 视频复制进结果目录，这里列出要删掉的视频扩展名。
_BGA_VIDEO_EXTENSIONS: set[str] = {
    ".mp4", ".wmv", ".m4v", ".webm", ".mpg", ".mpeg", ".m1v", ".m2v", ".avi", ".mov", ".mkv",
}


def _scan_2dx_sections(data: bytes) -> list[tuple[str, int, int]]:
    """按 2dx_parse.c 的规则扫描一个 2DX 载荷，返回 (文件名, 段内偏移, 字节数)。

    魔数为 "2DX9"，段长在 +0x8（u32 LE），载荷起点在 +0x18；
    声明键音数在 0x14（u16 LE），只用来决定文件名宽度。
    越界时与 C 实现一样停止，不抛异常。
    """
    limit = len(data)
    if limit < 0x16:
        return []
    declared = data[0x14] | (data[0x15] << 8)
    digits = max(4, len(str(declared)) if declared else 1)
    sections: list[tuple[str, int, int]] = []
    position = 0
    count = 0
    while count < declared:
        position = data.find(b"2DX9", position)
        if position < 0 or position + 0x18 >= limit:
            break
        size = int.from_bytes(data[position + 0x8 : position + 0xC], "little")
        payload = position + 0x18
        if payload + size > limit:
            break
        sections.append((f"{count + 1:0{digits}d}.wav", payload, size))
        count += 1
        position = payload + size
    return sections


def _split_audio_entry_names(names: set[str], song_id_display: str) -> list[str]:
    """在容器文件名集合里按与 _split_audio_parts 相同的规则挑出分片链。"""
    if f"{song_id_display}.2dx" in names or f"{song_id_display}.s3p" in names:
        return []
    for extension in (".2dx", ".s3p"):
        existing = [
            candidate
            for suffix in _SPLIT_AUDIO_SUFFIX_ORDER
            if (candidate := f"{song_id_display}{suffix}{extension}") in names
        ]
        if existing:
            return existing
    return []


def _relative_audio_path(path: Path, sound_root: Path) -> str:
    """容器路径一律相对 LDJ 音频根，使用正斜杠，缓存目录搬家后仍然有效。"""
    try:
        return Path(path).resolve().relative_to(Path(sound_root).resolve()).as_posix()
    except ValueError as exc:
        raise RuntimeError(f"audio container is outside the sound root: {path}") from exc


def _ifs_entry_table(ifs_file: Path) -> tuple[int, dict[str, tuple[int, int, bool]]]:
    """读取 IFS 清单，返回 (manifest_end, 文件名 -> (start, size, 是否属于本文件))。

    复用 ifstools 的解析结果，不调用任何外部提取器。
    """
    from ifstools.ifs import IFS  # noqa: PLC0415

    archive = IFS(str(ifs_file))
    try:
        base_blob = archive.data_blob
        manifest_end = int(base_blob.offset)
        entries: dict[str, tuple[int, int, bool]] = {}

        def walk(folder) -> None:
            for node in folder.files.values():
                if node.start is None or node.size is None:
                    continue
                entries.setdefault(
                    node.name,
                    (int(node.start), int(node.size), node.ifs_data is base_blob),
                )
            for child in folder.folders.values():
                walk(child)

        walk(archive.tree)
        return manifest_end, entries
    finally:
        archive.close()


def _direct_audio_from_directory(
    sound_root: Path, song_dir: Path, song_id_display: str
) -> list[tuple[str, int, list[tuple[str, int, int]]]]:
    """解包目录的主音频：返回 (容器相对路径, 容器基址, 段表) 清单。"""
    main_2dx = song_dir / f"{song_id_display}.2dx"
    if main_2dx.is_file():
        parts = [main_2dx]
    else:
        if (song_dir / f"{song_id_display}.s3p").is_file():
            raise RuntimeError(".s3p audio is not supported by direct read")
        parts = _split_audio_parts(song_dir, song_id_display)
        if not parts:
            raise RuntimeError("no .2dx main audio or split parts found")
    if any(part.suffix.lower() != ".2dx" for part in parts):
        raise RuntimeError("direct read only supports .2dx audio containers")
    return [
        (_relative_audio_path(part, sound_root), 0, _scan_2dx_sections(part.read_bytes()))
        for part in parts
    ]


def _direct_audio_from_ifs(
    sound_root: Path, ifs_file: Path, song_id_display: str
) -> list[tuple[str, int, list[tuple[str, int, int]]]]:
    """IFS 内的主音频：绝对偏移为 manifest_end + entry.start + 段内偏移。"""
    manifest_end, entries = _ifs_entry_table(ifs_file)
    names = set(entries)
    if f"{song_id_display}.2dx" in names:
        parts = [f"{song_id_display}.2dx"]
    elif f"{song_id_display}.s3p" in names:
        raise RuntimeError(".s3p audio is not supported by direct read")
    else:
        parts = _split_audio_entry_names(names, song_id_display)
    if not parts:
        raise RuntimeError("no .2dx main audio or split parts found in the IFS")
    if any(not name.lower().endswith(".2dx") for name in parts):
        raise RuntimeError("direct read only supports .2dx audio containers")

    relative_path = _relative_audio_path(ifs_file, sound_root)
    descriptors: list[tuple[str, int, list[tuple[str, int, int]]]] = []
    with Path(ifs_file).open("rb") as handle:
        for name in parts:
            start, size, owned = entries[name]
            if not owned:
                # 该条目来自被引用的 super-IFS，偏移不属于当前文件，无法直读
                raise RuntimeError(f"audio container {name} is stored in a referenced super-IFS")
            handle.seek(manifest_end + start)
            data = handle.read(size)
            if len(data) != size:
                raise RuntimeError(f"short read from {Path(ifs_file).name}:{start}")
            descriptors.append(
                (relative_path, manifest_end + start, _scan_2dx_sections(data))
            )
    return descriptors


def build_direct_audio_index(
    sound_root: Path,
    source_kind: str,
    source_files: dict[str, Path],
    song_id_display: str,
) -> dict:
    """扫描谱面来源里的主音频容器，生成直读索引。

    不支持的来源（standard/.s3p、未知容器、扫描不到任何段）一律抛异常，
    由调用方在同一进程内回退到 extract。
    """
    song_id_display = str(song_id_display)
    if source_kind != "ifs":
        raise RuntimeError(f"direct audio read does not support chart source kind {source_kind!r}")
    source = Path(source_files["ifs"])
    if source.is_dir():
        descriptors = _direct_audio_from_directory(sound_root, source, song_id_display)
    elif source.suffix.lower() == ".ifs":
        descriptors = _direct_audio_from_ifs(sound_root, source, song_id_display)
    else:
        raise RuntimeError(f"direct audio read does not support chart source {source.name}")

    containers: list[dict] = []
    container_index: dict[str, int] = {}
    wav: dict[str, list[int]] = {}
    for path, base_offset, sections in descriptors:
        index = container_index.get(path)
        if index is None:
            index = len(containers)
            container_index[path] = index
            containers.append({"path": path})
        for name, payload_offset, size in sections:
            # 分片按顺序扫描，同名条目后来者覆盖先来者
            wav[name] = [index, base_offset + payload_offset, size]
    if not wav:
        raise RuntimeError("no 2DX sections found in the main audio container")
    return {
        "index_version": AUDIO_INDEX_VERSION,
        "audio_mode": AUDIO_MODE_DIRECT,
        "song_id": song_id_display,
        "containers": containers,
        "wav": wav,
    }


def write_direct_audio_index(result_dir: Path, payload: dict) -> None:
    """先写同目录临时文件再原子重命名，保证清单声明 direct 时索引一定完整可见。"""
    target = Path(result_dir) / AUDIO_INDEX_FILE
    temporary = target.with_name(f".{AUDIO_INDEX_FILE}.tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    temporary.replace(target)


def _register_direct_audio(
    work_dir: Path, copied: dict[str, Path], parts: list[Path]
) -> None:
    """登记 direct 模式下不需要解压的主音频（含全部分片）。"""
    if _AUDIO_MODE_ACTIVE != AUDIO_MODE_DIRECT:
        return
    audio_file = Path(copied["audio"])
    if audio_file.suffix.lower() != ".2dx":
        return
    work_key = str(Path(work_dir).resolve())
    _DIRECT_AUDIO_SUPPRESSION.add((work_key, audio_file.name))
    for part in parts:
        _DIRECT_AUDIO_SUPPRESSION.add((work_key, Path(part).name))


def _direct_audio_suppressed(work_dir: Path, archive_path: Path) -> bool:
    return (str(Path(work_dir).resolve()), Path(archive_path).name) in _DIRECT_AUDIO_SUPPRESSION


def _empty_audio_out_dir(work_dir: Path, archive_path: Path) -> Path:
    """direct 模式不落盘主音频，只返回一个空输出目录供 _copy_results 使用。"""
    out_dir = Path(work_dir) / f"{Path(archive_path).name}.out"
    out_dir.mkdir(parents=True, exist_ok=True)
    for stale_file in out_dir.glob("*"):
        if stale_file.is_file():
            stale_file.unlink(missing_ok=True)
    return out_dir


def emit(payload: dict) -> None:
    # The converter is wrapped in redirect_stdout(sys.stderr) so that its
    # chatter cannot corrupt the manifest stream. sys.__stdout__ is the real
    # pipe and stays untouched by that redirect, so the protocol survives.
    stream = sys.__stdout__ or sys.stdout
    stream.write(json.dumps(payload, ensure_ascii=False) + "\n")
    stream.flush()


def difficulty_for_bme_name(file_name: str) -> str | None:
    upper_name = file_name.upper()
    for token, difficulty in DIFFICULTY_TOKENS:
        if token in upper_name:
            return difficulty
    return None


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Convert one IIDX song to BMS and emit a manifest.")
    parser.add_argument("--project-root", required=True, help="iidx2bms checkout that owns conversion/ and search_engine/")
    parser.add_argument("--sound-root", required=True, help="IIDX sound data root (contains <id>.1 / <id>.s3p / <id>.ifs)")
    parser.add_argument("--movie-root", required=True, help="IIDX movie data root (BGA)")
    parser.add_argument("--song-id", required=True, type=int, help="numeric IIDX song id")
    parser.add_argument("--out-root", required=True, help="directory that receives the converted song folder")
    parser.add_argument("--no-stagefile", action="store_true", help="do not copy a stagefile into the result")
    parser.add_argument("--no-bga", action="store_true", help="do not copy BGA / #BMP rewrite")
    parser.add_argument("--no-preview", action="store_true", help="do not extract the preview .2dx")
    parser.add_argument("--overwrite", action="store_true", help="remove an existing result folder of the same song first")
    parser.add_argument(
        "--audio-mode",
        choices=(AUDIO_MODE_DIRECT, AUDIO_MODE_EXTRACT),
        default=AUDIO_MODE_EXTRACT,
        help="direct skips extracting the main audio and writes a direct-read index; extract keeps the old behaviour",
    )
    return parser.parse_args(argv)


def find_result(engine, song_id: int):
    # search() ranks by (score, tie_breaker, song_id) so the target is not
    # necessarily first -- filter the full match list by id explicitly.
    matches = engine.search(str(song_id), limit=0)
    for candidate in matches:
        if int(candidate.song_id) == int(song_id):
            return candidate
    return None


def build_manifest(results_root: Path, result_dir: Path, result) -> list[dict]:
    charts: list[dict] = []
    for bme_file in sorted(result_dir.glob("*.bme")):
        difficulty = difficulty_for_bme_name(bme_file.name)
        level = 0
        if difficulty is not None:
            level = int(getattr(result, LEVEL_FIELDS[difficulty], 0) or 0)
        charts.append(
            {
                "difficulty": difficulty,
                "level": level,
                "file": str(bme_file.resolve()),
                "name": bme_file.name,
            }
        )
    return charts


def find_p0_chart_file(extracted_root: Path, song_id_display: str) -> Path | None:
    # 补丁层解包后谱面表仍叫 <id>.1，但恢复出来的目录层级由 ifstools 决定，
    # 所以按文件名递归找，不假设深度。
    for candidate in sorted(extracted_root.rglob(f"{song_id_display}.1")):
        if candidate.is_file():
            return candidate
    return None


def apply_p0_patch(
    project_root: Path,
    sound_root: Path,
    movie_root: Path,
    result_dir: Path,
    result,
    include_bga: bool,
) -> int:
    """把补丁层 <id>-p0.ifs 里多出来的谱面补进 pass 1 的结果目录。

    补丁层换掉了谱面表，音频与 _pre.2dx 却与基础层逐字节相同，
    所以它多出来的 SPL/DPL(LEGGENDARIA) 谱面的 WAV 编号与 pass 1 抽出的
    WAV 完全对应，直接复用 pass 1 的产物即可，不必再抽一遍音频。

    只补空缺，不覆盖 pass 1 已经写出的同名谱面。返回补进去的谱面数量；
    补丁层不存在、上游私有入口不可用、解包或 one2bme 失败都只发 warning
    并返回 0，绝不能影响 pass 1 已经完成的转换。
    """
    song_id_display = str(result.song_id_display)
    p0_file = sound_root / f"{song_id_display}{P0_SUFFIX}"
    if not p0_file.is_file():
        return 0

    # 这些是上游的私有入口。上游若把它们重构掉，这里退化成 pass 1 的结果，
    # 而不是让整次转换失败。
    try:
        from conversion.conversion import (  # noqa: PLC0415
            _difficulty_for_bme_name,
            _resolve_bga_with_extension,
            _resolve_song_meta,
            _rewrite_bme,
            _run_one2bme,
        )
        from ifstools.ifs import IFS  # noqa: PLC0415
    except ImportError as exc:
        emit({"type": "warning", "message": f"p0 patch layer skipped: {type(exc).__name__}: {exc}"})
        return 0

    temp_root = Path(tempfile.mkdtemp(prefix="iidx2bms_p0_"))
    try:
        extracted_root = temp_root / "ifs"
        # p0 是 super-IFS，它内部的引用相对自身所在目录解析，
        # 因此必须留在 sound_root 原地解包，不能先拷到别处（拷走会解析失败）。
        archive = IFS(str(p0_file))
        try:
            archive.extract(
                path=str(extracted_root),
                progress=False,
                recurse=True,
                extract_manifest=False,
                rename_dupes=True,
            )
        finally:
            archive.close()

        one_file = find_p0_chart_file(extracted_root, song_id_display)
        if one_file is None:
            emit({"type": "warning", "message": f"p0 patch layer carries no {song_id_display}.1: {p0_file}"})
            return 0

        # one2bme 只读谱面表，不需要同层的音频文件。
        work_dir = temp_root / "work"
        work_dir.mkdir(parents=True, exist_ok=True)
        bme_dir = _run_one2bme(project_root, work_dir, one_file)

        # 以下取值逐条对齐上游 _copy_results，保证补丁层谱面写出的 tag
        # 与 pass 1 的谱面完全一致。
        song_meta = _resolve_song_meta(result, project_root)
        genre_attr = getattr(result, "genre", None)
        title_attr = getattr(result, "title", None)
        artist_attr = getattr(result, "artist", None)
        genre_value = str(song_meta.get("genre") or "").strip() if genre_attr is None else str(genre_attr).strip()
        title_value = (
            str(song_meta.get("title") or result.title_ascii or "").strip()
            if title_attr is None
            else str(title_attr).strip()
        )
        artist_value = str(song_meta.get("artist") or "").strip() if artist_attr is None else str(artist_attr).strip()
        bmp01_value: str | None = None
        if include_bga:
            bmp01_value, _ = _resolve_bga_with_extension(
                movie_root, str(song_meta.get("bga_filename") or "").strip()
            )

        # pass 1 可能因为缺 stagefile 退化成无 stagefile 重跑，那条路径上局部变量
        # include_stagefile 仍是 True，所以这里以结果目录里的实际文件为准。
        stagefile_name = "1.png" if (result_dir / "1.png").is_file() else None

        injected = 0
        for bme_file in sorted(bme_dir.glob("*.bme")):
            normalized_name = bme_file.name.lstrip()
            copied_bme = result_dir / normalized_name
            if copied_bme.exists():
                # pass 1 已经产出同名谱面，说明该难度来自基础层，保持原样。
                continue
            shutil.copy2(bme_file, copied_bme)
            _rewrite_bme(
                copied_bme,
                difficulty=_difficulty_for_bme_name(normalized_name, result),
                stagefile_name=stagefile_name,
                genre_value=genre_value,
                title_value=title_value,
                artist_value=artist_value,
                bmp01_value=bmp01_value,
            )
            injected += 1
        return injected
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)


def _split_audio_parts(container_dir: Path, song_id_display: str) -> list[Path]:
    """返回分片主音频的有序清单；存在完整主容器时返回空表。

    上游把 <id>.2dx 与 <id>.s3p 当作完整主音频，所以只要其中任何一个在场就不接管；
    否则依次试 .2dx、.s3p 两族，取先出现的一族。
    <id>_pre.2dx 的「_pre」不在后缀集合里，预览音频不会被当成分片。
    """
    if (container_dir / f"{song_id_display}.2dx").is_file() or (
        container_dir / f"{song_id_display}.s3p"
    ).is_file():
        return []
    for extension in (".2dx", ".s3p"):
        existing = [
            candidate
            for suffix in _SPLIT_AUDIO_SUFFIX_ORDER
            if (candidate := container_dir / f"{song_id_display}{suffix}{extension}").is_file()
        ]
        if existing:
            return existing
    return []


def _stage_split_audio(parts: list[Path], work_dir: Path) -> list[Path]:
    """把分片按顺序复制进 work_dir，并登记这套分片供提取层合并。

    上游总是先复制进 work_dir 再提取，所以副本的复制顺序必须与拼接顺序一致。
    """
    if not parts:
        return []
    copied: list[Path] = []
    for part in parts:
        destination = Path(work_dir) / part.name
        shutil.copy2(part, destination)
        copied.append(destination)
    _SPLIT_AUDIO_REGISTRY[(Path(work_dir).resolve(), copied[0].name)] = copied
    return copied


def _registered_split_parts(work_dir: Path, archive_path: Path) -> list[Path]:
    """取出登记过的分片清单；没登记过（普通歌）返回空表。"""
    return _SPLIT_AUDIO_REGISTRY.get((Path(work_dir).resolve(), Path(archive_path).name), [])


def _overlay_out_dir(source_dir: Path, target_dir: Path) -> None:
    """把后一片解出的文件按同名覆盖写进首片的输出目录。"""
    for source_file in sorted(Path(source_dir).glob("*")):
        if source_file.is_file():
            shutil.copy2(source_file, Path(target_dir) / source_file.name)


def _dir_has_chart_payload(song_dir: Path, song_id_display: str) -> bool:
    """目录里是否有一份可当作 IFS 载荷用的谱面：谱面表加主音频。

    上游 trio 判定要的是 <id>.s3p，而这里的目录主音频是 <id>.2dx，所以两者都认；
    主音频被切成 <id>N.2dx 分片时也认，<id>_pre.2dx 由调用方另外判定。
    """
    if not (song_dir / f"{song_id_display}.1").is_file():
        return False
    if (song_dir / f"{song_id_display}.2dx").is_file() or (song_dir / f"{song_id_display}.s3p").is_file():
        return True
    return bool(_split_audio_parts(song_dir, song_id_display))


def _chart_payload_from_extracted_dir(extracted_chart_dir: Path, song_id_display: str) -> dict[str, Path] | None:
    """在一份已解包的 IFS 载荷目录里挑出可用的三件套，挑不出返回 None。

    与上游的差别只有一条：不要求目录里的文件集与标准三件套完全相等。
    主音频(<id>.2dx 或 <id>.s3p)与 <id>_pre.2dx 都在场就够了，
    多出来的文件(<id>a.2dx / <id>w.2dx 旁支容器等)一律忽略。
    没有主容器时退回分片族，把排在第一片的分片当作主音频；
    <id>N.2dx 这类分片的合并由提取层完成。
    """
    one_file = extracted_chart_dir / f"{song_id_display}.1"
    audio_2dx_file = extracted_chart_dir / f"{song_id_display}.2dx"
    audio_s3p_file = extracted_chart_dir / f"{song_id_display}.s3p"
    preview_file = extracted_chart_dir / f"{song_id_display}_pre.2dx"
    # 两个容器同时在场的包(如 27100)取 .2dx，与上游自己的偏好一致
    audio_file: Path | None = None
    for candidate in (audio_2dx_file, audio_s3p_file):
        if candidate.is_file():
            audio_file = candidate
            break
    if audio_file is None:
        parts = _split_audio_parts(extracted_chart_dir, song_id_display)
        audio_file = parts[0] if parts else None
    if not one_file.is_file() or audio_file is None or not preview_file.is_file():
        return None
    return {"one": one_file, "audio": audio_file, "preview": preview_file}



def apply_chart_source_patch() -> dict[str, bool]:
    """放宽上游的谱面来源判定与 IFS 载荷判定，多救回两类歌。

    这两类歌的文件都在 sound 目录里、谱面也齐全，却因为上游的判定过严而失败：

    1. 目录回退(dir_fallback)：393 首歌的 sound/<id>/ 里有一套完整谱面
       (<id>.1 加 <id>.2dx)，却既没有 <id>.s3p(不满足上游的 trio 判定)，
       也没有 <id>.ifs(拿不到 IFS 载荷)，于是 _find_chart_source 对它们一律抛
       "Chart source files not found"，这些歌从来没有转换成功过。
       这个目录本身就是 IFS 解包后的样子，把它直接当作 IFS 载荷喂给
       _prepare_from_ifs，下游链路与解包路径完全一致。
    2. 非标准 IFS(nonstd_ifs_accept)：24 首歌的 <id>.ifs 解开后除了标准三件套
       还多一个旁支容器(<id>a.2dx / <id>w.2dx 等)，上游要求文件集与标准三件套
       完全相等，于是抛 "is non-standard" 拒绝转换。这些包的 <id>.1、主音频与
       <id>_pre.2dx 都在场，忽略多出来的文件就能转。

    做法是包裹上游的 _find_chart_source 与 _prepare_from_ifs：
      * 来源选择：先调用原函数，成功就原样返回，保证原有四道判定的顺序不变
        (有 73 首歌同时有根目录 <id>.ifs 和 sound/<id>/，它们必须继续走 ifs)；
        只有原函数抛 RuntimeError 时才看 sound/<id>/，不满足条件就把原异常抛回去。
      * 载荷准备：先让原函数照常跑。目录回退传进来的 "ifs" 键是目录而不是文件，
        此时跳过 IFS 解包，从目录里取三件套。非标准包只接管原函数抛出的
        "is non-standard" 这一种失败(靠报错文本判断确实脆，但上游没给出别的信号)，
        此时上游已经把这个包解在 work_dir/<id>_ifs/<id>，直接复用那份文件，
        省掉一次解包。
        两条路径都复用 "ifs" 这个 kind：上游 "standard" 分支写死了
        _extract_wavs_from_s3p，而这里的主音频全是 .2dx，选错必失败。
      * 分片合并(split_audio_merge)：没有 <id>.2dx / <id>.s3p 主容器、
        只有 <id><后缀>.2dx(或 .s3p) 的歌，按「无、1-9、a-z」的次序把各片
        依次解压，同名文件后者覆盖前者，最后只留首片的输出目录交给下游。
      * 这两种包都不保证音频完整(旁支容器不参与音源提取)，照发 warning。

    返回补丁层的标志位，供 manifest 记录。上游若把这些私有入口重构掉，
    这里只发 warning 并返回全 False，转换退回上游原有行为，绝不因此失败。
    """
    patch_flags = {"dir_fallback": False, "nonstd_ifs_accept": False, "split_audio_merge": False}
    try:
        from conversion import conversion as conversion_module  # noqa: PLC0415
        from conversion.conversion import _copy_chart_files  # noqa: PLC0415
    except ImportError as exc:
        emit({"type": "warning", "message": f"chart source patch skipped: {type(exc).__name__}: {exc}"})
        return patch_flags

    original_find_chart_source = getattr(conversion_module, "_find_chart_source", None)
    original_prepare_from_ifs = getattr(conversion_module, "_prepare_from_ifs", None)
    if not callable(original_find_chart_source) or not callable(original_prepare_from_ifs):
        emit({"type": "warning", "message": "chart source patch skipped: upstream entry points are missing"})
        return patch_flags

    original_extract_2dx = getattr(conversion_module, "_extract_wavs_from_2dx", None)
    original_extract_s3p = getattr(conversion_module, "_extract_wavs_from_s3p", None)
    split_ready = callable(original_extract_2dx) and callable(original_extract_s3p)
    if not split_ready:
        # 提取函数缺失时只放开前两类歌，分片合并整个禁用，退回「audio may be incomplete」的旧行为
        emit(
            {
                "type": "warning",
                "message": "split audio merge disabled: upstream extraction entry points are missing",
            }
        )

    def find_chart_source(sound_root: Path, song_id_display: str):
        try:
            return original_find_chart_source(sound_root, song_id_display)
        except RuntimeError as exc:
            original_error = exc

        song_dir = Path(sound_root) / str(song_id_display)
        if song_dir.is_dir() and _dir_has_chart_payload(song_dir, str(song_id_display)):
            return ("ifs", {"ifs": song_dir})
        raise original_error

    def prepare_from_ifs(project_root: Path, work_dir: Path, song_id_display: str, ifs_file: Path):
        if Path(ifs_file).is_dir():
            # 目录回退：ifs_file 其实是 sound/<id>/ 这个已解包目录，没有 IFS 可解。
            song_dir = Path(ifs_file)
            one_file = song_dir / f"{song_id_display}.1"
            audio_2dx_file = song_dir / f"{song_id_display}.2dx"
            audio_s3p_file = song_dir / f"{song_id_display}.s3p"
            preview_file = song_dir / f"{song_id_display}_pre.2dx"
            # 主音频被切成分片时，排在第一片的那一份充当主音频，其余分片由提取层合并
            parts = _split_audio_parts(song_dir, str(song_id_display)) if split_ready else []
            audio_file = audio_2dx_file if audio_2dx_file.is_file() else audio_s3p_file
            if not one_file.is_file() or (not audio_file.is_file() and not parts):
                raise RuntimeError(f"Chart source folder {song_dir} has no {song_id_display}.1 with audio")
            if not preview_file.is_file():
                raise RuntimeError(f"Chart source folder {song_dir} has no {song_id_display}_pre.2dx")

            emit(
                {
                    "type": "warning",
                    "message": (
                        f"Chart {song_id_display}: {song_id_display}.ifs not found, "
                        f"using the pre-unpacked folder {song_dir.name} as the IFS payload"
                    ),
                }
            )
            if parts:
                emit(
                    {
                        "type": "warning",
                        "message": (
                            f"Chart {song_id_display}: merging {len(parts)} audio fragments "
                            f"in order {[part.name for part in parts]}"
                        ),
                    }
                )
                copied_parts = _stage_split_audio(parts, work_dir)
                copied = _copy_chart_files({"one": one_file, "preview": preview_file}, work_dir)
                copied["audio"] = copied_parts[0]
                _register_direct_audio(work_dir, copied, parts)
                return copied
            copied = _copy_chart_files(
                {"one": one_file, "audio": audio_file, "preview": preview_file}, work_dir
            )
            _register_direct_audio(work_dir, copied, [])
            return copied

        try:
            copied = original_prepare_from_ifs(project_root, work_dir, song_id_display, ifs_file)
        except RuntimeError as exc:
            # 只接管文件集「非标准」这一种失败；解不开包、缺 <id>_pre.2dx 等
            # 其他失败原样抛回，报错文本不变
            if "is non-standard" not in str(exc):
                raise
            original_error = exc
        else:
            _register_direct_audio(work_dir, copied, [])
            return copied

        # 上游已经把这个包解在 work_dir/<id>_ifs/<id> 下；这里直接接着用它，
        # 顺带也说明为什么找不到这个目录时要退回上游原来的报错
        chart_dir = Path(work_dir) / f"{song_id_display}_ifs" / str(song_id_display)
        files = _chart_payload_from_extracted_dir(chart_dir, str(song_id_display)) if chart_dir.is_dir() else None
        if files is None:
            # 既没有主音频、也没有可识别的分片族，原样抛出上游的报错
            raise original_error

        # 没有 <id>.2dx / <id>.s3p 主容器时，_chart_payload_from_extracted_dir 取的是首片，
        # 这里把整套分片登记下来，交给提取层按序合并
        has_main_container = (
            (chart_dir / f"{song_id_display}.2dx").is_file() or (chart_dir / f"{song_id_display}.s3p").is_file()
        )
        parts = [] if has_main_container or not split_ready else _split_audio_parts(chart_dir, str(song_id_display))
        if parts:
            selected_names = {files["one"].name, files["preview"].name} | {part.name for part in parts}
        else:
            selected_names = {item.name for item in files.values()}
        ignored = sorted(
            path.name for path in chart_dir.iterdir() if path.is_file() and path.name not in selected_names
        )
        if parts:
            message = (
                f"Chart {song_id_display}: {song_id_display}.ifs is non-standard, "
                f"merging {len(parts)} audio fragments in order {[part.name for part in parts]}, "
                f"ignoring {ignored}"
            )
        else:
            message = (
                f"Chart {song_id_display}: {song_id_display}.ifs is non-standard, "
                f"ignoring {ignored} and using {files['audio'].name} as the main audio; "
                f"the audio may be incomplete"
            )
        emit({"type": "warning", "message": message})
        if parts:
            copied_parts = _stage_split_audio(parts, work_dir)
            copied = _copy_chart_files({"one": files["one"], "preview": files["preview"]}, work_dir)
            copied["audio"] = copied_parts[0]
            _register_direct_audio(work_dir, copied, parts)
            return copied
        copied = _copy_chart_files(files, work_dir)
        _register_direct_audio(work_dir, copied, [])
        return copied

    def extract_wavs_from_2dx(project_root: Path, work_dir: Path, archive_path: Path):
        # direct 模式的主音频（含全部分片）不落盘，交给 Java 侧按索引直读
        if _AUDIO_MODE_ACTIVE == AUDIO_MODE_DIRECT and _direct_audio_suppressed(work_dir, archive_path):
            return _empty_audio_out_dir(work_dir, archive_path)
        # 只有登记过的分片族才合并；普通歌原样走上游
        parts = _registered_split_parts(work_dir, archive_path)
        if not parts:
            return original_extract_2dx(project_root, work_dir, archive_path)
        # 后解出的分片按同名覆盖写进首片的输出目录，_copy_results 只会看这一个目录
        base_out_dir = original_extract_2dx(project_root, work_dir, parts[0])
        for part in parts[1:]:
            _overlay_out_dir(original_extract_2dx(project_root, work_dir, part), base_out_dir)
        return base_out_dir

    def extract_wavs_from_s3p(
        project_root: Path,
        work_dir: Path,
        archive_path: Path,
        progress_callback=None,
    ):
        parts = _registered_split_parts(work_dir, archive_path)
        if not parts:
            return original_extract_s3p(project_root, work_dir, archive_path, progress_callback)

        total_parts = len(parts)

        def make_callback(index: int):
            if progress_callback is None:
                return None

            def report(percent: int) -> None:
                # 上游按单个容器报 0-100，这里折算成整套分片的整体进度
                clamped = max(0, min(100, int(percent)))
                progress_callback(int((index * 100 + clamped) / total_parts))

            return report

        base_out_dir = original_extract_s3p(project_root, work_dir, parts[0], make_callback(0))
        for index, part in enumerate(parts[1:], start=1):
            _overlay_out_dir(original_extract_s3p(project_root, work_dir, part, make_callback(index)), base_out_dir)
        return base_out_dir

    try:
        conversion_module._find_chart_source = find_chart_source
        conversion_module._prepare_from_ifs = prepare_from_ifs
        if split_ready:
            conversion_module._extract_wavs_from_2dx = extract_wavs_from_2dx
            conversion_module._extract_wavs_from_s3p = extract_wavs_from_s3p
    except Exception as exc:  # noqa: BLE001 - 装不上就退回上游行为
        emit({"type": "warning", "message": f"chart source patch skipped: {type(exc).__name__}: {exc}"})
        return patch_flags
    return {"dir_fallback": True, "nonstd_ifs_accept": True, "split_audio_merge": split_ready}


def main(argv: list[str]) -> int:
    global _AUDIO_MODE_ACTIVE
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    try:
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    args = parse_args(argv)

    project_root = Path(args.project_root).expanduser().resolve()
    sound_root = Path(args.sound_root).expanduser().resolve()
    movie_root = Path(args.movie_root).expanduser().resolve()
    results_root = Path(args.out_root).expanduser().resolve()

    if not (project_root / "conversion" / "conversion.py").is_file():
        emit({"type": "error", "message": f"not an iidx2bms checkout: {project_root}"})
        return 2
    if not sound_root.is_dir():
        emit({"type": "error", "message": f"LDJ sound root not found: {sound_root}"})
        return 2

    sys.path.insert(0, str(project_root))

    try:
        from conversion.conversion import convert_chart  # noqa: PLC0415
        from search_engine.search_engine import SearchEngine  # noqa: PLC0415

        # 谱面来源与 IFS 载荷的放宽必须在转换之前装上：它包裹的是上游的谱面来源选择，
        # 而来源选择发生在 convert_chart 内部(与事后再补谱面的 p0 层不同)。
        chart_source_patch = apply_chart_source_patch()

        emit({"type": "progress", "percent": 0, "stage": "init"})

        engine = SearchEngine(project_root / "music_data" / "music_data.json")
        engine.set_include_levels(True)
        engine.set_include_game_version(True)
        engine.set_include_genre(True)

        result = find_result(engine, args.song_id)
        if result is None:
            emit({"type": "error", "message": f"song_id {args.song_id} is not present in music_data.json"})
            return 3

        emit({"type": "progress", "percent": 1, "stage": "search"})

        # direct 模式先在不启动转换的前提下定位谱面来源并扫描主音频容器。
        # 任何不支持或扫描失败都在这里退回 extract，转换本身照常进行。
        audio_mode = args.audio_mode
        audio_index: dict | None = None
        if audio_mode == AUDIO_MODE_DIRECT:
            try:
                from conversion import conversion as conversion_module  # noqa: PLC0415

                source_kind, source_files = conversion_module._find_chart_source(
                    sound_root, str(result.song_id_display)
                )
                audio_index = build_direct_audio_index(
                    sound_root=sound_root,
                    source_kind=source_kind,
                    source_files=source_files,
                    song_id_display=str(result.song_id_display),
                )
            except Exception as exc:  # noqa: BLE001 - 索引失败只回退，不影响整曲转换
                emit(
                    {
                        "type": "warning",
                        "message": (
                            "direct audio index unavailable, falling back to extract: "
                            f"{type(exc).__name__}: {exc}"
                        ),
                    }
                )
                audio_index = None
                audio_mode = AUDIO_MODE_EXTRACT

        _AUDIO_MODE_ACTIVE = audio_mode
        _DIRECT_AUDIO_SUPPRESSION.clear()

        include_stagefile = not args.no_stagefile

        def run(stagefile: bool) -> Path:
            def on_progress(percent: int, stage: str) -> None:
                emit({"type": "progress", "percent": percent, "stage": stage})

            return convert_chart(
                result=result,
                sound_root=sound_root,
                movie_root=movie_root,
                project_root=project_root,
                results_root=results_root,
                fully_overwrite=args.overwrite,
                include_stagefile=stagefile,
                include_bga=not args.no_bga,
                include_preview=not args.no_preview,
                progress_callback=on_progress,
            )

        # The converter prints a lot of chatter; keep stdout clean for the
        # manifest by parking everything that is not our own JSON on stderr.
        with redirect_stdout(sys.stderr):
            try:
                result_dir = run(include_stagefile)
            except RuntimeError as exc:
                message = str(exc)
                stagefile_problem = "game_version is missing" in message or "Stagefile directory not found" in message
                if not include_stagefile or not stagefile_problem:
                    raise
                emit({"type": "warning", "message": f"stagefile unavailable, retrying without it: {message}"})
                result_dir = run(False)

        # 第 6 步：BGA 改为运行时从 movie 根按同名读取，转换侧不再保留复制进来的视频文件
        for _bga in tuple(result_dir.glob("*")):
            if _bga.is_file() and _bga.suffix.lower() in _BGA_VIDEO_EXTENSIONS:
                _bga.unlink()

        # 补丁层 <id>-p0.ifs 里可能还藏着基础层没有的 SPL/DPL 谱面。
        # 这一步是可选的：它失败时只发 warning，pass 1 的结果照常返回。
        def run_p0() -> bool:
            with redirect_stdout(sys.stderr):
                try:
                    return (
                        apply_p0_patch(
                            project_root=project_root,
                            sound_root=sound_root,
                            movie_root=movie_root,
                            result_dir=result_dir,
                            result=result,
                            include_bga=not args.no_bga,
                        )
                        > 0
                    )
                except Exception as exc:  # noqa: BLE001 - 补丁层失败不影响基础转换
                    emit({"type": "warning", "message": f"p0 patch layer failed: {type(exc).__name__}: {exc}"})
                    return False

        p0_patch = run_p0()

        # 索引必须先于清单落盘；写索引失败时切回 extract 并重跑，
        # 保证清单声明的模式与实际产物一致。
        if audio_mode == AUDIO_MODE_DIRECT and audio_index is not None:
            try:
                write_direct_audio_index(result_dir, audio_index)
            except OSError as exc:
                emit(
                    {
                        "type": "warning",
                        "message": f"could not write {AUDIO_INDEX_FILE}, falling back to extract: {exc}",
                    }
                )
                audio_mode = AUDIO_MODE_EXTRACT
                audio_index = None
                _AUDIO_MODE_ACTIVE = AUDIO_MODE_EXTRACT
                _DIRECT_AUDIO_SUPPRESSION.clear()
                with redirect_stdout(sys.stderr):
                    result_dir = run(include_stagefile)
                p0_patch = run_p0() or p0_patch
        else:
            # extract 模式不应留下索引，避免缓存目录里出现与清单不符的旧文件
            try:
                (result_dir / AUDIO_INDEX_FILE).unlink(missing_ok=True)
            except OSError:
                pass

        charts = build_manifest(results_root, result_dir, result)
        if not charts:
            emit({"type": "error", "message": f"conversion produced no .bme files in {result_dir}"})
            return 4

        payload = {
            "type": "result",
            "result_dir": str(result_dir.resolve()),
            "charts": charts,
            "song": {
                "song_id": int(result.song_id),
                "song_id_display": str(result.song_id_display),
                "title": str(result.title),
                "artist": str(result.artist),
                "genre": str(result.genre),
                "game_version": int(result.game_version),
            },
            # 转换条件，Java侧依据该值判定能否复用缓存，
            # 因此改变条件重新转换时必须更新该值；
            # p0_patch 记录本次是否真的补进了补丁层谱面，
            # dir_fallback 记录本次是否装上了目录回退，
            # nonstd_ifs_accept 记录本次是否接管了非标准 IFS，
            # split_audio_merge 记录本次是否装上了分片音频合并，
            # audio_mode 记录最终实际生效的音频模式（索引失败会回退为 extract），
            # index_version 是索引结构版本，缺少其中任何一个键的旧缓存一律视为过期
            "options": {
                "include_stagefile": bool(include_stagefile),
                "include_bga": bool(not args.no_bga),
                "include_preview": bool(not args.no_preview),
                "p0_patch": p0_patch,
                "dir_fallback": chart_source_patch["dir_fallback"],
                "nonstd_ifs_accept": chart_source_patch["nonstd_ifs_accept"],
                "split_audio_merge": chart_source_patch["split_audio_merge"],
                "audio_mode": audio_mode,
                "index_version": AUDIO_INDEX_VERSION,
            },
        }

        # 为了在移动到缓存目录后Java侧仍能定位谱面，
        # 同时在输出目录内保留清单文件
        try:
            (result_dir / MANIFEST_FILE).write_text(
                json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8"
            )
        except OSError as exc:
            emit({"type": "warning", "message": f"could not write {MANIFEST_FILE}: {exc}"})

        emit(payload)
        return 0
    except Exception as exc:  # noqa: BLE001 - the manifest is the error channel
        emit({"type": "error", "message": f"{type(exc).__name__}: {exc}", "traceback": traceback.format_exc()})
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
