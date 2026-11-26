#!/usr/bin/env python3
"""A regenerated gradle/verification-metadata.xml may only add to the one it replaces.

Usage: verification_additions.py OLD.xml NEW.xml      exit 0 = additions only, 1 = refused

NEW was written by a job that ran the new dependencies' code, so it is read the way Gradle reads it.
Gradle's reader ignores namespaces and nesting and acts on every element by its bare name, so NEW is
refused unless ElementTree and Gradle can only see the same thing: one namespace, no prefixed or
unknown element, the root holding one <configuration> and one <components>, and below <components>
only the shape Gradle writes, each component and artifact once. Then refused: a <configuration>
that differs (trust rules, verify-metadata, keys); a component or an artifact of OLD missing from NEW;
any checksum of an artifact already in OLD that differs, is added or gains an <also-trust>; a new
artifact whose checksums are anything but one well-formed sha256. Origins and reasons are labels,
not trust, and are not compared.
"""
import re
import sys
import xml.etree.ElementTree as ET

NS = "{https://schema.gradle.org/dependency-verification}"
HEX64 = re.compile(r"[0-9a-f]{64}")
SUMS = {"md5", "sha1", "sha256", "sha512"}
# Below <components>, each element and the children Gradle writes under it.
SHAPE = {"components": {"component"}, "component": {"artifact"}, "artifact": SUMS, "also-trust": set(),
         **{kind: {"also-trust"} for kind in SUMS}}


def refuse(path, why):
    sys.exit(f"REFUSED {path}: {why}")


def name(element):
    return element.tag.removeprefix(NS)


def load(path):
    data = open(path, "rb").read()
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:  # Gradle writes neither; expat would expand them
        refuse(path, "a DOCTYPE or ENTITY declaration")
    if re.search(rb"<\s*/?\s*[\w.-]+:", data):
        refuse(path, "a prefixed element name, which Gradle reads by its bare name")
    root = ET.fromstring(data)
    if root.tag != f"{NS}verification-metadata" or any(not e.tag.startswith(NS) for e in root.iter()):
        refuse(path, "an element outside Gradle's namespace")
    if [name(c) for c in root] != ["configuration", "components"]:
        refuse(path, f"the root holds {[name(c) for c in root]}, not one configuration and one components")
    for parent in root.find(f"{NS}components").iter():
        stray = [name(c) for c in parent if name(c) not in SHAPE[name(parent)]]
        if stray:
            refuse(path, f"<{name(parent)}> {parent.attrib} holds {stray}")
    return root


def shape(element):
    """Every element under one, as (tag, sorted attributes, text) in document order."""
    return [(e.tag, tuple(sorted(e.attrib.items())), (e.text or "").strip()) for e in element.iter()]


def checksums(artifact):
    """(kind, value, also-trust values) for each checksum of an artifact; origin/reason left out."""
    return sorted(
        (name(c), c.get("value"), tuple(sorted(a.get("value") for a in c)))
        for c in artifact
    )


def components(path, root):
    found = {}
    for component in root.iter(f"{NS}component"):
        key = (component.get("group"), component.get("name"), component.get("version"))
        if key in found:
            refuse(path, f"component {':'.join(map(str, key))} is listed twice")
        artifacts = {}
        for artifact in component:
            if artifact.get("name") in artifacts:
                refuse(path, f"{':'.join(map(str, key))}: {artifact.get('name')} is listed twice")
            artifacts[artifact.get("name")] = checksums(artifact)
        found[key] = artifacts
    return found


def problems(old_path, new_path):
    old_root, new_root = load(old_path), load(new_path)
    out = []
    if shape(old_root.find(f"{NS}configuration")) != shape(new_root.find(f"{NS}configuration")):
        out.append("<configuration> changed")
    old, new = components(old_path, old_root), components(new_path, new_root)
    for key, artifacts in old.items():
        label = ":".join(map(str, key))
        if key not in new:
            out.append(f"{label}: component removed")
            continue
        for artifact, sums in artifacts.items():
            if artifact not in new[key]:
                out.append(f"{label}: {artifact} removed")
            elif new[key][artifact] != sums:
                out.append(f"{label}: {artifact} checksums changed {sums} -> {new[key][artifact]}")
    for key, artifacts in new.items():
        for artifact, sums in artifacts.items():
            if key in old and artifact in old[key]:
                continue
            if len(sums) != 1 or sums[0][0] != "sha256" or not HEX64.fullmatch(sums[0][1] or "") or sums[0][2]:
                out.append(f"{':'.join(map(str, key))}: new {artifact} is not one plain sha256: {sums}")
    return out, old, new


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    found, old, new = problems(argv[0], argv[1])
    added = sum(len(a) for k, a in new.items() if k not in old)
    added += sum(len(set(a) - set(old[k])) for k, a in new.items() if k in old)
    for line in found:
        print(f"REFUSED {line}")
    print(f"{len(new) - len(old)} components and {added} artifacts added; {len(found)} problems")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
