"""Validate the packaged code archive against the hash-locked official input."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

repo = Path(__file__).resolve().parents[1]
source = json.loads((repo / "android/official-runtime-manifest.json").read_text())
assets = repo / "android/app/src/officialAudio/assets/official_runtime"
manifest = json.loads((assets / "runtime-manifest.json").read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--apk", type=Path, default=repo.parent / source["apk_path"])
original = parser.parse_args().apk
assert hashlib.sha256(original.read_bytes()).hexdigest() == source["apk_sha256"]
assert len(manifest["files"]) == 9 and len(manifest["owned"]) == 3
assert list(manifest["code"]) == ["official.apk"]
for group in ["files", "owned", "code"]:
    for name, digest in manifest[group].items():
        assert hashlib.sha256((assets / name).read_bytes()).hexdigest() == digest, name
with zipfile.ZipFile(original) as before, zipfile.ZipFile(
    assets / "official.apk"
) as after, (assets / "official.apk").open("rb") as raw:
    assert before.namelist() == after.namelist()
    dex = 0
    for entry in after.infolist():
        assert before.read(entry.filename) == after.read(entry.filename), entry.filename
        if not entry.filename.endswith(".dex"):
            continue
        dex += 1
        assert entry.compress_type == zipfile.ZIP_STORED
        raw.seek(entry.header_offset)
        header = raw.read(30)
        assert header[:4] == b"PK\x03\x04"
        name_length, extra_length = struct.unpack_from("<HH", header, 26)
        assert (entry.header_offset + 30 + name_length + extra_length) % 4 == 0
    assert dex == 33
print(
    f"{dex} DEX stored/aligned; all original code/resources byte-identical; every manifest hash verified"
)
