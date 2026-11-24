"""Independent native 4-bit hand-off hashes; mirror codes use gamma 1 (already encoded)."""
from pathlib import Path
from hashlib import sha256
from PIL import Image

resources = Path(__file__).resolve().parents[1] / "app/src/test/resources"
destination = resources / "rastergolden"
destination.mkdir(exist_ok=True)
for name in ("home", "joy", "t3-approval"):
    image = Image.open(resources / "golden" / f"{name}.png").convert("RGB")
    assert image.size == (540, 220)
    output = bytes(round(p[1] / 17) * 17 for p in image.getdata())
    digest = sha256(output).hexdigest()
    (destination / f"{name}.sha256").write_text(digest + "\n" + digest + "\n")
    print(name, digest)
