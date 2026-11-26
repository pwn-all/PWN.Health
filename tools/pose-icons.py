#!/usr/bin/env python3
"""Cut the picker's thumbnail for an exercise out of its how-to panel.

A how-to panel draws the movement as two poses side by side with an arrow between them. The picker
wants one small square per exercise, so this takes the left pose, lifts it off its background and
writes `res/drawable-nodpi/ic_pose_<name>.webp` at 176px — the size 44dp asks for at xxxhdpi, and
a thirtieth of the pixels the panel itself would make a list decode per row. Beside it goes
`ic_pose_<name>_hero.webp` at 528px, the same pose for the 200dp drawing across the top of a
template's page and of a session, where the 176px one came out stretched three times.

    tools/pose-icons.py            # cut every panel
    tools/pose-icons.py squats     # or just these
    tools/pose-icons.py --panel yoga.png yoga   # a new picture becomes ic_howto_yoga, then cut

Every file is written at the lowest quality that keeps its DSSIM against the picture it was made from
within DSSIM_TARGET, so a busy anatomical picture keeps its detail and a plain one is not stored at the
busy one's size. A new panel is AVIF: it is opaque and the largest picture an exercise has, and at the
same DSSIM AVIF came out about 37% under WebP (basketball, 31 KB against 49 KB). Android decodes 8-bit
AVIF from 12, under this app's minSdk of 33. The poses stay WebP — they are small, and their edge is
alpha, which WebP keeps losslessly. Older panels are lossy WebP with no source left to re-encode from.

The background comes off by flood-filling inward from the four corners rather than by matching its
colour everywhere: the figures are grey on grey and a global match eats holes in them (measured —
it perforates half the set). A pocket the flood cannot reach, such as the inside of a machine's
frame, keeps its pale fill and reads as part of the frame at this size.
"""
import re
import subprocess
import sys
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / 'app/src/main/res/drawable-nodpi'
PANEL_WIDTH = 1024  # about a phone's card at full width, as every panel in the set is
# The lowest quality that holds this structural dissimilarity to the source. Measured on the panels:
# 0.009 is where the fine muscle lines still read at twice the size, and q80's 0.011 smooths them.
# Never under Q_FLOOR, where a pale page's gradient starts to band whatever the metric says.
DSSIM_TARGET = 0.009
# The quality each format is searched over. AVIF softens rather than bands as it goes down, and a
# whole-picture DSSIM forgives that on a mostly empty page: the reverse fly would pass at q45 with
# its muscles washed flat. Under 68 the relief goes at twice the size; at 70 it matches WebP's q85.
QUALITY = {'.webp': (75, 95), '.avif': (68, 90)}
SIZE, FIT = 176, 152
HERO_SIZE, HERO_FIT = 528, 456  # three times over: the 200dp hero at 2.625 px/dp
# Fractions of the panel's width to cut. Half is the left pose in every panel but one: burpees
# draws four phases, so its left half is a stand and a crouch rather than one picture.
WINDOW = {'burpees': (0.17, 0.40)}
DEFAULT_WINDOW = (0.0, 0.5)
# Panels that stack their two poses rather than set them side by side: the top part, across the width —
# a little short of half where the lower pose's arrow reaches up past the middle.
STACKED = {'bicycle_crunch': 0.46}
# Workouts of the clock wear interval training's picture (MuscleMap.kt, OTHER_WORKOUTS_ART): their
# panels are shown under "How to?" and are not cut, so no pose of theirs lies in the APK unread.
PANEL_ONLY = {'rowing_machine', 'swimming', 'basketball', 'yoga'}
# How far the flood from the corners may stray from the background's colour, in percent; 8 for the
# rest. The stairs' pale concrete is within 8% of the page, and at 8 would be eaten into speckle.
# (A -fuzz has to come before the floods it is for: at the end of the command it changes nothing.)
FUZZ = {'stairs': 4}
# A fragment is dropped when it is this small a share of the ink and stands clear of the figure by
# this much of the width. Both have to hold: the arrow stub is tiny AND detached, while a barbell
# plate is detached but far from tiny, and a lifted foot is tiny but touching.
STUB_SHARE, STUB_GAP = 0.06, 0.03
# Extra points to flood from, as fractions of the trimmed crop. The corners reach every panel's
# background but one: the jump rope's arc closes a pocket around the legs that no corner is inside,
# and left alone it reads as a white shield behind the figure.
SEEDS = {'jump_rope': ((0.25, 0.80), (0.75, 0.80))}


def cut(panel: Path, out: Path) -> None:
    name = panel.stem[len('ic_howto_'):]
    start, end = (0.0, 1.0) if name in STACKED else WINDOW.get(name, DEFAULT_WINDOW)
    size = subprocess.run(['magick', str(panel), '-format', '%w %h', 'info:'],
                          capture_output=True, text=True, check=True).stdout.split()
    width, height = int(size[0]), int(size[1])
    if name in STACKED:
        height = round(height * STACKED[name])
    x, w = round(width * start), round(width * (end - start))
    bg = subprocess.run(['magick', str(panel), '-format', '%[pixel:p{2,2}]', 'info:'],
                        capture_output=True, text=True, check=True).stdout
    png, window = out.with_suffix('.png'), out.with_suffix('.cut.png')
    # Pixels, not percent: a geometry with one % makes BOTH numbers percentages, so `512x100%`
    # asks for 512% of the width and quietly hands back the whole panel — two poses, scaled down,
    # which is exactly what this script exists not to produce.
    subprocess.run(['magick', str(panel), '-crop', f'{w}x{height}+{x}+0', '+repage', str(window)],
                   check=True)
    got = int(subprocess.run(['magick', str(window), '-format', '%w', 'info:'],
                             capture_output=True, text=True, check=True).stdout)
    assert got == w, f'{panel.name}: asked for {w}px of {width}, got {got} — the crop did not take'
    seeds = []
    for fx, fy in SEEDS.get(panel.stem[len('ic_howto_'):], ()):
        seeds += ['-draw', f'color %[fx:w*{fx}],%[fx:h*{fy}] floodfill']
    subprocess.run([
        'magick', str(window),
        '-bordercolor', bg, '-border', '1', '-fuzz', '8%', '-trim', '+repage',
        '-alpha', 'set', '-fill', 'none', '-fuzz', f'{FUZZ.get(name, 8)}%',
        '-draw', 'color 0,0 floodfill', '-draw', 'color %[fx:w-1],0 floodfill',
        '-draw', 'color 0,%[fx:h-1] floodfill', '-draw', 'color %[fx:w-1],%[fx:h-1] floodfill',
        *seeds, str(window),
    ], check=True)
    keep = figure_width(window)
    trim = ['-crop', f'{keep}x{height}+0+0', '+repage', '-trim', '+repage'] if keep else []
    for size, fit, dest in ((SIZE, FIT, out), (HERO_SIZE, HERO_FIT, out.with_name(f'{out.stem}_hero.webp'))):
        subprocess.run(['magick', str(window), *trim, '-background', 'none', '-gravity', 'center',
                        '-resize', f'{fit}x{fit}', '-extent', f'{size}x{size}', str(png)], check=True)
        encode(png, dest)
    png.unlink()
    window.unlink()


def write(png: Path, dest: Path, q: int) -> None:
    if dest.suffix == '.avif':
        # 8-bit, which every Android from 12 decodes; 10-bit is optional there.
        subprocess.run(['magick', str(png), '-depth', '8', '-quality', str(q), '-define', 'heic:speed=2',
                        str(dest)], check=True)
    else:
        # Alpha whole: a thumbnail is read by the figure's edge.
        subprocess.run(['cwebp', '-quiet', '-q', str(q), '-m', '6', '-sharp_yuv', '-af', '-alpha_q', '100',
                        str(png), '-o', str(dest)], check=True)


def distance(source: Path, encoded: Path) -> float:
    """DSSIM of [encoded] against [source], both laid on the page white so what lies under a transparent
    pixel — which an encoder may drop — does not count; the 0..1 figure `magick compare` gives in brackets."""
    flat = [path.with_suffix('.flat.png') for path in (source, encoded)]
    for path, out in zip((source, encoded), flat):
        # Alpha off after the flatten: an opaque channel left on counts as a fourth, identical one and
        # dilutes the mean by a quarter — measured, it would let a panel through at q45 that fails at q60.
        subprocess.run(['magick', str(path), '-background', 'white', '-flatten', '-alpha', 'off', str(out)],
                       check=True)
    # compare exits 1 when the pictures differ, which they always do here.
    said = subprocess.run(['magick', 'compare', '-metric', 'DSSIM', *map(str, flat), 'null:'],
                          capture_output=True, text=True).stderr
    for out in flat:
        out.unlink()
    return float(re.search(r'\(([\d.e-]+)\)', said).group(1))


def encode(png: Path, dest: Path) -> int:
    """[png] at the lowest quality of [dest]'s format within DSSIM_TARGET of it; returns the quality."""
    trial = dest.with_name(f'{dest.stem}.trial{dest.suffix}')
    low, high = QUALITY[dest.suffix]
    best = high
    while low <= high:
        q = (low + high) // 2
        write(png, trial, q)
        if distance(png, trial) <= DSSIM_TARGET:
            best, high = q, q - 1
        else:
            low = q + 1
    trial.unlink()
    write(png, dest, best)
    return best


def panel(source: Path, name: str) -> None:
    """A picture become ic_howto_<name>.avif — no wider than PANEL_WIDTH, metadata gone — and its pose
    cut from the picture itself rather than from the compressed panel."""
    png = RES / f'ic_howto_{name}.png'
    subprocess.run(['magick', str(source), '-resize', f'{PANEL_WIDTH}x>', '-strip', str(png)], check=True)
    for old in RES.glob(f'ic_howto_{name}.*'):
        if old != png:
            old.unlink()
    q = encode(png, RES / f'ic_howto_{name}.avif')
    print(f'ic_howto_{name}.avif at q{q}')
    if name not in PANEL_ONLY:
        cut(png, RES / f'ic_pose_{name}.webp')
    png.unlink()


def columns(image: Path) -> list[int]:
    """Mean alpha per column, 0..255 — the figure's shadow on the x axis."""
    txt = subprocess.run(['magick', str(image), '-alpha', 'extract', '-scale', 'x1!',
                          '-depth', '8', 'txt:-'], capture_output=True, text=True, check=True).stdout
    out = []
    for line in txt.splitlines()[1:]:
        grey = re.search(r'\((\d+)', line.split(':')[1])
        out.append(int(grey.group(1)))
    return out


def figure_width(image: Path) -> int | None:
    """Where to cut the right edge so a detached stub — the arrow's tail — is left behind.

    The trimmed crop starts at the figure, so only the right side needs deciding. Returns None
    when there is nothing to drop, which is the ordinary answer.
    """
    ink = columns(image)
    total = sum(ink)
    if not total:
        return None
    gap = max(1, round(len(ink) * STUB_GAP))
    run = 0
    for x in range(len(ink) - 1, -1, -1):
        if ink[x]:
            run = 0
            continue
        run += 1
        if run >= gap and sum(ink[x:]) <= total * STUB_SHARE:
            return x
    return None


def main(names):
    panels = sorted(path for path in RES.glob('ic_howto_*') if path.suffix in ('.webp', '.avif'))
    done = 0
    for panel in panels:
        name = panel.stem[len('ic_howto_'):]
        if name in PANEL_ONLY or (names and name not in names):
            continue
        cut(panel, RES / f'ic_pose_{name}.webp')
        done += 1
    assert done, f'nothing cut; {len(panels)} panels are in {RES}'
    print(f'{done} thumbnails and heroes written to {RES}')
    return 0


if __name__ == '__main__':
    if sys.argv[1:2] == ['--panel']:
        panel(Path(sys.argv[2]), sys.argv[3])
        sys.exit(0)
    sys.exit(main(set(sys.argv[1:])))
