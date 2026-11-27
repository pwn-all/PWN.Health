#!/usr/bin/env python3
"""Add the macOS and Windows aapt2 jars of the root build's AGP to verification-metadata.xml.

Usage: aapt2_platforms.py [ROOT] [--offline-sums FILE]
Gradle records the aapt2 of the machine it runs on, Linux on CI; DependencyVerificationTest wants all
three. The hash of each missing jar is the .sha256 Google's Maven publishes beside it (the same
server and TLS Gradle took the Linux one from). The file is edited as text, so Gradle's layout and the
rest of the file stay byte for byte. --offline-sums reads "<jar name> <sha256>" lines instead of
fetching (for tests).
"""
import datetime
import re
import sys
import urllib.request
from pathlib import Path

MAVEN = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2"
PLATFORMS = {"linux": None, "osx": "macOS", "windows": "Windows"}
HEX64 = re.compile(r"[0-9a-f]{64}")


def published_sha256(version, jar, offline):
    if offline is not None:
        value = offline.get(jar, "")
    else:
        url = f"{MAVEN}/{version}/{jar}.sha256"
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "aapt2-platforms"}), timeout=60) as r:
            value = r.read(200).decode("ascii", "replace").split()[0] if r.status == 200 else ""
    if not HEX64.fullmatch(value):
        sys.exit(f"no SHA-256 for {jar}: {value!r}")
    return value


def main(argv):
    offline = None
    if "--offline-sums" in argv:
        i = argv.index("--offline-sums")
        offline = dict(line.split() for line in Path(argv[i + 1]).read_text().splitlines() if line.strip())
        argv = argv[:i] + argv[i + 2:]
    root = Path(argv[0] if argv else ".")
    agp = re.search(r'id\("com\.android\.application"\) version "([^"]+)"', (root / "build.gradle.kts").read_text())
    if not agp:
        sys.exit("No AGP version in the root build script.")
    path = root / "gradle/verification-metadata.xml"
    text = path.read_bytes().decode("utf-8")
    block = re.compile(
        r'(?P<head>( *)<component group="com\.android\.tools\.build" name="aapt2" version="(?P<v>'
        + re.escape(agp.group(1)) + r'-\d+)">\n)(?P<body>.*?)(?P<tail>\2</component>\n)', re.S)
    matches = list(block.finditer(text))
    if len(matches) != 1:
        sys.exit(f"{len(matches)} aapt2 components for AGP {agp.group(1)}: run the build that records the Linux one first")
    m = matches[0]
    version, indent = m.group("v"), m.group(2)
    artifacts = re.findall(r' *<artifact name="[^"]+">\n.*?</artifact>\n', m.group("body"), re.S)
    names = {re.search(r'name="([^"]+)"', a).group(1): a for a in artifacts}
    if f"aapt2-{version}-linux.jar" not in names:
        sys.exit(f"aapt2 {version} has no Linux jar: this is meant to run after the build on Linux recorded it")
    today = datetime.date.today().isoformat()
    added = []
    for platform, os_name in PLATFORMS.items():
        jar = f"aapt2-{version}-{platform}.jar"
        if jar in names or os_name is None:
            continue
        sha = published_sha256(version, jar, offline)
        names[jar] = (
            f'{indent}   <artifact name="{jar}">\n'
            f'{indent}      <sha256 value="{sha}" origin="Taken from Google\'s Maven on {today}, on Linux, '
            f'for a build on {os_name}"/>\n'
            f"{indent}   </artifact>\n")
        added.append(jar)
    if added:
        body = "".join(names[n] for n in sorted(names))  # Gradle keeps artifacts sorted by name
        text = text[:m.start("body")] + body + text[m.end("body"):]
        path.write_bytes(text.encode("utf-8"))
    print(f"aapt2 {version}: added {added or 'nothing'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
