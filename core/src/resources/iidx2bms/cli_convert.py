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
import sys
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
            # 変換条件。Java側はキャッシュ再利用の可否をこの値で判定するため、
            # 条件を変えた再変換では必ず更新される必要がある。
            "options": {
                "include_stagefile": bool(include_stagefile),
                "include_bga": bool(not args.no_bga),
                "include_preview": bool(not args.no_preview),
            },
        }

        # キャッシュディレクトリへ移動された後でもJava側が譜面を特定できるよう、
        # マニフェストを出力ディレクトリ内にも残す。
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
