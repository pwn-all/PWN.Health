#!/usr/bin/env python3
"""Turn a duotone SVG set into `res/drawable/ic_art_*.xml`.

A set is a folder of `ic_duo_<name>.svg` files: a 48x48 viewBox, `<path>` elements only, no transforms and
no groups, painted in exactly the three colours of the palette below. Each becomes a vector whose
colours are the three `@color/art_duo_*` resources, so one file is read under both themes.

    tools/duo-icons.py <svg-dir> [name ...]     # write, restricted to the names given
    tools/duo-icons.py --check <svg-dir>        # prove each written vector still renders as its SVG

`--check` is the test: it rebuilds an SVG out of the generated XML, renders both with qlmanage and
fails unless they are pixel-identical. macOS only, and it wants ImageMagick on PATH.
"""
import math
import re
import subprocess
import sys
import tempfile
import xml.sax.saxutils as sx
from pathlib import Path

DRAWABLE = Path(__file__).resolve().parent.parent / 'app/src/main/res/drawable'
# The set's palette. A colour outside it means the set changed and this script should not guess.
TOKEN = {'#E5E7F8': 'art_duo_tile', '#999CD1': 'art_duo_mid', '#4E5298': 'art_duo_ink'}
# Every icon in the set opens with this rounded square, its tile, in the palest of the three
# tones. It is dropped: a drawing that carries its own ground cannot sit on a card, a coloured
# row or a dark page without announcing itself as a sticker on top of them. The tone stays in
# the palette because ten of the drawings also use it inside the figure.
TILE = 'M12 0h24a12 12 0 0 1 12 12v24a12 12 0 0 1-12 12H12A12 12 0 0 1 0 36V12A12 12 0 0 1 12 0Z'
# Lint calls a pathData longer than this bad for performance, and the gate turns that into an
# error. A few figures carry a hundred cubics where a dozen would do; they are suppressed per
# path rather than by disabling the check, because a simplifier that fits fewer curves would also
# redraw the artwork. If one of these ever shows up in a scrolling list rather than as a single
# 48dp glyph, fit the curves instead of suppressing.
LONG_PATH = 800
PATH = re.compile(r'<path\b([^>]*?)/?>')
TREE = re.compile(r'<g\b([^>]*)>|</g>|<path\b([^>]*?)/?>')
VD_TREE = re.compile(r'<group\b(.*?)>|</group>|<path\b(.*?)/>', re.S)
SVG_ATTR = re.compile(r'([a-z-]+)="([^"]*)"')
VD_ATTR = re.compile(r'android:([A-Za-z]+)="([^"]*)"')


def num(x: float) -> str:
    # 9 significant digits, not 4: a group scales a 512-unit drawing into a 48 viewBox, so a
    # rounded scale moves the figure by a visible pixel. --check measures exactly this.
    return f'{x:.9g}'


def mul(m, n):
    """m . n, both (a b c d e f) as SVG writes them: (x,y) -> (ax+cy+e, bx+dy+f)."""
    a, b, c, d, e, f = m
    A, B, C, D, E, F = n
    return (a*A + c*B, b*A + d*B, a*C + c*D, b*C + d*D, a*E + c*F + e, b*E + d*F + f)


def similarity(spec: str):
    """An SVG transform list as (scale, degrees, tx, ty) — what a `<group>` can carry.

    A VectorDrawable group is one uniform scale, one rotation and one translation, so a skew or a
    mirror is refused rather than approximated: it would draw a plausible wrong picture."""
    m = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    for fn, args in re.findall(r'([a-z]+)\s*\(([^)]*)\)', spec):
        v = [float(x) for x in re.split(r'[,\s]+', args.strip()) if x]
        if fn == 'translate':
            n = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif fn == 'scale':
            n = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif fn == 'rotate':
            r = math.radians(v[0])
            n = (math.cos(r), math.sin(r), -math.sin(r), math.cos(r), 0, 0)
            if len(v) > 1:  # rotate(deg cx cy) turns about a point, not the origin
                n = mul((1, 0, 0, 1, v[1], v[2]), mul(n, (1, 0, 0, 1, -v[1], -v[2])))
        elif fn == 'matrix':
            n = tuple(v)
        else:
            raise AssertionError(f'{fn}() is not converted')
        m = mul(m, n)
    a, b, c, d, e, f = m
    s = math.hypot(a, b)
    assert s > 0 and abs(math.hypot(c, d) - s) < 1e-4 * s, f'not a uniform scale: {spec}'
    assert abs(a*c + b*d) < 1e-4 * s * s, f'skewed: {spec}'
    assert a*d - b*c > 0, f'mirrored: {spec}'
    return s, math.degrees(math.atan2(b, a)), e, f


def to_vector(svg: str) -> str:
    """The generated XML for one SVG. Raises if the file uses anything the set never has."""
    svg = re.sub(r'<metadata>.*?</metadata>', '', svg, flags=re.S)
    assert 'opacity=' not in svg, 'opacity= is not converted'
    box = re.search(r'viewBox="0 0 (\d+) (\d+)"', svg)
    assert box, 'viewBox "0 0 W H" expected'
    w, h = box.groups()
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:tools="http://schemas.android.com/tools"',
           f'    android:width="{w}dp"', f'    android:height="{h}dp"',
           f'    android:viewportWidth="{w}"', f'    android:viewportHeight="{h}">']
    paths, depth = 0, 0
    for m in TREE.finditer(svg):
        g, body = m.group(1), m.group(2)
        pad = '    ' * (depth + 1)
        if m.group(0).startswith('</'):
            depth -= 1
            out.append('    ' * (depth + 1) + '</group>')
            continue
        if m.group(0).startswith('<g'):
            a = dict(SVG_ATTR.findall(g))
            assert set(a) <= {'transform'}, f'a group that paints: {g}'
            s, deg, tx, ty = similarity(a['transform'])
            out += [pad + '<group',
                    pad + f'    android:scaleX="{num(s)}"',
                    pad + f'    android:scaleY="{num(s)}"',
                    pad + f'    android:rotation="{num(deg)}"',
                    pad + f'    android:translateX="{num(tx)}"',
                    pad + f'    android:translateY="{num(ty)}">']
            depth += 1
            continue
        a = dict(SVG_ATTR.findall(body))
        if a.get('d') == TILE:
            continue
        fill, stroke = a.get('fill', 'none'), a.get('stroke', 'none')
        assert fill != 'none' or stroke != 'none', f'a path that paints nothing: {body}'
        lines = [pad + f'    android:pathData="{sx.quoteattr(a["d"])[1:-1]}"']
        if len(a['d']) > LONG_PATH:
            lines.append(pad + '    tools:ignore="VectorPath"')
        if fill != 'none':
            lines.append(pad + f'    android:fillColor="@color/{TOKEN[fill]}"')
            if a.get('fill-rule') == 'evenodd':
                lines.append(pad + '    android:fillType="evenOdd"')
        if stroke != 'none':
            lines.append(pad + f'    android:strokeColor="@color/{TOKEN[stroke]}"')
            lines.append(pad + f'    android:strokeWidth="{a["stroke-width"]}"')
            if 'stroke-linecap' in a:
                lines.append(pad + f'    android:strokeLineCap="{a["stroke-linecap"]}"')
            if 'stroke-linejoin' in a:
                lines.append(pad + f'    android:strokeLineJoin="{a["stroke-linejoin"]}"')
        out += [pad + '<path', '\n'.join(lines) + ' />']
        paths += 1
    assert depth == 0, 'unbalanced <g>'
    assert paths, 'no paths'
    return '\n'.join(out + ['</vector>']) + '\n'


def to_svg(xml: str) -> str:
    """The reverse, for --check only: the generated XML painted back in the daylight palette."""
    day = {f'@color/{t}': hexa for hexa, t in TOKEN.items()}
    parts = []
    for m in VD_TREE.finditer(xml):
        g, body = m.group(1), m.group(2)
        if m.group(0).startswith('</'):
            parts.append('</g>')
            continue
        if m.group(0).startswith('<group'):
            a = dict(VD_ATTR.findall(g))
            # translate . rotate . scale is the order a group's matrix is built in
            parts.append(f'<g transform="translate({a["translateX"]} {a["translateY"]}) '
                         f'rotate({a["rotation"]}) scale({a["scaleX"]} {a["scaleY"]})">')
            continue
        a = dict(VD_ATTR.findall(body))
        bits = [f'd="{a["pathData"]}"', f'fill="{day.get(a.get("fillColor"), "none")}"']
        if a.get('fillType') == 'evenOdd':
            bits.append('fill-rule="evenodd"')
        if 'strokeColor' in a:
            bits += [f'stroke="{day[a["strokeColor"]]}"', f'stroke-width="{a["strokeWidth"]}"']
            for vd, css in (('strokeLineCap', 'stroke-linecap'), ('strokeLineJoin', 'stroke-linejoin')):
                if vd in a:
                    bits.append(f'{css}="{a[vd]}"')
        parts.append('<path ' + ' '.join(bits) + '/>')
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 48 48" '
            'fill="none">' + ''.join(parts) + '</svg>')


def render(svg: Path) -> Path:
    subprocess.run(['qlmanage', '-t', '-s', '384', '-o', str(svg.parent), str(svg)],
                   check=True, capture_output=True)
    return svg.parent / (svg.name + '.png')


def pairs(src: Path, names):
    for svg in sorted(src.glob('ic_duo_*.svg')):
        name = svg.stem[len('ic_duo_'):]
        if names and name not in names:
            continue
        yield svg, DRAWABLE / f'ic_art_{name}.xml'


def main(argv):
    check = argv[0] == '--check' if argv else False
    src, names = Path(argv[1 if check else 0]), set(argv[2 if check else 1:])
    if not check:
        n = 0
        for svg, xml in pairs(src, names):
            xml.write_text(to_vector(svg.read_text()))
            n += 1
        print(f'{n} drawables written to {DRAWABLE}')
        return 0

    bad, seen = [], 0
    with tempfile.TemporaryDirectory() as tmp:
        for svg, xml in pairs(src, names):
            if not xml.exists():
                continue
            a, b = Path(tmp) / 'a', Path(tmp) / 'b'
            for d in (a, b):
                d.mkdir(exist_ok=True)
            source = re.sub(r'<metadata>.*?</metadata>', '', svg.read_text(), flags=re.S)
            source = re.sub(r'<path[^>]*d="' + re.escape(TILE) + r'"[^>]*/>', '', source)
            (a / svg.name).write_text(source)
            (b / svg.name).write_text(to_svg(xml.read_text()))
            diff = subprocess.run(['magick', 'compare', '-metric', 'AE', str(render(a / svg.name)),
                                   str(render(b / svg.name)), 'null:'],
                                  capture_output=True, text=True).stderr.split()[0]
            seen += 1
            if float(diff) != 0:
                bad.append(f'{xml.name}: {diff} px differ from {svg.name}')
            for d in (a, b):
                for f in d.iterdir():
                    f.unlink()
    print(f'{seen} drawables checked')
    for line in bad:
        print('  ' + line)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
