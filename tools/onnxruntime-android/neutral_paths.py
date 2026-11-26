"""Rewrite the build directory inside the AAR's native libraries to a neutral path of the same length.

ONNX Runtime compiles its source paths into libonnxruntime.so for its error messages, so every
build carries the directory it was built in. Each occurrence of that directory is replaced by
"/ortbuild/" padded with "./" to the same length: no offset in the library moves, and every path
still names the same file under the build tree.

    python3 -I tools/onnxruntime-android/neutral_paths.py <aar> <build directory>
"""
import sys
import zipfile
from pathlib import Path


def neutral(prefix: bytes) -> bytes:
    """'/ortbuild/' and then './' up to the prefix's length, '/' once more if it is odd."""
    head = b"/ortbuild/"
    if len(prefix) < len(head):
        raise SystemExit(f"build directory shorter than {head!r}: {prefix!r}")
    pad = len(prefix) - len(head)
    out = head + b"./" * (pad // 2) + b"/" * (pad % 2)
    assert len(out) == len(prefix)
    return out


def main(aar: Path, work: str) -> None:
    prefix = (work.rstrip("/") + "/").encode()
    replacement = neutral(prefix)
    with zipfile.ZipFile(aar) as src:
        entries = [(info, src.read(info)) for info in src.infolist()]
    total = 0
    rewritten = []
    for info, data in entries:
        if info.filename.startswith("jni/") and info.filename.endswith(".so"):
            count = data.count(prefix)
            data = data.replace(prefix, replacement)
            total += count
        rewritten.append((info, data))
    if total == 0:
        raise SystemExit(f"{prefix.decode()} is not in any library of {aar}")
    tmp = aar.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w") as out:
        for info, data in rewritten:
            out.writestr(info, data)
    tmp.replace(aar)
    for _, data in rewritten:
        assert prefix not in data
    print(f"{total} paths rewritten in {aar.name}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(Path(sys.argv[1]), sys.argv[2])
