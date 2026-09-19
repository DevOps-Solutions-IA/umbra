#!/usr/bin/env python3
"""Fetch the selected official Gradle distribution; verify its pinned checksum.

Requires internet on the build machine. No downloaded binaries are included in this source delivery.
An existing local installation is reused; this does not attest its contents.
"""
from __future__ import annotations
import hashlib
import os
from pathlib import Path
import re
import sys
import urllib.request
import zipfile

VERSION = "8.13"
# https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256
SHA256 = "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
ROOT = Path(__file__).resolve().parents[1]


def bootstrap() -> Path:
    home = ROOT / ".umbra-tools" / f"gradle-{VERSION}"
    executable = home / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")
    if executable.exists():
        return executable
    base = f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip"
    home.parent.mkdir(exist_ok=True)
    expected = SHA256
    if not re.fullmatch(r"[a-f0-9]{64}", expected):
        raise RuntimeError("Invalid official checksum response")
    archive = home.parent / f"gradle-{VERSION}.zip"
    digest = hashlib.sha256()
    size = 0
    try:
        with urllib.request.urlopen(base, timeout=30) as response, archive.open("wb") as output:
            if not response.url.startswith("https://"):
                raise RuntimeError("Refusing non-HTTPS redirect")
            while chunk := response.read(1_048_576):
                size += len(chunk)
                if size > 300_000_000:
                    raise RuntimeError("Unexpected distribution size")
                digest.update(chunk); output.write(chunk)
        if digest.hexdigest() != expected:
            raise RuntimeError("Gradle checksum mismatch")
        with zipfile.ZipFile(archive) as package:
            for member in package.infolist():
                path = (home.parent / member.filename).resolve()
                if not path.is_relative_to(home.parent.resolve()):
                    raise RuntimeError("Unsafe archive member")
            package.extractall(home.parent)
        if os.name != "nt":
            executable.chmod(0o755)
        return executable
    finally:
        archive.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        print(bootstrap())
    except Exception as exc:
        print(f"Gradle bootstrap failed: {exc}", file=sys.stderr)
        sys.exit(1)
