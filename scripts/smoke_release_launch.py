#!/usr/bin/env python3
"""Launch R8 APKs on a disposable emulator using an ephemeral synthetic signer.

Never signs with a production key or modifies the original unsigned APK. Refuses
pre-existing release installations and removes only its own installations/copies.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from build_android import java_home

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
args = parser.parse_args()
if not args.sdk:
    parser.error('--sdk or ANDROID_HOME required')
sdk = Path(args.sdk)
adb = str(sdk / 'platform-tools/adb')
serial = args.serial
jdk = java_home()
os.environ['JAVA_HOME'] = str(jdk)
def run(*args):
    p=subprocess.run(list(args),check=True,text=True,capture_output=True,timeout=180)
    return p.stdout
assert run(adb,'-s',serial,'shell','getprop','ro.kernel.qemu').strip()=='1'
with tempfile.TemporaryDirectory(prefix='umbra-synthetic-r8-') as d:
    temp=Path(d); key=temp/'synthetic.p12'
    run(str(jdk/'bin/keytool'),'-genkeypair','-alias','synthetic-validation','-keystore',str(key),'-storepass','synthetic-only','-keypass','synthetic-only','-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Synthetic local validation')
    for flavor in ('connected','offline'):
        package='app.umbra.privatechat'+('.offline' if flavor=='offline' else '')
        assert 'package:'+package not in run(adb,'-s',serial,'shell','pm','list','packages',package).splitlines(), 'Refuse existing release application'
        source=root/f'android/app/build/outputs/apk/{flavor}/release/app-{flavor}-release-unsigned.apk'
        signed=temp/f'{flavor}-synthetic-signed.apk'
        run(str(sdk/'build-tools/35.0.0/apksigner'),'sign','--ks',str(key),'--ks-pass','pass:synthetic-only','--key-pass','pass:synthetic-only','--out',str(signed),str(source))
        run(str(sdk/'build-tools/35.0.0/apksigner'),'verify','--verbose',str(signed))
        installed=False
        try:
            run(adb,'-s',serial,'install',str(signed));installed=True
            result=run(adb,'-s',serial,'shell','am','start','-W','-n',package+'/app.umbra.ui.MainActivity')
            assert 'Status: ok' in result,result
            time.sleep(1)
            resumed=run(adb,'-s',serial,'shell','dumpsys','activity','activities')
            assert any(package+'/app.umbra.ui.MainActivity' in line for line in resumed.splitlines() if 'mResumedActivity:' in line or 'topResumedActivity=' in line)
            run(adb,'-s',serial,'shell','uiautomator','dump','/sdcard/umbra-r8-synthetic.xml')
            tree=ET.fromstring(run(adb,'-s',serial,'shell','cat','/sdcard/umbra-r8-synthetic.xml'))
            assert any(node.get('text')=='Bóveda bloqueada' for node in tree.iter('node'))
            print('PASS',flavor,'release/R8 installed using ephemeral synthetic signer, resumed and displayed locked UI; no unlock/native-crypto/Keystore claim',flush=True)
        finally:
            if installed:
                run(adb,'-s',serial,'shell','am','force-stop',package)
                assert 'Success' in run(adb,'-s',serial,'uninstall',package)
                run(adb,'-s',serial,'shell','rm','-f','/sdcard/umbra-r8-synthetic.xml')
print('PASS temporary signing key/copies deleted, both synthetic release installations removed')
