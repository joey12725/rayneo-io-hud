"""Build uncommitted, native-pixel contact sheets of every bitmap golden (Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/build"
OUTPUT.mkdir(parents=True, exist_ok=True)
paths = sorted((ROOT / "app/src/test/resources/golden").glob("*.png"))
for offset in range(0, len(paths), 18):
    subset = paths[offset:offset + 18]
    sheet = Image.new("RGB", (560 * 3, 252 * ((len(subset) + 2) // 3)), "#202428")
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(subset):
        x, y = index % 3 * 560, index // 3 * 252
        draw.text((x + 8, y + 4), path.stem, fill="white")
        with Image.open(path) as frame:
            assert frame.size == (540, 220), (path, frame.size)
            sheet.paste(frame, (x + 8, y + 24))
    target = OUTPUT / f"contact-sheet-{offset // 18 + 1}.png"
    sheet.save(target)
    print(target)
