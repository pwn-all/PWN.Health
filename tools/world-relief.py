#!/usr/bin/env python3
"""Build app/src/main/assets/world.relief from ETOPO 2022 (NOAA NCEI, public domain), 60 arc-seconds.

The height of the ground under the map, for the relief the app paints between the land and the
lines on it: hills shaded from the north-west and tinted by height, so mountains, valleys and
plains read as what they are at every zoom. Heights only — the shading and the colours are worked
out on the phone, so one file serves the day map and the night one.

Run it only when the relief should change; the asset it writes is committed. It needs h5py, numpy,
Pillow and SciPy, and the Natural Earth land and lakes tools/world-map.py has already fetched into
its cache. ETOPO's file and Natural Earth's are held to their SHA-256 in tools/world-inputs.sha256
(tools/world_inputs.py).

    python3 -m pip install --user h5py numpy pillow scipy
    python3 tools/world-relief.py

The sea is not drawn — the map paints water flat — so every cell outside Natural Earth's land is
set to sea level and the land keeps its own heights, below sea level included (the Dead Sea, the
Caspian depression). A coast is then not dragged down by the sea floor beside it, which the
smoothing on the phone would otherwise spread a cell inland as a dark rim along every shore. ETOPO
also carries the beds of the great lakes — Baikal's lies 443 m below the sea here — and the map
paints lakes flat too, so each of Natural Earth's lakes is filled to the level of the ground around
it: Baikal comes out at 480 m against its true 456, Titicaca at 3,816 against 3,812.

The finest level is ETOPO's own grid, a minute of arc, 1.2 to 1.9 km across in the middle latitudes:
a hill, a valley a walk climbs out of. Its step is 24 m and it costs 15 MB of the 30 the map is
allowed; the two-minute level that would sit above it is left out — at the zooms that would read it
the phone reads the minute instead, four times the cells and still a texture of a few hundred
texels a side — and that is the room the forests have. Greenland and Antarctica are left out, as the
map leaves them out: their ice was 1.2 MB of relief nobody walks on.

Format, varints LEB128 (zigzag where signed):

    "PWNREL1"
    varint levelCount
    per level, coarse to fine: varint cellSeconds, varint tileCells, varint step (metres a unit),
                               varint tilesX, varint tilesY,
                               tilesX * tilesY varint byte lengths, row by row from the north-west
                               (0: an all-sea tile, which stores nothing)
    the tiles' bytes, level by level in the same order

A level's cells start at 180° W and 90° N and run east and south, cellSeconds of arc each. A tile is
a zlib stream of tileCells² zigzag varints, row by row from its north-west corner: each the
difference between a cell's height, in units of step, and the MED prediction from its west, north
and north-west neighbours, as JPEG-LS predicts, with cells outside the tile taken as zero. Cells past
the world's east or south edge in a level's last column or row of tiles are stored as sea.
"""

import json
import pathlib
import sys
import urllib.request
import zlib

import h5py
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

import world_inputs

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "build" / "world-map-cache"
ETOPO = CACHE / "etopo" / "ETOPO_2022_v1_60s_N90W180_surface.nc"
ETOPO_URL = (
    "https://www.ngdc.noaa.gov/thredds/fileServer/global/ETOPO2022/60s/"
    "60s_surface_elev_netcdf/ETOPO_2022_v1_60s_N90W180_surface.nc"
)
OUT = ROOT / "app" / "src" / "main" / "assets" / "world.relief"

# Natural Earth's land and lakes, as tools/world-map.py draws them: what is not land is sea.
LAND = ["ne_10m_land", "ne_10m_minor_islands"]
LAKES = ["ne_10m_lakes", "ne_10m_lakes_europe", "ne_10m_lakes_north_america"]

# Coarse to fine, in seconds of arc a cell, with the step heights are stored in: 16' draws the
# globe, 1' a valley. Each level is the finest averaged two by two as often as it takes, so the phone
# reads the level whose cells are a few pixels across and never decodes a continent to draw a valley.
#
# On a one-minute cell an error of twelve metres tilts the ground by a third of a degree — less than
# the shading shows on a plain and nothing beside a mountain's own slope — and the step is what fits
# the relief beside the map and the forests in the 30 MB.
LEVELS = [(960, 16), (480, 16), (240, 16), (60, 24)]
# Left out, as tools/world-map.py leaves them out: the countries Natural Earth codes so, and
# everything south of the latitude.
LEFT_OUT = ("GRL", "ATA")
SOUTH_LIMIT = -60.0
COUNTRIES = "ne_10m_admin_0_countries"
ISLET_KM = 15.0
TILE = 256
# How many cells out from a lake's outline the ring that sets its level starts.
SHORE_GAP = 1
# A land cell this far below sea level is the sea floor of a fjord the coarse coast swallowed, not a
# depression: the Dead Sea's shore, at about −430 m, is the lowest dry ground there is.
LOWEST_LAND = -440.0


def download():
    if not ETOPO.exists():
        ETOPO.parent.mkdir(parents=True, exist_ok=True)
        print("fetching ETOPO 2022, 60 arc-seconds (about 460 MB)")
        urllib.request.urlretrieve(ETOPO_URL, ETOPO)
    world_inputs.checked(ETOPO)


def heights():
    """ETOPO's surface — the top of the ice where there is ice — as rows from the north."""
    with h5py.File(ETOPO, "r") as source:
        grid = np.asarray(source["z"][:], dtype=np.float32)
        latitudes = np.asarray(source["lat"][:])
    return grid[::-1] if latitudes[0] < latitudes[-1] else grid


def polygons_mask(shape, names):
    """Natural Earth's polygons on the same grid, holes and all."""
    rows, columns = shape
    per_degree = columns / 360.0
    image = Image.new("L", (columns, rows), 0)
    draw = ImageDraw.Draw(image)
    for name in names:
        path = CACHE / f"{name}.geojson"
        if not path.exists():
            sys.exit(f"{path} is missing: run tools/world-map.py first, it fetches Natural Earth")
        for feature in json.loads(world_inputs.checked(path).read_text())["features"]:
            geometry = feature["geometry"]
            if geometry is None:
                continue
            polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
            for polygon in polygons:
                for index, ring in enumerate(polygon):
                    points = [((lon + 180.0) * per_degree, (90.0 - lat) * per_degree) for lon, lat in ring]
                    if len(points) >= 3:
                        draw.polygon(points, fill=0 if index else 1)
    return np.asarray(image, dtype=bool)


def left_out_mask(shape):
    """Greenland and Antarctica on the grid, from Natural Earth's own country outlines."""
    rows, columns = shape
    per_degree = columns / 360.0
    path = CACHE / f"{COUNTRIES}.geojson"
    if not path.exists():
        sys.exit(f"{path} is missing: run tools/world-map.py first, it fetches Natural Earth")
    image = Image.new("L", (columns, rows), 0)
    draw = ImageDraw.Draw(image)
    for feature in json.loads(world_inputs.checked(path).read_text())["features"]:
        if feature["properties"].get("ADM0_A3") not in LEFT_OUT:
            continue
        geometry = feature["geometry"]
        polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
        for polygon in polygons:
            draw.polygon([((lon + 180.0) * per_degree, (90.0 - lat) * per_degree) for lon, lat in polygon[0]], fill=1)
    # Grown by the islets' reach, as tools/world-map.py leaves out what lies just off the outline.
    mask = ndimage.binary_dilation(np.asarray(image, dtype=bool), iterations=max(1, round(ISLET_KM / 111.32 * per_degree)))
    mask[int((90.0 - SOUTH_LIMIT) * per_degree):] = True
    return mask


def flatten_lakes(grid, lakes):
    """Each lake filled to the level of the ground around it; islands in it keep their own ground.

    The ring read for that is one cell out from the shore, not the shore itself: ETOPO's lake beds
    run a cell or so past Natural Earth's outlines, and a shore cell over the bed gave Baikal a
    surface 120 m below the sea. The tenth percentile of the ring rather than its lowest cell, for the
    same reason, and still low enough to be the water's edge rather than the hills beyond it.
    """
    labels, count = ndimage.label(lakes)
    if count == 0:
        return grid
    near = ndimage.binary_dilation(lakes, iterations=SHORE_GAP)
    ring = ndimage.binary_dilation(lakes, iterations=SHORE_GAP + 1) & ~near
    # A ring cell belongs to the lake within reach of it; one between two lakes is given to either.
    beside = np.where(ring, ndimage.grey_dilation(labels, size=2 * SHORE_GAP + 3), 0)
    surface = ndimage.labeled_comprehension(
        grid, beside, np.arange(1, count + 1), lambda heights: np.percentile(heights, 10), np.float64, 0.0,
    )
    level = np.concatenate(([0.0], surface))
    return np.where(lakes, level[labels], grid).astype(np.float32)


def halve(grid):
    rows, columns = grid.shape
    return grid.reshape(rows // 2, 2, columns // 2, 2).mean(axis=(1, 3), dtype=np.float64).astype(np.float32)


def med_residuals(units):
    """The JPEG-LS MED predictor over one tile, zeros outside it; the decoder undoes it exactly."""
    padded = np.pad(units, ((1, 0), (1, 0)))
    west = padded[1:, :-1]
    north = padded[:-1, 1:]
    corner = padded[:-1, :-1]
    low = np.minimum(west, north)
    high = np.maximum(west, north)
    prediction = np.where(corner >= high, low, np.where(corner <= low, high, west + north - corner))
    return units - prediction


def varints(values):
    """Zigzag LEB128 for a whole array at once; a Python loop would take minutes per level."""
    signed = values.astype(np.int64).ravel()
    zigzag = ((signed << 1) ^ (signed >> 63)).astype(np.uint64)
    lengths = np.ones(zigzag.shape, dtype=np.int64)
    for bits in (7, 14, 21, 28):
        lengths += zigzag >= (1 << bits)
    starts = np.concatenate(([0], np.cumsum(lengths)[:-1]))
    out = np.empty(int(lengths.sum()), dtype=np.uint8)
    for k in range(5):
        has = lengths > k
        if not has.any():
            break
        chunk = (zigzag[has] >> np.uint64(7 * k)) & np.uint64(0x7F)
        more = (lengths[has] > k + 1).astype(np.uint64) << np.uint64(7)
        out[starts[has] + k] = (chunk | more).astype(np.uint8)
    return out.tobytes()


def varint(value, out):
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def encode_level(grid, step):
    """Every tile of one level: its index entries and its bytes, sea tiles left out."""
    rows, columns = grid.shape
    tiles_x = -(-columns // TILE)
    tiles_y = -(-rows // TILE)
    units = np.rint(grid / step).astype(np.int32)
    padded = np.zeros((tiles_y * TILE, tiles_x * TILE), dtype=np.int32)
    padded[:rows, :columns] = units
    lengths, bodies = [], []
    for ty in range(tiles_y):
        for tx in range(tiles_x):
            tile = padded[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE]
            if not tile.any():
                lengths.append(0)
                continue
            # Filtered: the residuals are small numbers spread thin, which the Huffman stage codes
            # better than the matcher, 6% smaller than the default strategy.
            packer = zlib.compressobj(9, zlib.DEFLATED, 15, 9, zlib.Z_FILTERED)
            body = packer.compress(varints(med_residuals(tile))) + packer.flush()
            lengths.append(len(body))
            bodies.append(body)
    return tiles_x, tiles_y, lengths, b"".join(bodies)


def main():
    download()
    grid = heights()
    gone = left_out_mask(grid.shape)
    land = polygons_mask(grid.shape, LAND) & ~gone
    grid = np.where(land, np.maximum(grid, LOWEST_LAND), 0.0).astype(np.float32)
    print(f"ETOPO {grid.shape[1]} x {grid.shape[0]}, {land.mean() * 100:.1f}% land")

    # The finest level, ETOPO's own grid, its lakes filled; every coarser one averaged down from it,
    # the ones LEVELS leaves out included, since each is the one under it halved.
    lakes = polygons_mask(grid.shape, LAKES) & ~gone
    grid = flatten_lakes(grid, lakes)
    del land, lakes, gone
    finest = LEVELS[-1][0]
    by_seconds = {finest: grid}
    seconds = finest
    while seconds < LEVELS[0][0]:
        by_seconds[seconds * 2] = halve(by_seconds[seconds])
        seconds *= 2
    pyramid = [by_seconds[seconds] for seconds, _ in LEVELS]

    head = bytearray(b"PWNREL1")
    varint(len(LEVELS), head)
    bodies = []
    for (seconds, step), level in zip(LEVELS, pyramid):
        assert level.shape == (180 * 3600 // seconds, 360 * 3600 // seconds), level.shape
        tiles_x, tiles_y, lengths, body = encode_level(level, step)
        for value in (seconds, TILE, step, tiles_x, tiles_y, *lengths):
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
