#!/usr/bin/env python3
"""Check explicit source configuration only. Does not inspect an APK or certify security."""
from pathlib import Path
import xml.etree.ElementTree as ET
from permission_policy import manifest_permissions, validate_permissions

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'


def main() -> None:
    base = ROOT / 'android/app/src'
    manifest = ET.parse(base / 'main/AndroidManifest.xml').getroot()
    app = manifest.find('application')
    checks = 0
    def require(condition: bool, name: str) -> None:
        nonlocal checks
        if not condition:
            raise SystemExit('FAIL source policy: ' + name)
        checks += 1
        print('PASS source policy: ' + name)
    require(app is not None, 'application declared')
    require(app.get(ANDROID + 'allowBackup') == 'false', 'backup disabled in source')
    require(app.get(ANDROID + 'fullBackupContent') == 'false', 'legacy backup disabled')
    require(app.get(ANDROID + 'usesCleartextTraffic') == 'false', 'cleartext networking disabled')
    network = ET.parse(base / 'main/res/xml/network_security_config.xml').getroot()
    require(network.find('base-config').get('cleartextTrafficPermitted') == 'false', 'network policy denies cleartext')
    require([x.get('src') for x in network.iter('certificates')] == ['system'], 'only system trust anchors')
    overlay = ET.parse(base / 'offline/AndroidManifest.xml').getroot()
    removed = {x.get(ANDROID + 'name') for x in overlay.findall('uses-permission') if x.get(TOOLS + 'node') == 'remove'}
    require('android.permission.INTERNET' in removed, 'offline overlay removes INTERNET')
    require('android.permission.ACCESS_NETWORK_STATE' in removed, 'offline overlay removes NETWORK_STATE')
    permissions = manifest_permissions(manifest)
    validate_permissions(permissions, 'connected')
    forbidden = {'android.permission.READ_CONTACTS', 'android.permission.READ_SMS',
                 'android.permission.READ_PHONE_STATE', 'android.permission.ACCESS_BACKGROUND_LOCATION',
                 'android.permission.QUERY_ALL_PACKAGES', 'android.permission.MANAGE_EXTERNAL_STORAGE'}
    require(not (permissions & forbidden), 'no contact/SMS/phone/background-location/broad-storage permissions')
    extraction = ET.parse(base / 'main/res/xml/data_extraction_rules.xml').getroot()
    for section in ('cloud-backup', 'device-transfer'):
        excludes = {(x.get('domain'), x.get('path')) for x in extraction.find(section).findall('exclude')}
        require({('root', '.'), ('database', '.'), ('sharedpref', '.'), ('file', '.'), ('external', '.')} <= excludes,
                section + ' excludes private storage')
    build = (ROOT / 'android/app/build.gradle.kts').read_text()
    require('create("offline")' in build and 'buildConfigField("boolean", "ALLOW_RELAY", "false")' in build,
            'offline compile-time relay switch')
    print(f'{checks} source policy checks passed. Merged manifest/APK verification remains separate.')


if __name__ == '__main__':
    main()
