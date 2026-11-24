# /// script
# requires-python = ">=3.9"
# dependencies = ["pillow>=10"]
# ///
"""Build an avatar pack (manifest.json + atlas.png, schema 1) from folders of frames.

    uv run tools/avatar/pack_from_frames.py FRAMES_DIR OUT_DIR [options]

FRAMES_DIR holds one folder per state, named with a canonical state name (see docs/avatar-packs.md),
each containing the frames in order, for example:

    frames/idle/00.png  frames/idle/01.png  frames/idle/02.png
    frames/speaking/00.png ...
    frames/thinking.gif           (an animated GIF also works; its frame durations are kept)

Optional FRAMES_DIR/pack.json supplies manifest fields and per-state overrides:

    {"id": "my-pack", "name": "My avatar", "license": "CC-BY-4.0",
     "portraitCrop": [16, 50, 160, 160], "threshold": 100,
     "states": {"asking": {"playback": "once_then_hold"}, "dancing": {"playback": "bounded_loop", "boundedLoopSeconds": 4}},
     "fallbacks": {"compressing": "working"}}

Frames are converted to one bit: a pixel is lit when its brightness (or, with --green, its green channel)
is >= --cut. Light-on-dark artwork works as is; use --invert for dark-on-light drawings. Every frame is
scaled to the cell size (default: the first frame's size; 2:3 such as 192x288 is recommended).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from PIL import Image, ImageOps, ImageSequence

STATE = re.compile(r"^[a-z][a-z0-9_]{0,47}$")
CANONICAL = (
    "idle sleeping waking listening thinking working searching remembering saving speaking waiting delegating "
    "compressing asking interrupted error happy laughing smirking playful heart_eyes blushing sad concerned skeptical "
    "curious surprised facepalm celebrating proud confused romantic spicy nerdy creative philosophical serious comforting "
    "teaching debating secretive looking_around stretching waving nodding shaking_head winking dancing coffee checking_watch"
).split()
# Playback the app uses for each state when pack.json does not say (the canonical library's choices).
PLAYBACK = {
    "waking": "once", "saving": "once_then_hold", "delegating": "once", "asking": "once_then_hold", "interrupted": "once",
    "error": "once", "romantic": "subtle_loop", "nerdy": "subtle_loop", "creative": "subtle_loop",
    "philosophical": "subtle_loop", "serious": "once_then_hold", "comforting": "once_then_hold", "teaching": "subtle_loop",
    "debating": "subtle_loop", "dancing": "bounded_loop",
}
LOOPING = set("idle sleeping listening thinking working searching remembering speaking waiting compressing".split())
CORE = ["idle", "listening", "thinking", "speaking", "working", "error", "happy"]


def playback_for(state: str) -> str:
    if state in PLAYBACK:
        return PLAYBACK[state]
    return "loop" if state in LOOPING else "once"


def load_frames(path: Path, default_ms: int) -> list[tuple[Image.Image, int]]:
    """Frames of one state: a folder of images (sorted by name) or one animated GIF/PNG."""
    if path.is_dir():
        files = sorted(p for p in path.iterdir() if p.suffix.lower() in (".png", ".gif", ".webp", ".bmp"))
        return [(Image.open(p).convert("RGB"), default_ms) for p in files]
    image = Image.open(path)
    return [(f.convert("RGB"), int(f.info.get("duration") or default_ms)) for f in ImageSequence.Iterator(image)]


def one_bit(frame: Image.Image, size: tuple[int, int], cut: int, green: bool, invert: bool) -> Image.Image:
    if frame.size != size:
        frame = frame.resize(size, Image.Resampling.LANCZOS)
    channel = frame.getchannel("G") if green else frame.convert("L")
    if invert:
        channel = ImageOps.invert(channel)
    return channel.point(lambda v: 255 if v >= cut else 0, mode="1")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("frames", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--cell", help="cell size WxH (default: first frame's size)")
    ap.add_argument("--cols", type=int, default=8, help="atlas columns (default 8)")
    ap.add_argument("--ms", type=int, default=350, help="frame duration when the source has none (default 350)")
    ap.add_argument("--cut", type=int, default=100, help="lit when brightness >= cut (default 100)")
    ap.add_argument("--green", action="store_true", help="threshold the green channel instead of brightness")
    ap.add_argument("--invert", action="store_true", help="dark-on-light artwork")
    ap.add_argument("--id", help="pack id (default: pack.json id, else the frames folder name)")
    ap.add_argument("--name", help="display name")
    ap.add_argument("--license", help="SPDX licence id, for example CC0-1.0 or CC-BY-4.0")
    ap.add_argument("--strict", action="store_true", help="fail on non-canonical state names")
    args = ap.parse_args(argv)

    meta = {}
    if (args.frames / "pack.json").is_file():
        meta = json.loads((args.frames / "pack.json").read_text())
    sources = {}
    for p in sorted(args.frames.iterdir()):
        name = p.stem if p.is_file() else p.name
        if p.name == "pack.json" or name.startswith("."):
            continue
        if p.is_file() and p.suffix.lower() not in (".gif", ".png", ".webp"):
            continue
        if not STATE.match(name):
            print(f"skip {p.name}: state names are lowercase letters, digits and _", file=sys.stderr)
            continue
        if name not in CANONICAL:
            msg = f"{name} is not a canonical state; the app will only show it if something asks for it"
            if args.strict:
                ap.error(msg)
            print("warning: " + msg, file=sys.stderr)
        sources[name] = p
    if "idle" not in sources:
        ap.error("an idle state is required (FRAMES_DIR/idle/ or idle.gif)")

    loaded = {name: load_frames(p, args.ms) for name, p in sources.items()}
    loaded = {k: v for k, v in loaded.items() if v}
    first = loaded["idle"][0][0]
    if args.cell:
        cw, ch = (int(v) for v in args.cell.lower().split("x"))
    else:
        cw, ch = first.size
    cells, states = [], {}
    overrides = meta.get("states", {})
    for name, frames in loaded.items():
        o = overrides.get(name, {})
        entry = {"playback": o.get("playback", playback_for(name)), "frames": []}
        if "boundedLoopSeconds" in o:
            entry["boundedLoopSeconds"] = o["boundedLoopSeconds"]
        ms_override = o.get("ms")  # one number for every frame, or a list per frame
        for i, (frame, ms) in enumerate(frames):
            cells.append(one_bit(frame, (cw, ch), args.cut, args.green, args.invert))
            if isinstance(ms_override, list) and i < len(ms_override):
                ms = ms_override[i]
            elif isinstance(ms_override, (int, float)):
                ms = ms_override
            entry["frames"].append({"cell": len(cells) - 1, "ms": int(ms)})
        states[name] = entry
    cols = max(1, min(args.cols, len(cells)))
    rows = (len(cells) + cols - 1) // cols
    lit = Image.new("L", (cols * cw, rows * ch), 0)
    for k, cell in enumerate(cells):
        lit.paste(cell.convert("L"), ((k % cols) * cw, (k // cols) * ch))
    # Palette image: index 0 black, index 1 pure green (what the glasses draw).
    atlas = Image.frombytes("P", lit.size, bytes(1 if v else 0 for v in lit.tobytes()))
    atlas.putpalette([0, 0, 0, 0, 255, 0])

    pack_id = args.id or meta.get("id") or re.sub(r"[^a-z0-9._-]", "-", args.frames.resolve().name.lower()).strip("-.") or "my-pack"
    side = min(cw, ch)
    manifest = {
        "schema": 1,
        "id": pack_id,
        "name": args.name or meta.get("name") or pack_id,
        "license": args.license or meta.get("license") or "unspecified",
        "cell": {"w": cw, "h": ch},
        "atlas": {"file": "atlas.png", "cols": cols},
        "portraitCrop": meta.get("portraitCrop") or [(cw - side) // 2, 0, side, side],
        "threshold": int(meta.get("threshold", 100)),
        "states": states,
        "fallbacks": meta.get("fallbacks", {}),
    }
    args.out.mkdir(parents=True, exist_ok=True)
    atlas.save(args.out / "atlas.png", optimize=True, bits=1)
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n")
    missing = [s for s in CORE if s not in states]
    print(f"{pack_id}: {len(states)} states, {len(cells)} cells of {cw}x{ch}, atlas {cols * cw}x{rows * ch} -> {args.out}")
    if missing:
        print("note: core states missing (they will fall back): " + ", ".join(missing))
    print(f"zip it for the phone: (cd {args.out} && zip -r ../{pack_id}.zip manifest.json atlas.png)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
