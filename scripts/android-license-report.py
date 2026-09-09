#!/usr/bin/env python3
# SPDX-License-Identifier: MPL-2.0
"""Offline notice generation from the actual Gradle runtime artifacts."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile


LIMIT = 4 * 1024 * 1024
NOTICE = re.compile(r"(?:^|/)(?:LICENSE|NOTICE|COPYING|COPYRIGHT)(?:[._-]|$)", re.I)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def embedded_notices(data, prefix="", depth=0):
    found = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate dependency archive entries")
        for info in archive.infolist():
            if info.is_dir():
                continue
            if NOTICE.search(info.filename):
                if info.file_size > LIMIT:
                    raise ValueError("Oversized dependency notice")
                found.append((prefix + info.filename, archive.read(info).decode("utf-8")))
            elif depth == 0 and (info.filename == "classes.jar" or
                                 (info.filename.startswith("libs/") and info.filename.endswith(".jar"))):
                if info.file_size > 64 * 1024 * 1024:
                    raise ValueError("Oversized nested dependency archive")
                found.extend(embedded_notices(archive.read(info), prefix + info.filename + "!", 1))
    return found


def make_report(artifacts, policy, license_dir, variant):
    if policy.get("schema") != "navis-android-licenses-v1" or not artifacts:
        raise ValueError("Missing or unsupported license inventory")
    reviewed = policy["artifacts"]
    components = {}
    notices = {}
    for artifact in artifacts:
        coordinate = artifact["coordinate"]
        path = Path(artifact["file"])
        key = coordinate + "@" + path.suffix.lstrip(".")
        entry = reviewed.get(key)
        if entry is None:
            raise ValueError(f"Unreviewed dependency: {key}")
        data = path.read_bytes()
        digest = sha256(data)
        if digest != entry["sha256"]:
            raise ValueError(f"Changed dependency needs license review: {key}")
        if key in components:
            continue
        for license_id in entry["licenses"]:
            if license_id not in policy["licenses"]:
                raise ValueError(f"Unknown license: {license_id}")
        if not entry.get("source") or not entry.get("license_evidence"):
            raise ValueError(f"Missing source/license provenance: {key}")
        components[key] = {"coordinate": coordinate, "artifact": path.name,
                           "sha256": digest, "licenses": entry["licenses"], "source": entry["source"]}
        texts = embedded_notices(data)
        for notice_path in entry.get("additional_notices", []):
            if Path(notice_path).name != notice_path:
                raise ValueError("Notice must be a direct policy input")
            texts.append((notice_path, (license_dir / notice_path).read_text()))
        for location, text in texts:
            notice = notices.setdefault(sha256(text.encode()), {"text": text, "origins": set()})
            notice["origins"].add(f"{coordinate}: {location}")
    if set(components) != set(policy.get("variants", {}).get(variant, [])):
        raise ValueError(f"Runtime dependency set changed for {variant}; review the license inventory")
    parts = [f"Navis Android runtime dependency notices ({variant})",
             "These notices cover the resolved Gradle runtime artifacts, not Gecko's native dependency catalogue. Component licenses remain independent of the Navis MPL-2.0 license."]
    for key, value in sorted(components.items()):
        parts.append(f"{key}\nLicense: {', '.join(value['licenses'])}\nSource: {value['source']}")
    for license_id in sorted({x for c in components.values() for x in c["licenses"]}):
        spec = policy["licenses"][license_id]
        if Path(spec["file"]).name != spec["file"]:
            raise ValueError("License text must be a direct policy input")
        data = (license_dir / spec["file"]).read_bytes()
        if sha256(data) != spec["sha256"]:
            raise ValueError(f"Changed license text: {license_id}")
        parts.append(license_id + "\n" + data.decode("utf-8"))
    for digest in sorted(notices):
        notice = notices[digest]
        parts.append("Embedded/supplemental notices from:\n" + "\n".join(sorted(notice["origins"])) + "\n\n" + notice["text"])
    text = "\n\n".join(parts) + "\n"
    if len(text.encode()) > LIMIT:
        raise ValueError("License report exceeds the product reader limit")
    report = {"schema": "navis-android-license-report-v1", "variant": variant,
              "components": [components[k] for k in sorted(components)], "text_sha256": sha256(text.encode())}
    return text, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--policy", required=True, type=Path)
    parser.add_argument("--variant", required=True, choices=("debug", "release"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    text, report = make_report(json.loads(args.inventory.read_text()), json.loads(args.policy.read_text()), args.policy.parent, args.variant)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "android-dependencies.txt").write_text(text, encoding="utf-8")
    (args.output / "android-dependencies.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Verified offline notices for {len(report['components'])} {args.variant} dependencies")


if __name__ == "__main__":
    main()
