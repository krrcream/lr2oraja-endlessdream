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


def _dir_has_chart_payload(song_dir: Path, song_id_display: str) -> bool:
    """目录里是否有一份可当作 IFS 载荷用的谱面：谱面表加主音频。

    上游 trio 判定要的是 <id>.s3p，而这里的目录主音频是 <id>.2dx，
    所以两者都认；<id>_pre.2dx 由调用方另外判定。
    """
    if not (song_dir / f"{song_id_display}.1").is_file():
        return False
    return (song_dir / f"{song_id_display}.2dx").is_file() or (song_dir / f"{song_id_display}.s3p").is_file()


def _chart_payload_from_extracted_dir(extracted_chart_dir: Path, song_id_display: str) -> dict[str, Path] | None:
    """在一份已解包的 IFS 载荷目录里挑出可用的三件套，挑不出返回 None。

    与上游的差别只有一条：不要求目录里的文件集与标准三件套完全相等。
    主音频(<id>.2dx 或 <id>.s3p)与 <id>_pre.2dx 都在场就够了，
    多出来的文件(<id>a.2dx / <id>w.2dx 旁支容器、<id>N.2dx 分块等)一律忽略。
    只有分块、没有 <id>.2dx 与 <id>.s3p 的包(13 首)在这里挑不出主音频，返回 None。
    """
    one_file = extracted_chart_dir / f"{song_id_display}.1"
    audio_2dx_file = extracted_chart_dir / f"{song_id_display}.2dx"
    audio_s3p_file = extracted_chart_dir / f"{song_id_display}.s3p"
    preview_file = extracted_chart_dir / f"{song_id_display}_pre.2dx"
    # 两个容器同时在场的包(如 27100)取 .2dx，与上游自己的偏好一致
    audio_file = audio_2dx_file if audio_2dx_file.is_file() else audio_s3p_file
    if not one_file.is_file() or not audio_file.is_file() or not preview_file.is_file():
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
        省掉一次解包；仍然挑不出主音频的包(13 首分块音频)原样抛出上游的报错。
        两条路径都复用 "ifs" 这个 kind：上游 "standard" 分支写死了
        _extract_wavs_from_s3p，而这里的主音频全是 .2dx，选错必失败。
      * 这两种包都不保证音频完整(分块与旁支容器都不参与音源提取)，照发 warning。

    返回补丁层的标志位，供 manifest 记录。上游若把这些私有入口重构掉，
    这里只发 warning 并返回全 False，转换退回上游原有行为，绝不因此失败。
    """
    patch_flags = {"dir_fallback": False, "nonstd_ifs_accept": False}
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
            audio_file = audio_2dx_file if audio_2dx_file.is_file() else audio_s3p_file
            if not one_file.is_file() or not audio_file.is_file():
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
            return _copy_chart_files({"one": one_file, "audio": audio_file, "preview": preview_file}, work_dir)

        try:
            return original_prepare_from_ifs(project_root, work_dir, song_id_display, ifs_file)
        except RuntimeError as exc:
            # 只接管文件集「非标准」这一种失败；解不开包、缺 <id>_pre.2dx 等
            # 其他失败原样抛回，报错文本不变
            if "is non-standard" not in str(exc):
                raise
            original_error = exc

        # 上游已经把这个包解在 work_dir/<id>_ifs/<id> 下；这里直接接着用它，
        # 顺带也说明为什么找不到这个目录时要退回上游原来的报错
        chart_dir = Path(work_dir) / f"{song_id_display}_ifs" / str(song_id_display)
        files = _chart_payload_from_extracted_dir(chart_dir, str(song_id_display)) if chart_dir.is_dir() else None
        if files is None:
            # 主音频被切成 <id>N.2dx 分片的包(13 首)救不了，原样抛出上游的报错
            raise original_error

        selected_names = {item.name for item in files.values()}
        ignored = sorted(
            path.name for path in chart_dir.iterdir() if path.is_file() and path.name not in selected_names
        )
        emit(
            {
                "type": "warning",
                "message": (
                    f"Chart {song_id_display}: {song_id_display}.ifs is non-standard, "
                    f"ignoring {ignored} and using {files['audio'].name} as the main audio; "
                    f"the audio may be incomplete"
                ),
            }
        )
        return _copy_chart_files(files, work_dir)

    try:
        conversion_module._find_chart_source = find_chart_source
        conversion_module._prepare_from_ifs = prepare_from_ifs
    except Exception as exc:  # noqa: BLE001 - 装不上就退回上游行为
        emit({"type": "warning", "message": f"chart source patch skipped: {type(exc).__name__}: {exc}"})
        return patch_flags
    return {"dir_fallback": True, "nonstd_ifs_accept": True}


def main(argv: list[str]) -> int:
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

        # 补丁层 <id>-p0.ifs 里可能还藏着基础层没有的 SPL/DPL 谱面。
        # 这一步是可选的：它失败时只发 warning，pass 1 的结果照常返回。
        p0_patch = False
        with redirect_stdout(sys.stderr):
            try:
                p0_patch = (
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
                p0_patch = False

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
            # 缺少其中任何一个键的旧缓存一律视为过期
            "options": {
                "include_stagefile": bool(include_stagefile),
                "include_bga": bool(not args.no_bga),
                "include_preview": bool(not args.no_preview),
                "p0_patch": p0_patch,
                "dir_fallback": chart_source_patch["dir_fallback"],
                "nonstd_ifs_accept": chart_source_patch["nonstd_ifs_accept"],
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
