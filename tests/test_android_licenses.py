# SPDX-License-Identifier: MPL-2.0
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("licenses", ROOT / "scripts/android-license-report.py")
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


def archive(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        for path, value in entries.items():
            output.writestr(path, value)
    return buffer.getvalue()


class LicenseReportTests(unittest.TestCase):
    def test_exact_variant_dedup_nested_notices_and_determinism(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "example.aar"
            artifact.write_bytes(archive({"classes.jar": archive({"META-INF/NOTICE": "Attribution"}),
                                          "META-INF/LICENSE.md": "Library license"}))
            license_data = b"Example license\n"
            (root / "license.txt").write_bytes(license_data)
            policy = {"schema": "navis-android-licenses-v1", "variants": {"release": ["test:example:1@aar"]}, "licenses": {"Example": {
                "file": "license.txt", "sha256": REPORT.sha256(license_data)}}, "artifacts": {
                "test:example:1@aar": {"sha256": REPORT.sha256(artifact.read_bytes()),
                    "licenses": ["Example"], "source": "https://example.org/source", "license_evidence": "https://example.org/license"}}}
            rows = [{"coordinate": "test:example:1", "file": str(artifact)}]
            text, manifest = REPORT.make_report(rows * 2, policy, root, "release")
            self.assertEqual((text, manifest), REPORT.make_report(rows, policy, root, "release"))
            self.assertEqual(len(manifest["components"]), 1)
            self.assertIn("Attribution", text)
            self.assertIn("Library license", text)
            self.assertNotIn(directory, text)
            for mutation in ("unknown", "changed", "missing_license", "changed_license", "omitted_dependency", "unknown_variant"):
                broken = copy.deepcopy(policy)
                if mutation == "unknown": broken["artifacts"] = {}
                if mutation == "changed": broken["artifacts"]["test:example:1@aar"]["sha256"] = "0" * 64
                if mutation == "missing_license": broken["licenses"] = {}
                if mutation == "changed_license": broken["licenses"]["Example"]["sha256"] = "0" * 64
                if mutation == "omitted_dependency": broken["variants"]["release"].append("test:another:1@jar")
                if mutation == "unknown_variant": broken["variants"] = {}
                with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                    REPORT.make_report(rows, broken, root, "release")

    def test_reviewed_policy_is_self_contained(self):
        root = ROOT / "android/licenses"
        policy = json.loads((root / "dependencies.json").read_text())
        self.assertGreaterEqual(len(policy["artifacts"]), 100)
        for value in policy["licenses"].values():
            self.assertEqual(REPORT.sha256((root / value["file"]).read_bytes()), value["sha256"])
        for value in policy["artifacts"].values():
            self.assertRegex(value["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(value["source"].startswith("https://"))
            self.assertTrue(value["license_evidence"].startswith("https://"))
            self.assertTrue(value["licenses"])


if __name__ == "__main__":
    unittest.main()
