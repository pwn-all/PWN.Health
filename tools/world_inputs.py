"""The files the world-*.py builders download whole, each by its SHA-256, in world-inputs.sha256.

The assets those scripts write are committed and ship in the app; the list is the record of what
they were made from. A builder passes every such file through checked() before it reads it, fetched
in this run or found in the cache. A name the list has must be that file: another one stops the
run. A name it does not have is added, and the new line is committed with the asset. To take a
newer file on purpose — GeoNames republishes cities500.zip under the same name — delete its line
and the cached copy.

Natural Earth's eleven lines are the files that build the committed world.map byte for byte.
ETOPO's file and cities500.zip have no line yet. The copies the committed world.relief and
world.towns were made from were not hashed when they were read, and a line written from a later
download would name a file nothing here was built from: each gets its line from the cache of the
machine that built the asset, while that still holds it, or from the run that next rebuilds it.
WorldCover is not in the list: its tiles are read a range of bytes at a time, never whole.
"""
import hashlib
import pathlib

LIST = pathlib.Path(__file__).with_name("world-inputs.sha256")


def checked(path):
    """`path`, once its SHA-256 is the one the list has for its name or has been added to it."""
    with open(path, "rb") as file:
        digest = hashlib.file_digest(file, "sha256").hexdigest()  # Python 3.11
    name = pathlib.Path(path).name
    known = dict(line.split()[::-1] for line in LIST.read_text().splitlines()) if LIST.exists() else {}
    if name not in known:
        with LIST.open("a") as out:
            out.write(f"{digest}  {name}\n")
        print(f"{LIST.name}: {name} added, to be committed with the asset")
    elif known[name] != digest:
        raise SystemExit(f"{path} is not the {name} that {LIST.name} names: {digest}, not {known[name]}")
    return path


if __name__ == "__main__":  # self-check: python3 tools/world_inputs.py
    import tempfile

    with tempfile.TemporaryDirectory() as folder:
        LIST = pathlib.Path(folder, "list")
        sample = pathlib.Path(folder, "sample")
        sample.write_bytes(b"one")
        assert checked(sample) == sample and checked(sample) == sample  # added, then recognised
        sample.write_bytes(b"two")
        try:
            checked(sample)
        except SystemExit:
            print("ok: a changed file stops the run")
        else:
            raise AssertionError("a changed file passed")
