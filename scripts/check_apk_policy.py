#!/usr/bin/env python3
"""Decode final APK binary XML with aapt, including renamed R8 resource paths."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def decode_tree(text: str) -> ET.Element:
    stack = []
    root = None
    for line in text.splitlines():
        element = re.match(r"(\s*)E: ([\w.-]+)(?: |$)", line)
        if element:
            indent, name = len(element[1]), element[2]
            while stack and stack[-1][0] >= indent:
                stack.pop()
            node = ET.Element(name)
            if stack:
                stack[-1][1].append(node)
            elif root is None:
                root = node
            else:
                raise RuntimeError("Multiple binary XML roots")
            stack.append((indent, node))
        attribute = re.match(r'\s*A: ([\w:.-]+)(?:\(0x[0-9a-f]+\))?=(.*)', line)
        if attribute:
            if not stack:
                raise RuntimeError("Attribute outside XML element")
            name, value = attribute[1], attribute[2]
            if value.startswith('"'):
                value = value.split('"', 2)[1]
            elif value.startswith('(type 0x12)'):
                value = "false" if value.endswith('0x0') else "true"
            stack[-1][1].set(name, value)
    if root is None:
        raise RuntimeError("No XML decoded from APK")
    return root


def validate_manifest(root: ET.Element, variant: str, build_type: str) -> tuple[str, str]:
    if variant not in {"connected", "offline"} or build_type not in {"debug", "release"}:
        raise ValueError("Unknown build variant")
    expected = "app.umbra.privatechat" + (".offline" if variant == "offline" else "") + (".dev" if build_type == "debug" else "")
    if root.tag != "manifest" or root.get("package") != expected or root.findall("instrumentation"):
        raise RuntimeError("Unexpected package or instrumentation in app APK")
    apps = root.findall("application")
    if len(apps) != 1:
        raise RuntimeError("Expected exactly one application")
    app = apps[0]
    for flag in ("allowBackup", "fullBackupContent", "usesCleartextTraffic"):
        if app.get("android:" + flag) != "false":
            raise RuntimeError("Unsafe application flag: " + flag)
    if app.get("android:debuggable", "false") != ("true" if build_type == "debug" else "false"):
        raise RuntimeError("Unexpected debuggable flag")
    if app.get("android:testOnly", "false") != "false":
        raise RuntimeError("Test-only app cannot be a production variant")
    components = [e for e in app if e.tag in {"activity", "activity-alias", "service", "receiver", "provider"}]
    if len(components) != 1 or components[0].tag != "activity" or components[0].get("android:name") != "app.umbra.ui.MainActivity":
        raise RuntimeError("Unexpected app component")
    activity = components[0]
    if activity.get("android:exported") != "true" or {e.get("android:name") for e in activity.iter("action")} != {"android.intent.action.MAIN"} or {e.get("android:name") for e in activity.iter("category")} != {"android.intent.category.LAUNCHER"}:
        raise RuntimeError("Unexpected exported entry point")
    refs = tuple(app.get("android:" + name, "") for name in ("networkSecurityConfig", "dataExtractionRules"))
    if any(not re.fullmatch(r"@0x[0-9a-f]+", ref) for ref in refs):
        raise RuntimeError("Missing compiled TLS or data extraction policy")
    return refs


def validate_resources(network: ET.Element, extraction: ET.Element) -> None:
    base = network.find("base-config")
    if network.tag != "network-security-config" or len(network) != 1 or base is None or base.get("cleartextTrafficPermitted") != "false":
        raise RuntimeError("Cleartext or alternate network policy")
    if [e.get("src") for e in network.iter("certificates")] != ["system"]:
        raise RuntimeError("Only system TLS trust anchors are permitted")
    if extraction.tag != "data-extraction-rules" or {e.tag for e in extraction} != {"cloud-backup", "device-transfer"}:
        raise RuntimeError("Missing backup/transfer policy")
    for section in extraction:
        expected = {(domain, ".") for domain in ("root", "database", "sharedpref", "file", "external")}
        if any(e.tag != "exclude" for e in section) or {(e.get("domain"), e.get("path")) for e in section} != expected:
            raise RuntimeError("Private data is not excluded from " + section.tag)


def validate_mapping(text: str) -> None:
    if not re.search(r"^[^ #\s]+ -> [^\n]+:$", text, re.MULTILINE):
        raise RuntimeError("Missing R8 class map")
    # R8 can inline a test/lab method into a production class. Its origin then
    # appears only on a method mapping line, not as a retained class declaration.
    forbidden = r"(?<![\w.$])(?:app\.umbra\.lab(?:[.$]|$)|androidx\.test(?:[.$]|$)|app\.umbra\.(?:MemoryRecords|DeviceMemoryRecords|VoiceNativeFixtureListener|media[.$](?:Voice(?:Engine|Restart)FixtureListener|CameraProviderFixtureListener|SyntheticVideoCapturer))(?:[.$\s:]|$))"
    if re.search(forbidden, text, re.MULTILINE):
        raise RuntimeError("Test/lab code retained or inlined by R8")


def validate_dex(package: zipfile.ZipFile) -> None:
    names = package.namelist()
    if len(names) != len(set(names)):
        raise RuntimeError("Ambiguous duplicate APK entries")
    if "classes.dex" not in names:
        raise RuntimeError("APK has no primary DEX")
    for name in names:
        if re.fullmatch(r"classes\d*\.dex", name):
            data = package.read(name)
            if (b"Lapp/umbra/lab/" in data or b"Landroidx/test/" in data or
                    re.search(rb"Lapp/umbra/(?:MemoryRecords|DeviceMemoryRecords|VoiceNativeFixtureListener|media/(?:Voice(?:Engine|Restart)FixtureListener|CameraProviderFixtureListener|SyntheticVideoCapturer))[;$]", data)):
                raise RuntimeError("Test/lab code in application DEX")


def inspect(apk: Path, aapt: Path, variant: str, build_type: str) -> None:
    def dump(*args):
        return subprocess.check_output([str(aapt), "dump", *args], text=True)
    manifest = decode_tree(dump("xmltree", str(apk), "AndroidManifest.xml"))
    refs = validate_manifest(manifest, variant, build_type)
    table = dump("--values", "resources", str(apk))
    resources = []
    for ref in refs:
        paths = set(re.findall(r"resource " + ref[1:] + r" [^\n]+\n\s*\(string8\) \"([^\"]+)\"", table))
        if len(paths) != 1:
            raise RuntimeError("Missing or ambiguous compiled resource " + ref)
        resources.append(decode_tree(dump("xmltree", str(apk), paths.pop())))
    validate_resources(*resources)
    if build_type == "release":
        mapping = ROOT / f"android/app/build/outputs/mapping/{variant}Release/mapping.txt"
        validate_mapping(mapping.read_text())
    with zipfile.ZipFile(apk) as package:
        validate_dex(package)
    print(f"PASS APK policy {variant}/{build_type}: package, exported entry, backup, TLS, debug flag, no test/lab DEX")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"), required=False)
    parser.add_argument("--include-release", action="store_true")
    args = parser.parse_args()
    if not args.sdk:
        raise SystemExit("BLOCKED: ANDROID_HOME or --sdk required")
    aapt = Path(args.sdk) / "build-tools/35.0.0" / ("aapt.exe" if os.name == "nt" else "aapt")
    for kind in (["debug", "release"] if args.include_release else ["debug"]):
        for variant in ("connected", "offline"):
            suffix = "-unsigned" if kind == "release" else ""
            apk = ROOT / f"android/app/build/outputs/apk/{variant}/{kind}/app-{variant}-{kind}{suffix}.apk"
            inspect(apk, aapt, variant, kind)


if __name__ == "__main__":
    main()
