#!/usr/bin/env python3
"""Explicit Camera2-only test on owned AOSP AVDs; no media/Engine or physical claim."""
import argparse
import os
from pathlib import Path
import re
import subprocess
from android_apk_install import ensure_apk
from check_optimized_media import inspect


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True);parser.add_argument('--reports',type=Path,required=True)
    parser.add_argument('--optimized',action='store_true');args=parser.parse_args()
    root=Path(__file__).resolve().parents[1];adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
    def run(*cmd):return subprocess.run([adb,'-s',args.serial,*cmd],capture_output=True,text=True,check=True,timeout=60)
    if run('shell','getprop','ro.kernel.qemu').stdout.strip()!='1':raise RuntimeError('Disposable AVD required')
    if args.optimized:inspect()
    variant='mediaLab' if args.optimized else 'debug';package='app.umbra.privatechat.'+('medialab' if args.optimized else 'dev')
    for path in (f'connected/{variant}/app-connected-{variant}.apk',f'androidTest/connected/{variant}/app-connected-{variant}-androidTest.apk'):
        ensure_apk(adb,args.serial,package+('.test' if path.startswith('androidTest/') else ''),root/'android/app/build/outputs/apk'/path)
    args.reports.mkdir(parents=True,exist_ok=True)
    try:
        for case in ('denied','capture'):
            run('shell','am','force-stop',package)
            run('shell','pm','revoke' if case=='denied' else 'grant',package,'android.permission.CAMERA')
            result=run('shell','am','instrument','-w','-r','-e','class','app.umbra.DeviceSignalTest','-e','listener',
                'app.umbra.media.CameraProviderFixtureListener','-e','cameraCase',case,package+'.test/androidx.test.runner.AndroidJUnitRunner')
            (args.reports/(case+'.log')).write_text(result.stdout+result.stderr)
            if ('cameraProvider=PASS '+case not in result.stdout or not re.search(r'^OK \(3 tests\)$',result.stdout,re.M)
                or 'INSTRUMENTATION_CODE: -1' not in result.stdout
                or re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b',result.stdout)
                or any(x in result.stdout for x in ('FAILURES!!!','Process crashed','INSTRUMENTATION_FAILED'))):
                raise RuntimeError('Camera provider did not execute successfully: '+case)
        result=run('shell','am','instrument','-w','-r','-e','class','app.umbra.media.VideoSurfaceLifecycleTest',
                   package+'.test/androidx.test.runner.AndroidJUnitRunner')
        (args.reports/'surface-lifecycle.log').write_text(result.stdout+result.stderr)
        if (not re.search(r'^OK \(2 tests\)$',result.stdout,re.M) or 'INSTRUMENTATION_CODE: -1' not in result.stdout
            or re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b',result.stdout)
            or any(x in result.stdout for x in ('FAILURES!!!','Process crashed','INSTRUMENTATION_FAILED'))):
            raise RuntimeError('Video surface lifecycle checks did not execute successfully')
        print('PASS AVD Camera2 provider: denial, front/back capture, switch, bounded closure; not a physical camera or codec claim')
    finally:
        run('shell','am','force-stop',package)
        run('shell','pm','revoke',package,'android.permission.CAMERA')

if __name__=='__main__':main()
