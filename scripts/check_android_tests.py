#!/usr/bin/env python3
"""Reject absent, empty, failed or skipped Android JVM test reports."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def validate(directory: Path, expected: set[str]) -> int:
    suites = [ET.parse(path).getroot() for path in directory.glob("TEST-*.xml")]
    names = {suite.get("name") for suite in suites}
    if not expected or names != expected or len(names) != len(suites):
        raise RuntimeError(f"Missing/unexpected suites in {directory}: expected {expected}, got {names}")
    count = 0
    for suite in suites:
        cases = suite.findall("testcase")
        if not cases or int(suite.get("tests", "0")) != len(cases):
            raise RuntimeError("Empty or inconsistent test suite")
        if any(int(suite.get(key, "0")) != 0 for key in ("failures", "errors", "skipped")) or any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
            raise RuntimeError("Failed or skipped JVM tests")
        count += len(cases)
    return count


def main():
    source = ROOT / "android/app/src/test/java/app/umbra"
    expected = {"app.umbra." + ".".join(path.relative_to(source).with_suffix("").parts) for path in source.rglob("*Test.java")}
    for variant in ("Connected", "Offline"):
        variant_expected = expected | {"app.umbra." + path.stem for path in (ROOT / f"android/app/src/test{variant}/java/app/umbra").glob("*Test.java")}
        count = validate(ROOT / f"android/app/build/test-results/test{variant}DebugUnitTest", variant_expected)
        print(f"PASS {variant}: {count} JVM tests, no failures/errors/skips")


if __name__ == "__main__":
    main()
