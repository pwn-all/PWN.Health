#!/usr/bin/env python3
"""Build app/src/main/assets/world.forest from ESA WorldCover 2021 v200 (CC BY 4.0), the 10 m map
of what covers the ground.

Where the forests are, for the dots the map is drawn in: a cell is forest when trees cover at least
half of it — WorldCover's tree cover and mangroves, counted on its own ten-metre classes. Half a
minute of arc a cell at the finest, about 900 m across in the middle latitudes: the size of a wood a
walk goes through, and as fine as the map's 30 MB allow next to the relief. At that level a patch of
forest under six cells, or a clearing under six cells inside one, is folded into what surrounds it:
0.45% of the cells change, the specks a cell or two across that are as much the classifier's noise as
woods, and the level goes from 8.6 MB to 6.3. The one-minute level is left out, as the relief leaves
out its two-minute one: the phone reads the half minute at those zooms.

WorldCover is 2,651 GeoTIFFs of 36,000 × 36,000 pixels, about a hundred gigabytes. Each is a cloud
optimised GeoTIFF carrying its own overviews, and the one at a sixteenth of the resolution — 2,250
pixels a tile, about 150 m — already holds forty pixels for every cell written here, so only that
overview is read, a range of bytes at a time: a gigabyte or so instead of a hundred. The overview is
the full map resampled by nearest neighbour, which keeps the classes as they are and the share of
trees in a cell unbiased.

Run it only when the forests should change; the asset it writes is committed. It needs NumPy and
Pillow, and the Natural Earth countries tools/world-map.py fetches, to leave out Greenland and
Antarctica as the map does. What it has read is kept in build/world-map-cache/worldcover, so a
second run fetches nothing. The countries file is held to its SHA-256 in tools/world-inputs.sha256
(tools/world_inputs.py); WorldCover, read a range of bytes at a time, is not.

    python3 tools/world-forest.py

Format, varints LEB128:

    "PWNFOR1"
    varint levelCount
    per level, coarse to fine: varint cellSeconds, varint tileCells, varint tilesX, varint tilesY,
                               tilesX * tilesY varint byte lengths, row by row from the north-west
                               (0: a tile with no forest in it, which stores nothing)
    the tiles' bytes, level by level in the same order

A level's cells start at 180° W and 90° N and run east and south, cellSeconds of arc each. A tile is
a zlib stream of tileCells rows of tileCells bits, a set bit a forest cell, eight cells a byte with
the westernmost in the high bit — NumPy's packbits.
"""

import concurrent.futures
import json
import math
import pathlib
import struct
import sys
import urllib.request
import xml.etree.ElementTree as ElementTree
import zlib

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

import world_inputs

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "build" / "world-map-cache"
TILES = CACHE / "worldcover"
OUT = ROOT / "app" / "src" / "main" / "assets" / "world.forest"

BUCKET = "https://esa-worldcover.s3.eu-central-1.amazonaws.com"
PREFIX = "v200/2021/map/"
COUNTRIES_URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_10m_admin_0_countries.geojson"

# WorldCover's classes that are trees: tree cover and mangroves.
TREES = (10, 95)
# Each WorldCover tile is three degrees square; its overview this many pixels across.
TILE_DEGREES = 3
OVERVIEW = 2250
# The finest level, in seconds of arc a cell, and the coarser ones stored, down to the sixteen minutes
# the relief also stops at; each is a whole number of the finest's cells.
FINEST = 30
LEVELS = [960, 480, 240, 120, 30]
# At the finest level, a patch of either kind smaller than this many cells takes the other kind.
SPECK_CELLS = 6
# The speck pass runs in bands of rows; a band reads this many rows past each of its edges, which
# a patch smaller than SPECK_CELLS cannot span.
BAND_ROWS = 1024
BAND_OVERLAP = 8
TILE = 256
# A cell is forest when at least this share of it is trees.
FOREST_SHARE = 0.5
# Left out as the map leaves them out: Greenland and everything south of this.
SOUTH_LIMIT = -60.0
LEFT_OUT = ("GRL", "ATA")


def tile_names():
    """Every map tile in the bucket, from its listing, a thousand keys a page."""
    names, token = [], None
    while True:
        url = f"{BUCKET}/?list-type=2&prefix={PREFIX}&max-keys=1000"
        if token:
            url += "&continuation-token=" + urllib.request.quote(token, safe="")
        tree = ElementTree.fromstring(urllib.request.urlopen(url, timeout=60).read())
        space = {"s3": tree.tag.split("}")[0].strip("{")}
        names += [key.text for key in tree.findall("s3:Contents/s3:Key", space) if key.text.endswith("_Map.tif")]
        if tree.findtext("s3:IsTruncated", namespaces=space) != "true":
            return names
        token = tree.findtext("s3:NextContinuationToken", namespaces=space)


# The TIFF tags an overview is read with: its width, compression, predictor, tile size, and where
# its tiles lie.
WANTED_TAGS = {256, 259, 317, 322, 324, 325}


def fetch(url, first, last):
    request = urllib.request.Request(url, headers={"Range": f"bytes={first}-{last}"})
    for attempt in range(5):
        try:
            return urllib.request.urlopen(request, timeout=120).read()
        except OSError:
            if attempt == 4:
                raise
    raise AssertionError


def overview_of(url):
    """The 2,250-pixel overview of one tile, read from its IFDs and tiles with range requests."""
    head = fetch(url, 0, 262_143)
    if head[:4] != b"II*\x00":
        raise ValueError(f"{url}: not a little-endian classic TIFF")

    def array(type_, count, value_offset, raw):
        size = {3: 2, 4: 4}[type_]
        if count * size <= 4:
            return list(struct.unpack_from("<" + "HI"[size == 4] * count, raw))
        data = head if value_offset + count * size <= len(head) else fetch(url, value_offset, value_offset + count * size - 1)
        start = value_offset if data is head else 0
        return list(struct.unpack_from("<" + "HI"[size == 4] * count, data, start))

    ifd = struct.unpack_from("<I", head, 4)[0]
    while ifd:
        if ifd + 2 > len(head):
            raise ValueError(f"{url}: IFD past the header")
        tags = {}
        count = struct.unpack_from("<H", head, ifd)[0]
        for entry in range(count):
            tag, type_, n = struct.unpack_from("<HHI", head, ifd + 2 + entry * 12)
            if tag not in WANTED_TAGS:
                continue  # the GeoTIFF keys and the rest: the tile's corner is in its name
            raw = head[ifd + 10 + entry * 12:ifd + 14 + entry * 12]
            tags[tag] = array(type_, n, struct.unpack("<I", raw)[0], raw)
        if tags[256][0] == OVERVIEW:
            if tags[259][0] != 8 or tags.get(317, [1])[0] != 1:
                raise ValueError(f"{url}: not plain deflate")
            size = tags[322][0]
            across = math.ceil(OVERVIEW / size)
            offsets, counts = tags[324], tags[325]
            first, last = min(offsets), max(o + c for o, c in zip(offsets, counts)) - 1
            blob = fetch(url, first, last)
            canvas = np.zeros((across * size, across * size), np.uint8)
            for index, (offset, length) in enumerate(zip(offsets, counts)):
                piece = np.frombuffer(zlib.decompress(blob[offset - first:offset - first + length]), np.uint8)
                row, column = divmod(index, across)
                canvas[row * size:(row + 1) * size, column * size:(column + 1) * size] = piece.reshape(size, size)
            return canvas[:OVERVIEW, :OVERVIEW]
        ifd = struct.unpack_from("<I", head, ifd + 2 + count * 12)[0]
    raise ValueError(f"{url}: no {OVERVIEW}-pixel overview")


def shares_of(name):
    """The share of trees in each of the tile's cells at the finest level, cached as it is read."""
    path = TILES / (pathlib.Path(name).stem + ".npy")
    if path.exists():
        return np.load(path)
    picture = overview_of(f"{BUCKET}/{name}")
    cells = TILE_DEGREES * 3600 // FINEST
    index = (np.arange(OVERVIEW) + 0.5) * cells // OVERVIEW
    flat = (index.astype(np.int64)[:, None] * cells + index.astype(np.int64)[None, :]).ravel()
    trees = np.bincount(flat, weights=np.isin(picture, TREES).ravel(), minlength=cells * cells)
    every = np.bincount(flat, minlength=cells * cells)
    share = np.round(trees / np.maximum(every, 1) * 250).astype(np.uint8).reshape(cells, cells)
    TILES.mkdir(parents=True, exist_ok=True)
    np.save(path, share)
    return share


def left_out_mask(rows, columns):
    """Greenland and Antarctica on the finest grid, from Natural Earth's own country outlines."""
    path = CACHE / "ne_10m_admin_0_countries.geojson"
    if not path.exists():
        CACHE.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(COUNTRIES_URL, path)
    image = Image.new("1", (columns, rows))
    draw = ImageDraw.Draw(image)
    for feature in json.loads(world_inputs.checked(path).read_text())["features"]:
        if feature["properties"].get("ADM0_A3") not in LEFT_OUT:
            continue
        geometry = feature["geometry"]
        polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
        for polygon in polygons:
            ring = polygon[0]
            draw.polygon([((lon + 180) * columns / 360, (90 - lat) * rows / 180) for lon, lat in ring], fill=1)
    mask = np.array(image, bool)
    mask[int((90 - SOUTH_LIMIT) * rows / 180):] = True
    return mask


def without_specks(forest):
    """Patches of either kind under SPECK_CELLS cells folded into what surrounds them, a band of rows
    at a time: a labelling of the whole grid at once would need four gigabytes. A patch that touches
    a band's core and is small enough to fold cannot reach past the band's overlap, so each band's
    core comes out as the whole grid's would."""
    out = forest.copy()
    rows = forest.shape[0]
    for top in range(0, rows, BAND_ROWS):
        first, last = max(top - BAND_OVERLAP, 0), min(top + BAND_ROWS + BAND_OVERLAP, rows)
        band = forest[first:last].copy()
        for kind in (True, False):
            # Edge-connected, as GDAL's sieve counts a patch: two cells touching at a corner are two.
            labels, count = ndimage.label(band == kind)
            if count:
                small = np.bincount(labels.ravel()) < SPECK_CELLS
                small[0] = False
                band[small[labels]] = not kind
        out[top:min(top + BAND_ROWS, rows)] = band[top - first:top - first + min(BAND_ROWS, rows - top)]
    return out


def varint(value, out):
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def encode_level(forest):
    rows, columns = forest.shape
    tiles_y, tiles_x = math.ceil(rows / TILE), math.ceil(columns / TILE)
    padded = np.zeros((tiles_y * TILE, tiles_x * TILE), bool)
    padded[:rows, :columns] = forest
    lengths, body = [], bytearray()
    for ty in range(tiles_y):
        for tx in range(tiles_x):
            tile = padded[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE]
            if not tile.any():
                lengths.append(0)
                continue
            data = zlib.compress(np.packbits(tile, axis=1).tobytes(), 9)
            lengths.append(len(data))
            body += data
    return tiles_x, tiles_y, lengths, body


def main():
    names = tile_names()
    print(f"{len(names)} WorldCover tiles")
    cells = 3600 // FINEST
    shares = np.zeros((180 * cells, 360 * cells), np.uint8)
    done = 0
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        for name, share in zip(names, pool.map(shares_of, names)):
            corner = pathlib.Path(name).stem.split("_")[-2]
            lat = int(corner[1:3]) * (1 if corner[0] == "N" else -1)
            lon = int(corner[4:7]) * (1 if corner[3] == "E" else -1)
            row, column = (90 - lat - TILE_DEGREES) * cells, (lon + 180) * cells
            shares[row:row + share.shape[0], column:column + share.shape[1]] = share
            done += 1
            if done % 200 == 0:
                print(f"  {done} tiles read", flush=True)
    shares[left_out_mask(*shares.shape)] = 0
    print(f"forest cells at {FINEST}\": {(shares >= FOREST_SHARE * 250).mean() * 100:.2f}% of the grid")

    head = bytearray(b"PWNFOR1")
    varint(len(LEVELS), head)
    bodies = []
    for seconds in LEVELS:
        factor = seconds // FINEST
        level = shares if factor == 1 else shares.reshape(
            shares.shape[0] // factor, factor, shares.shape[1] // factor, factor
        ).mean(axis=(1, 3))
        forest = level >= FOREST_SHARE * 250
        if seconds == FINEST:
            forest = without_specks(forest)
        tiles_x, tiles_y, lengths, body = encode_level(forest)
        for value in (seconds, TILE, tiles_x, tiles_y, *lengths):
            varint(value, head)
        stored = sum(1 for length in lengths if length)
        print(f"{seconds:>4}\" cells  {stored:>5} of {len(lengths):>5} tiles  {len(body) / 1e6:6.2f} MB")
        bodies.append(body)
    out = bytes(head) + b"".join(bodies)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(out)
    print(f"{OUT.relative_to(ROOT)}: {len(out) / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
