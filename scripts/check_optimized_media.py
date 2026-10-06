#!/usr/bin/env python3
"""Check the isolated R8 target; this is packaging evidence, not decoded audio."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from check_apk_policy import decode_tree, validate_dex, validate_manifest, validate_mapping
from check_voice_artifact import PIN, verify_apk

ROOT = Path(__file__).resolve().parents[1]


def optimized_classes(mapping: str, configuration: str) -> dict[str, str]:
    validate_mapping(mapping)
    if re.search(r'^\s*-(?:dontoptimize|dontobfuscate|dontshrink)\b', configuration, re.M):
        raise ValueError('Optimization disabled in mediaLab')
    names = {}
    for original in ('app.umbra.media.NativeVoiceSession', 'app.umbra.calls.CallService', 'app.umbra.crypto.Engine'):
        matched = re.search(r'^' + re.escape(original) + r' -> ([^:]+):$', mapping, re.M)
        if not matched or matched[1] == original:
            raise ValueError('Required media implementation was removed or not obfuscated')
        names[original] = matched[1]
    return names


def inspect() -> dict:
    output = ROOT / 'android/app/build/outputs'
    apk = output / 'apk/connected/mediaLab/app-connected-mediaLab.apk'
    mapping = output / 'mapping/connectedMediaLab/mapping.txt'
    configuration = output / 'mapping/connectedMediaLab/configuration.txt'
    names = optimized_classes(mapping.read_text(), configuration.read_text())
    aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/aapt'
    manifest = decode_tree(subprocess.check_output([str(aapt), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'], text=True))
    if manifest.get('package') != 'app.umbra.privatechat.medialab':
        raise ValueError('Not the isolated mediaLab package')
    # Reuse the production structural constraints after separately checking lab ID.
    manifest.set('package', 'app.umbra.privatechat')
    validate_manifest(manifest, 'connected', 'release')
    with zipfile.ZipFile(apk) as archive:
        validate_dex(archive)
        verify_apk(archive, 'connected', json.loads(PIN.read_text()))
        dex = b''.join(archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n))
        for name in names.values():
            if ('L' + name.replace('.', '/') + ';').encode() not in dex:
                raise ValueError('Mapped implementation missing from packaged DEX')
    def sha(path):
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'package': 'app.umbra.privatechat.medialab', 'debuggable': False,
            'apkSha256': sha(apk), 'mappingSha256': sha(mapping),
            'configurationSha256': sha(configuration), 'obfuscatedClasses': names,
            'exactProductionApk': False, 'mediaExecution': 'NOT_EXECUTED_BY_THIS_CHECK'}


if __name__ == '__main__':
    print(json.dumps(inspect(), indent=2))
