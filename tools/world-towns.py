#!/usr/bin/env python3
"""Build app/src/main/assets/world.towns from GeoNames (CC BY 4.0) and ESA WorldCover 2021 v200
(CC BY 4.0): where the walk map's towns stand, how far they reach, and which way they run.

GeoNames says where a place is and how many people live there: every place of five hundred or more,
and every seat of an administration. WorldCover says what is built up, read from the 75 m overview of
its ten-metre map. Round each place the built-up ground is gathered into blots — gaps of up to four
pixels closed, holes filled — and the blot the place stands in, or the nearest one within reach, is
its ground. A blot that holds several places is shared among them as a weighted Voronoi diagram,
each weighted by the radius its population suggests, so a city keeps the ground between its suburbs
and a suburb keeps its own. The town is then written as the middle of its ground, the radius of a
circle as large, and, where the ground runs one way, the stretch and the turn of an ellipse of the
same area. GeoNames' point marks a square or a town hall, which may stand at one end of the place;
the middle of what is built is where its blot belongs.

A place with no built-up ground within reach — a village under trees, a point set in a field — keeps
its own point and the area its population suggests, as a circle. The area of a place WorldCover sees
far less or far more of than its people suggest is held to between a third of that and four times
it: the ground missed under a canopy, or a port and an airfield that joined a hamlet's blot.

Run it only when the towns should change; the asset it writes is committed. It reads WorldCover's
overviews once by HTTP range requests, gigabytes of them at 2.9 MB for a tile of Moravia, and keeps
only each tile's built-up pixels: 121 MB in build/world-map-cache/builtup. Needs numpy and scipy,
and Pillow for tools/world-forest.py, whose tile listing and overview reader it uses. GeoNames
republishes cities500.zip under the one name, so the copy a run read is the one named by SHA-256 in
tools/world-inputs.sha256 (tools/world_inputs.py), and another stops the run until its line goes.

    python3 tools/world-towns.py

Format of world.towns, integers LEB128 varints, zigzag where signed:

    "PWNTOWN2"
    varint townCount
    one zlib stream: townCount * (zigzag varint dLon, zigzag varint dLat,
                                  varint radius << 1 | drawnOut, [byte stretch << 4 | turn])
                     the longitude and latitude of the town's middle in thousandths of a degree,
                     the first absolute, the rest deltas; the radius of a circle of the town's area
                     in units of RADIUS_UNIT metres. A town drawn out is the ellipse of that area
                     whose long axis is 2 ** (stretch / 4) times its short one, turned turn * 180/16
                     degrees from east toward south. The towns follow a Hilbert curve, so each delta
                     is to a neighbour: a tenth smaller than in order of latitude.
"""

import concurrent.futures
import importlib.util
import math
import pathlib
import sys
import urllib.request
import zipfile
import zlib

import numpy as np
from scipy import ndimage

import world_inputs

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "build" / "world-map-cache"
BUILT = CACHE / "builtup"
OUT = ROOT / "app" / "src" / "main" / "assets" / "world.towns"

# tools/world-forest.py lists WorldCover's tiles and reads their overviews; a hyphen keeps it from
# being imported by name.
_spec = importlib.util.spec_from_file_location("world_forest", pathlib.Path(__file__).with_name("world-forest.py"))
forest = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(forest)

PLACES_URL = "https://download.geonames.org/export/dump/cities500.zip"
# Sections of a city, and places abandoned, historical or destroyed: the city has a row of its own.
LEFT_OUT_CODES = {"PPLX", "PPLQ", "PPLH", "PPLW", "PPLCH"}
# Left out as the rest of the map leaves them out.
LEFT_OUT_COUNTRIES = {"GL", "AQ"}
SOUTH_LIMIT = -60.0
# Two places nearer than this are one village twice over, as itself and as its municipality's seat;
# the more populous is kept.
SAME_PLACE_M = 300.0

# The built-up area a population suggests, in km²: AREA_K * people ** AREA_POWER, fitted to the
# ground WorldCover finds round 1,906 towns and villages of its tile N48E015, each alone in its blot:
# 0.34 km² at 680 people, 0.73 at 1,500, 1.75 at 4,800, 5.0 at 16,000. It weighs a place in a shared
# blot, bounds what is measured, and stands in where nothing is.
AREA_K = 0.0014
AREA_POWER = 0.85
LEAST_PEOPLE = 500

BUILT_UP = 50
# Pixels across a tile's overview: 75 m in latitude.
OVERVIEW = 4500
PER_DEGREE = OVERVIEW // forest.TILE_DEGREES
CLOSING = 2
EIGHT_WAYS = np.ones((3, 3), bool)
# How far round a tile its neighbours are read, in degrees, so a blot across the edge is one blot.
MARGIN = 0.25
# A place outside every blot takes the nearest within this or its own expected radius, the farther.
SEED_LEAST_M = 300.0
AREA_BOUNDS = (1 / 3, 4.0)
# Fewer pixels than this say nothing about which way a town runs.
SHAPE_LEAST_PIXELS = 12
TURNS = 16
STRETCH_STEPS = 4
MOST_STRETCH = 15
PLACES = 1000
RADIUS_UNIT = 50
# A town is an ellipse when it is 600 m in radius or more and runs at least 1.54 to one, three steps
# of stretch once rounded. A smaller or rounder town's ellipse is a dot or two away from its circle on
# the map, and an ellipse for every town that runs one way, 160,000 of them, would be 172 kB the
# map's 30 MB has no room for.
LEAST_STRETCH = 3
SHAPE_LEAST_RADIUS = 600 // RADIUS_UNIT
METRES_PER_DEGREE = 111_320.0
PIXEL_M = METRES_PER_DEGREE / PER_DEGREE
# Pixel-by-place distances computed at once when a blot is shared out.
VORONOI_CHUNK = 20_000_000


def expected_radius(people):
    return math.sqrt(AREA_K * max(people, LEAST_PEOPLE) ** AREA_POWER / math.pi) * 1000


def places():
    """GeoNames' places as (lat, lon, people), less those the map leaves out and less the smaller of
    any two nearer than SAME_PLACE_M."""
    path = CACHE / pathlib.Path(PLACES_URL).name
    if not path.exists():
        print(f"fetching {path.name}")
        CACHE.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(PLACES_URL, path)
    rows = []
    with zipfile.ZipFile(world_inputs.checked(path)) as archive:
        for line in archive.read(path.with_suffix(".txt").name).decode("utf-8").splitlines():
            fields = line.split("\t")
            lat, lon = float(fields[4]), float(fields[5])
            if fields[7] in LEFT_OUT_CODES or fields[8] in LEFT_OUT_COUNTRIES or lat < SOUTH_LIMIT:
                continue
            rows.append((lat, lon, int(fields[14] or 0)))
    rows.sort(key=lambda row: -row[2])
    kept, grid = [], {}
    for lat, lon, people in rows:
        key = (math.floor(lat * 100), math.floor(lon * 100))
        squeeze = math.cos(math.radians(lat))
        near = any(
            math.hypot((lon - other_lon) * squeeze, lat - other_lat) * METRES_PER_DEGREE < SAME_PLACE_M
            for dy in (-1, 0, 1)
            for dx in (-1, 0, 1)
            for other_lat, other_lon in grid.get((key[0] + dy, key[1] + dx), ())
        )
        if not near:
            grid.setdefault(key, []).append((lat, lon))
            kept.append((lat, lon, people))
    return kept


def corner_of(name):
    corner = pathlib.Path(name).stem.split("_")[-2]
    lat = int(corner[1:3]) * (1 if corner[0] == "N" else -1)
    lon = int(corner[4:7]) * (1 if corner[3] == "E" else -1)
    return lat, lon


def built_path(lat, lon):
    return BUILT / f"{lat}_{lon}.npz"


def cache_built(name):
    """One tile's built-up pixels, read from its overview once and kept as packed bits."""
    path = built_path(*corner_of(name))
    if not path.exists():
        picture = forest.overview_of(f"{forest.BUCKET}/{name}")
        part = path.with_name(path.stem + ".part.npz")
        np.savez_compressed(part, bits=np.packbits(picture == BUILT_UP))
        part.rename(path)
    return name


def built(lat, lon):
    path = built_path(lat, lon)
    if not path.exists():
        return None
    with np.load(path) as saved:
        bits = saved["bits"]
    return np.unpackbits(bits)[: OVERVIEW * OVERVIEW].reshape(OVERVIEW, OVERVIEW).astype(bool)


def mosaic(lat0, lon0):
    """The built-up pixels of the tile whose south-west corner is lat0, lon0 and MARGIN degrees round
    it from its neighbours: rows north to south, columns west to east."""
    pad = round(MARGIN * PER_DEGREE)
    size = OVERVIEW + 2 * pad
    out = np.zeros((size, size), bool)
    for step_lat in (-3, 0, 3):
        for step_lon in (-3, 0, 3):
            tile = built(lat0 + step_lat, (lon0 + step_lon + 180) % 360 - 180)
            if tile is None:
                continue
            top, left = pad - step_lat * PER_DEGREE, pad + step_lon * PER_DEGREE
            r0, r1 = max(top, 0), min(top + OVERVIEW, size)
            c0, c1 = max(left, 0), min(left + OVERVIEW, size)
            if r0 < r1 and c0 < c1:
                out[r0:r1, c0:c1] = tile[r0 - top:r1 - top, c0 - left:c1 - left]
    return out


def ground_of(mask):
    """Blots of built-up ground: gaps of up to four pixels closed and holes filled, labelled."""
    ground = ndimage.binary_closing(mask, EIGHT_WAYS, iterations=CLOSING) | mask
    background, count = ndimage.label(~ground)
    edge = np.zeros(count + 1, bool)
    edge[np.concatenate([background[0], background[-1], background[:, 0], background[:, -1]])] = True
    edge[0] = True
    ground |= ~edge[background]
    return ndimage.label(ground, EIGHT_WAYS)


def measure_tile(job):
    """Each place of one tile as (lat, lon, area m², stretch, turn degrees, measured), its neighbours
    within the margin taking their share of any blot they stand in."""
    (lat0, lon0), towns = job
    pad = round(MARGIN * PER_DEGREE)
    mask = mosaic(lat0, lon0)
    guesses = [
        (lat, lon, math.pi * expected_radius(people) ** 2, 1.0, 0.0, False)
        for lat, lon, people, central in towns
        if central
    ]
    if not mask.any():
        return guesses
    rows = np.flatnonzero(mask.any(1))
    columns = np.flatnonzero(mask.any(0))
    top, bottom = max(rows[0] - 8, 0), min(rows[-1] + 9, mask.shape[0])
    left, right = max(columns[0] - 8, 0), min(columns[-1] + 9, mask.shape[1])
    labels, _ = ground_of(mask[top:bottom, left:right])
    boxes = ndimage.find_objects(labels)

    seeds = []
    for lat, lon, people, central in towns:
        row = (lat0 + 3 + MARGIN - lat) * PER_DEGREE - top
        column = (((lon - lon0 + 180) % 360) - 180 + MARGIN) * PER_DEGREE - left
        across = PIXEL_M * math.cos(math.radians(lat))
        reach = max(SEED_LEAST_M, expected_radius(people))
        r, c = int(row), int(column)
        seed = 0
        if 0 <= r < labels.shape[0] and 0 <= c < labels.shape[1]:
            seed = labels[r, c]
        if not seed:
            dr, dc = math.ceil(reach / PIXEL_M), math.ceil(reach / across)
            r0, c0 = max(r - dr, 0), max(c - dc, 0)
            window = labels[r0:max(r + dr + 1, 0), c0:max(c + dc + 1, 0)]
            near_r, near_c = np.nonzero(window)
            if len(near_r):
                metres = np.hypot((near_r + r0 + 0.5 - row) * PIXEL_M, (near_c + c0 + 0.5 - column) * across)
                nearest = np.argmin(metres)
                if metres[nearest] <= reach:
                    seed = window[near_r[nearest], near_c[nearest]]
        seeds.append((seed, row, column, expected_radius(people), central))

    by_seed = {}
    for index, (seed, *_) in enumerate(seeds):
        if seed:
            by_seed.setdefault(seed, []).append(index)
    measured = {}
    for seed, members in by_seed.items():
        if not any(seeds[m][4] for m in members):
            continue
        box = boxes[seed - 1]
        pr, pc = np.nonzero(labels[box] == seed)
        pr = pr + box[0].start
        pc = pc + box[1].start
        middle_lat = lat0 + 3 + MARGIN - (top + pr.mean()) / PER_DEGREE
        across = PIXEL_M * math.cos(math.radians(middle_lat))
        if len(members) == 1:
            owner = np.zeros(len(pr), np.int32)
        else:
            mr = np.array([seeds[m][1] for m in members])
            mc = np.array([seeds[m][2] for m in members])
            weight = np.array([seeds[m][3] for m in members])
            owner = np.empty(len(pr), np.int32)
            step = max(1, VORONOI_CHUNK // len(members))
            for start in range(0, len(pr), step):
                r = pr[start:start + step, None] + 0.5
                c = pc[start:start + step, None] + 0.5
                owner[start:start + step] = np.argmin(np.hypot((r - mr) * PIXEL_M, (c - mc) * across) / weight, axis=1)
        for slot, m in enumerate(members):
            if not seeds[m][4]:
                continue
            mine = owner == slot
            count = int(mine.sum())
            if count:
                measured[m] = (pr[mine], pc[mine])

    out = []
    for m, (lat, lon, people, central) in enumerate(towns):
        if not central:
            continue
        expected = math.pi * expected_radius(people) ** 2
        if m not in measured:
            out.append((lat, lon, expected, 1.0, 0.0, False))
            continue
        pr, pc = measured[m]
        mean_r, mean_c = pr.mean() + 0.5, pc.mean() + 0.5
        middle_lat = lat0 + 3 + MARGIN - (top + mean_r) / PER_DEGREE
        middle_lon = (lon0 - MARGIN + (left + mean_c) / PER_DEGREE + 180) % 360 - 180
        across = PIXEL_M * math.cos(math.radians(middle_lat))
        area = len(pr) * PIXEL_M * across
        area = min(max(area, expected * AREA_BOUNDS[0]), expected * AREA_BOUNDS[1])
        stretch, turn = 1.0, 0.0
        if len(pr) >= SHAPE_LEAST_PIXELS:
            east = (pc + 0.5 - mean_c) * across
            south = (pr + 0.5 - mean_r) * PIXEL_M
            xx, yy, xy = (east * east).mean(), (south * south).mean(), (east * south).mean()
            spread = math.hypot((xx - yy) / 2, xy)
            long_, short = (xx + yy) / 2 + spread, max((xx + yy) / 2 - spread, 1e-9)
            stretch = math.sqrt(long_ / short)
            turn = math.degrees(0.5 * math.atan2(2 * xy, xx - yy)) % 180
        out.append((middle_lat, middle_lon, area, stretch, turn, True))
    return out


def hilbert(x, y, order):
    """Where the cell x, y falls along a Hilbert curve over a 2**order grid: cells near each other
    along it are near each other on the ground."""
    distance = 0
    side = 1 << (order - 1)
    while side:
        right, up = int(bool(x & side)), int(bool(y & side))
        distance += side * side * ((3 * right) ^ up)
        if not up:
            if right:
                x, y = side - 1 - x, side - 1 - y
            x, y = y, x
        side >>= 1
    return distance


def varint(value, out):
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def zigzag(value, out):
    varint((value << 1) ^ (value >> 63), out)


def encode(towns):
    rows = set()
    for lat, lon, area, stretch, turn, _ in towns:
        radius = max(1, round(math.sqrt(area / math.pi) / RADIUS_UNIT))
        steps = min(MOST_STRETCH, round(STRETCH_STEPS * math.log2(stretch)))
        drawn_out = steps >= LEAST_STRETCH and radius >= SHAPE_LEAST_RADIUS
        shape = (steps << 4 | round(turn / (180 / TURNS)) % TURNS) if drawn_out else None
        rows.add((round(lon * PLACES), round(lat * PLACES), radius, shape))
    span = 360 * PLACES
    rows = sorted(rows, key=lambda row: hilbert(row[0] + span // 2, row[1] + span // 4, span.bit_length()))
    body = bytearray()
    previous_lon = previous_lat = 0
    for lon, lat, radius, shape in rows:
        zigzag(lon - previous_lon, body)
        zigzag(lat - previous_lat, body)
        varint(radius << 1 | (shape is not None), body)
        if shape is not None:
            body.append(shape)
        previous_lon, previous_lat = lon, lat
    out = bytearray(b"PWNTOWN2")
    varint(len(rows), out)
    return bytes(out) + zlib.compress(bytes(body), 9), len(rows), sum(row[3] is not None for row in rows)


def main():
    towns = places()
    print(f"{len(towns):,} places")
    BUILT.mkdir(parents=True, exist_ok=True)
    forest.OVERVIEW = OVERVIEW
    names = forest.tile_names()
    print(f"{len(names)} WorldCover tiles")
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        for done, _ in enumerate(pool.map(cache_built, names), 1):
            if done % 200 == 0:
                print(f"  {done} tiles' built-up ground read", flush=True)

    by_tile = {}
    for lat, lon, people in towns:
        by_tile.setdefault((math.floor(lat / 3) * 3, math.floor(lon / 3) * 3), []).append((lat, lon, people))
    jobs = []
    for (lat0, lon0), own in by_tile.items():
        near = [(lat, lon, people, False)
                for step_lat in (-3, 0, 3) for step_lon in (-3, 0, 3) if step_lat or step_lon
                for lat, lon, people in by_tile.get((lat0 + step_lat, (lon0 + step_lon + 180) % 360 - 180), ())
                if lat0 - MARGIN <= lat <= lat0 + 3 + MARGIN
                and -MARGIN <= (lon - lon0 + 180) % 360 - 180 <= 3 + MARGIN]
        jobs.append(((lat0, lon0), [(lat, lon, people, True) for lat, lon, people in own] + near))
    jobs.sort(key=lambda job: -len(job[1]))
    measured = []
    with concurrent.futures.ProcessPoolExecutor(4) as pool:
        for done, result in enumerate(pool.map(measure_tile, jobs), 1):
            measured += result
            if done % 100 == 0:
                print(f"  {done} of {len(jobs)} tiles measured", flush=True)
    data, count, drawn_out = encode(measured)
    OUT.write_bytes(data)
    print(f"{sum(town[5] for town in measured):,} of {len(measured):,} places measured on WorldCover")
    print(f"{OUT.relative_to(ROOT)}: {count:,} towns, {drawn_out:,} drawn out, {len(data) / 1e3:.0f} kB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
