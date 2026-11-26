#!/usr/bin/env python3
"""Build app/src/main/assets/world.map from Natural Earth (public domain, 1:10m).

What the walk map draws under a route, and nothing it does not: coasts and islands, lakes, rivers
and the streams Natural Earth adds for Europe and North America, glaciers and salt flats. No roads,
no country or region borders, no labels and no place names: the map is drawn in dots, at the zooms
a walk is looked at, and a border or a road there was a line nobody reads among the dots. Greenland
and Antarctica are left out: nobody walks there with this app, and their ice was a megabyte and a
half of the map and its relief. The ground's height is tools/world-relief.py's, the forests
tools/world-forest.py's and the towns tools/world-towns.py's.

Run it only when the map should change; the assets it writes are committed. Every file it fetches
is held to its SHA-256 in tools/world-inputs.sha256 (tools/world_inputs.py).

    python3 tools/world-map.py

Format of world.map, all integers LEB128 varints (zigzag where signed) except the offset table,
which is fixed width so it can be patched after the bodies are sized:

    "PWNMAP3"
    varint lodCount
    per LOD: varint unitsPerDegree, uint32le bodyOffset, uint32le bodyLength
    per LOD body, as one zlib stream: varint layerCount
                  per layer: varint lineCount
                             per line: varint pointCount
                                       pointCount * (zigzag varint dLon, zigzag varint dLat),
                                       the first pair absolute, the rest deltas

The LODs are ordered coarse to fine and each body stands alone, so the renderer reads the one
level the zoom asks for and never pays for the rest. Deflate takes a fifth off the varints; the APK
stores the file as it is, so the reader seeks straight to a body instead of inflating everything
before it. Layers are the pens: the reader assigns a colour and a width per layer index, so the
order in LAYER_NAMES is part of the format.
"""

import json
import math
import pathlib
import struct
import sys
import urllib.request
import zlib

import world_inputs

BASE = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson"
LAYER_NAMES = ["land", "lake", "river", "glacier", "playa", "stream"]
RIVER, STREAM = LAYER_NAMES.index("river"), LAYER_NAMES.index("stream")
# Layers the reader fills rather than strokes. Their geometry is closed rings, so it is cut to a
# grid instead of into strips: a filled shape has to stay a shape, and one undivided Eurasia would
# be eighty thousand points redrawn every frame of a walk down a street.
FILL_LAYERS = {0, 1, 3, 4}
GRID_DEGREES = 5.0
# (file, layer index, splitter). The splitter, when given, decides the layer per feature.
SOURCES = [
    ("ne_10m_land", 0, None),
    ("ne_10m_minor_islands", 0, None),
    ("ne_10m_lakes", 1, None),
    ("ne_10m_lakes_europe", 1, None),
    ("ne_10m_lakes_north_america", 1, None),
    ("ne_10m_rivers_lake_centerlines", RIVER, "river"),
    ("ne_10m_rivers_europe", STREAM, "river"),
    ("ne_10m_rivers_north_america", STREAM, "river"),
    ("ne_10m_glaciated_areas", 3, None),
    ("ne_10m_playas", 4, None),
]
# Natural Earth ranks its rivers from 0, the Amazon and the Nile, to 12, a stream; up to this rank a
# river is drawn from as far out as a continent, and below it only once a region fills the screen.
MAJOR_RIVER_RANK = 6
# How far out each layer is drawn, in degrees per pixel: the `upTo` of its pens in DAY_MAP and
# NIGHT_MAP, TrackScreen.kt. The renderer reads a level for a zoom no finer than 2/unitsPerDegree,
# so a level coarser than this never draws the layer and does not carry it. Change both together.
DRAWN_UP_TO = {"river": 0.05, "stream": 0.006}
# Left out of the map: the countries Natural Earth codes so, and everything south of the latitude.
LEFT_OUT = ("GRL", "ATA")
SOUTH_LIMIT = -60.0
COUNTRIES = "ne_10m_admin_0_countries"
# An islet this close to a left-out country's outline is left out with it.
ISLET_KM = 15.0


def river_layer(properties, layer):
    """A great river keeps its pen; a lesser one falls to the streams'. A line down the middle of a
    lake is dropped: the lake's own fill already says where the water is."""
    if "Centerline" in (properties.get("featurecla") or ""):
        return None
    rank = properties.get("scalerank")
    if layer == RIVER and (rank is None or rank > MAJOR_RIVER_RANK):
        return STREAM
    return layer
# Coarse to fine, in degrees. 0.6 draws the globe; 0.0002 (~22 m) is finer than Natural Earth's
# own 1:10m accuracy, so the finest level is the source data with only its noise removed. The
# ladder, with world.relief, world.forest and world.towns beside it, lands just under the 30 MB the
# map is allowed to weigh.
TOLERANCES = [0.6, 0.14, 0.032, 0.007, 0.0002]
# Long lines are cut into pieces sharing an endpoint. The renderer skips a piece whose box misses
# the screen, and one undivided Siberian coastline would have a box covering half the northern
# hemisphere — always on screen, never mostly visible, every point walked for nothing.
CHUNK = 128

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "build" / "world-map-cache"
OUT = ROOT / "app" / "src" / "main" / "assets" / "world.map"


def download(name):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / f"{name}.geojson"
    if not path.exists():
        print(f"fetching {name}")
        urllib.request.urlretrieve(f"{BASE}/{name}.geojson", path)
    return json.loads(world_inputs.checked(path).read_text())


def rings(geometry):
    """Every geometry Natural Earth uses, flattened to plain coordinate lists."""
    kind, coordinates = geometry["type"], geometry["coordinates"]
    if kind == "LineString":
        return [coordinates]
    if kind in ("MultiLineString", "Polygon"):
        return list(coordinates)
    if kind == "MultiPolygon":
        return [ring for polygon in coordinates for ring in polygon]
    raise ValueError(kind)


def simplify(points, tolerance):
    """Douglas-Peucker, iterative because a coastline is long and the recursion is deep."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        ax, ay = points[first]
        bx, by = points[last]
        dx, dy = bx - ax, by - ay
        span = math.hypot(dx, dy)
        farthest, worst = -1, tolerance
        for index in range(first + 1, last):
            px, py = points[index]
            if span == 0.0:
                distance = math.hypot(px - ax, py - ay)
            else:
                distance = abs((px - ax) * dy - (py - ay) * dx) / span
            if distance > worst:
                farthest, worst = index, distance
        if farthest > 0:
            keep[farthest] = True
            stack.append((first, farthest))
            stack.append((farthest, last))
    return [point for point, wanted in zip(points, keep) if wanted]


def quantise(points, units):
    """To the LOD's own grid, dropping the points that land on the one before them."""
    out = []
    for lon, lat in points:
        pair = (round(lon * units), round(lat * units))
        if not out or pair != out[-1]:
            out.append(pair)
    return out


def chunked(line):
    return [line[start:start + CHUNK + 1] for start in range(0, len(line) - 1, CHUNK)]


def crossing(first, second, value, axis):
    other = 1 - axis
    share = (value - first[axis]) / (second[axis] - first[axis])
    crossed = first[other] + (second[other] - first[other]) * share
    return (value, crossed) if axis == 0 else (crossed, value)


def half_plane(ring, value, keep_above, axis):
    """Sutherland-Hodgman against one edge of the cell. A rectangle is four of these."""
    out = []
    if not ring:
        return out
    previous = ring[-1]
    for current in ring:
        inside = current[axis] >= value if keep_above else current[axis] <= value
        was = previous[axis] >= value if keep_above else previous[axis] <= value
        if inside != was:
            out.append(crossing(previous, current, value, axis))
        if inside:
            out.append(current)
        previous = current
    return out


def tiled(ring):
    """One closed ring cut into the grid cells it covers, each piece still a closed ring."""
    if len(ring) < 3:
        return []
    pieces = []
    lats = [point[1] for point in ring]
    row = math.floor(min(lats) / GRID_DEGREES) * GRID_DEGREES
    while row < max(lats):
        band = half_plane(half_plane(ring, row, True, 1), row + GRID_DEGREES, False, 1)
        row += GRID_DEGREES
        if len(band) < 3:
            continue
        lons = [point[0] for point in band]
        column = math.floor(min(lons) / GRID_DEGREES) * GRID_DEGREES
        while column < max(lons):
            cell = half_plane(half_plane(band, column, True, 0), column + GRID_DEGREES, False, 0)
            column += GRID_DEGREES
            if len(cell) >= 3:
                pieces.append(cell)
    return pieces


def varint(value, out):
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def zigzag(value, out):
    varint((value << 1) ^ (value >> 63), out)


def left_out_outlines():
    """The outer rings of the countries the map leaves out, each with its box for a quick miss."""
    outlines = []
    for feature in download(COUNTRIES)["features"]:
        if feature["properties"].get("ADM0_A3") not in LEFT_OUT:
            continue
        geometry = feature["geometry"]
        polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
        for polygon in polygons:
            ring = [(point[0], point[1]) for point in polygon[0]]
            lons = [point[0] for point in ring]
            lats = [point[1] for point in ring]
            outlines.append((min(lons), min(lats), max(lons), max(lats), ring))
    return outlines


def inside(lon, lat, ring):
    """Even-odd ray casting: whether the point is inside the closed ring."""
    hit = False
    previous_lon, previous_lat = ring[-1]
    for current_lon, current_lat in ring:
        if (current_lat > lat) != (previous_lat > lat):
            crossing_lon = current_lon + (lat - current_lat) * (previous_lon - current_lon) / (previous_lat - current_lat)
            if lon < crossing_lon:
                hit = not hit
        previous_lon, previous_lat = current_lon, current_lat
    return hit


def left_out(line, outlines):
    """Whether a ring or a line belongs to what the map leaves out, judged by the middle of its points:
    every piece of Greenland and Antarctica lies wholly inside them, and nothing else crosses into
    them. Islets just off Greenland's coast, which its outline leaves out, go with it: anything whose
    middle is within ISLET_KM of the outline's points. Iceland and Ellesmere lie further off than that."""
    lon = sum(point[0] for point in line) / len(line)
    lat = sum(point[1] for point in line) / len(line)
    if lat < SOUTH_LIMIT:
        return True
    reach = ISLET_KM / 111.32
    for west, south, east, north, ring in outlines:
        if not (west - reach * 4 <= lon <= east + reach * 4 and south - reach <= lat <= north + reach):
            continue
        if west <= lon <= east and south <= lat <= north and inside(lon, lat, ring):
            return True
        squeeze = math.cos(math.radians(lat))
        if any(math.hypot((x - lon) * squeeze, y - lat) < reach for x, y in ring):
            return True
    return False


def collect(outlines):
    layers = [[] for _ in LAYER_NAMES]
    for name, layer, splitter in SOURCES:
        before = [sum(len(line) for line in lines) for lines in layers]
        for feature in download(name)["features"]:
            if feature["geometry"] is None:
                continue
            target = river_layer(feature["properties"], layer) if splitter == "river" else layer
            if target is None:
                continue
            for ring in rings(feature["geometry"]):
                line = [(point[0], point[1]) for point in ring]
                if len(line) >= 2 and not left_out(line, outlines):
                    layers[target].append(line)
        for index, lines in enumerate(layers):
            added = sum(len(line) for line in lines) - before[index]
            if added:
                print(f"{name:45s} {added:>9,} pts -> {LAYER_NAMES[index]}")
    return layers


def body_of(layers, units):
    out = bytearray()
    varint(len(layers), out)
    kept = 0
    for layer, lines in enumerate(layers):
        if 2.0 / units > DRAWN_UP_TO.get(LAYER_NAMES[layer], math.inf):
            varint(0, out)
            continue
        # Fills are cut to the grid and left closed; strokes are cut into fixed-length pieces.
        # Cutting happens in degrees and quantising after it, so a cell edge lands on the grid
        # from both sides and neighbouring pieces cannot leave a seam.
        cut = tiled if layer in FILL_LAYERS else chunked
        least = 3 if layer in FILL_LAYERS else 2
        encoded = [
            quantised
            for line in lines
            for piece in cut(line)
            for quantised in [quantise(piece, units)]
            if len(quantised) >= least
        ]
        varint(len(encoded), out)
        for line in encoded:
            varint(len(line), out)
            previous_lon = previous_lat = 0
            for lon, lat in line:
                zigzag(lon - previous_lon, out)
                zigzag(lat - previous_lat, out)
                previous_lon, previous_lat = lon, lat
            kept += len(line)
    return out, kept


def main():
    outlines = left_out_outlines()
    source = collect(outlines)
    print(f"source: {sum(len(l) for layer in source for l in layer):,} points")

    # Each LOD simplifies the one before it: the fine pass is the expensive one, and a coarser
    # tolerance has nothing to add that the finer result does not already contain.
    built = []
    current = source
    for tolerance in sorted(TOLERANCES):
        current = [[simplify(line, tolerance) for line in layer] for layer in current]
        built.append((tolerance, current))
    built.reverse()

    head = bytearray(b"PWNMAP3")
    varint(len(built), head)
    bodies, slots = [], []
    for tolerance, layers in built:
        # Half the tolerance: fine enough that quantisation never shows next to simplification.
        units = max(1, round(2.0 / tolerance))
        body, kept = body_of(layers, units)
        body = zlib.compress(bytes(body), 9)
        print(f"tolerance {tolerance:<8} {kept:>9,} pts  {len(body) / 1e6:6.2f} MB  1/{units} degree grid")
        varint(units, head)
        slots.append(len(head))
        head += bytes(8)
        bodies.append(body)

    out = bytearray(head)
    for body, slot in zip(bodies, slots):
        struct.pack_into("<II", out, slot, len(out), len(body))
        out += body

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(out)
    print(f"{OUT.relative_to(ROOT)}: {len(out) / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
