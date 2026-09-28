#!/usr/bin/env python3
"""Owned AOSP AVD: UID egress counters, controlled DNS, real HTTPS/Signal and force-stop.

Never captures host microphone/camera or third-party traffic. Test trust is confined
inside instrumentation. NetworkStack DNS is attributed by the synthetic trap name,
not misleadingly counted as packets from the application's UID.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
from voice_relay_lab import voice_relay

ROOT=Path(__file__).resolve().parents[1]


def valid_report(text):
    return (re.search(r'^OK \(3 tests\)$',text,re.M) is not None
            and 'INSTRUMENTATION_CODE: -1' in text
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b',text)
            and not any(x in text for x in ('FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed')))


def counters(text):
    rows=re.findall(r'^[ \t]*(\d+)[ \t]+(\d+)[ \t]+RETURN(?:[ \t]|$)',text,re.M)
    if len(rows)!=1:raise RuntimeError('Missing/ambiguous UID packet counter')
    return {'packets':int(rows[0][0]),'bytes':int(rows[0][1])}


def sensor_access(text):
    return bool(re.search(r'^\s*(?:RECORD_AUDIO|CAMERA|FINE_LOCATION|COARSE_LOCATION|BLUETOOTH_SCAN):.*(?:time=|duration=)',text,re.M))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--flavor',choices=('connected','offline'),required=True)
    parser.add_argument('--optimized',action='store_true')
    parser.add_argument('--emergency',action='store_true')
    parser.add_argument('--reports',type=Path,required=True)
    args=parser.parse_args();args.reports.mkdir(parents=True,exist_ok=True)
    build='vaultLab' if args.optimized else 'debug'
    package='app.umbra.privatechat'+('.offline' if args.flavor=='offline' else '')+('.vaultlab' if args.optimized else '.dev')
    adb=[str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'),'-s',args.serial]
    uid=None;chains=[];process=None;dns=None
    def run(*command,**kwargs):
        return subprocess.run([*adb,*command],check=True,capture_output=True,timeout=120,**kwargs)
    if run('shell','getprop','ro.kernel.qemu').stdout.strip()!=b'1':raise RuntimeError('Disposable owned AVD required')
    output=ROOT/'android/app/build/outputs'
    apks=[output/f'apk/{args.flavor}/{build}/app-{args.flavor}-{build}.apk',output/f'apk/androidTest/{args.flavor}/{build}/app-{args.flavor}-{build}-androidTest.apk']
    evidence={'synthetic':True,'emergency':args.emergency,'optimized':args.optimized,'exactProductionApk':False,'flavor':args.flavor,
              'artifacts':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in apks},'stages':{}}
    def save(): (args.reports/'receipt.json').write_text(json.dumps(evidence,indent=2)+'\n')
    if args.optimized:
        mapping=output/f'mapping/{args.flavor}VaultLab/mapping.txt';configuration=mapping.with_name('configuration.txt')
        if re.search(r'^\s*-(?:dontoptimize|dontobfuscate|dontshrink)\b',configuration.read_text(),re.M):raise RuntimeError('R8 disabled')
        match=re.search(r'^app\.umbra\.connectivity\.ConnectivityService -> ([^:]+):$',mapping.read_text(),re.M)
        if not match or match[1]=='app.umbra.connectivity.ConnectivityService':raise RuntimeError('Gate not obfuscated')
        evidence['optimizedGate']=match[1];evidence['mappingSha256']=hashlib.sha256(mapping.read_bytes()).hexdigest()
    for apk in apks:run('install','-r',str(apk))
    run('shell','am','force-stop',package)
    run('shell','pm','clear',package)
    installed=run('shell','pm','list','packages','-U',package).stdout.decode()
    match=re.search(r'^package:'+re.escape(package)+r' uid:(\d+)$',installed,re.M)
    if not match:raise RuntimeError('Missing unique application UID')
    uid=match[1];evidence['uid']=int(uid)
    def app(*command,input=None,check=True):
        script='cd /data/user/0/'+package+' && '+' '.join(shlex.quote(x) for x in command)
        return subprocess.run([*adb,'shell','su',uid,'sh','-c',shlex.quote(script)],input=input,capture_output=True,check=check,timeout=15)
    def write(name,value):
        raw=value if isinstance(value,bytes) else json.dumps(value).encode()
        app('sh','-c','umask 077; cat > files/'+name+'.tmp && mv files/'+name+'.tmp files/'+name,input=raw)
    def read(name):
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            value=app('cat','files/'+name,check=False)
            if value.returncode==0:return json.loads(value.stdout)
            if process is not None and process.poll() is not None:raise RuntimeError('Startup fixture exited before '+name)
            time.sleep(.1)
        raise RuntimeError('Startup exchange deadline: '+name)
    def go(stage):write('synthetic-startup-'+stage+'-go',b'go')
    def packets(tool):return counters(run('shell','su','0',tool,'-L','UMBRA_STARTUP','-n','-v','-x').stdout.decode())
    def reset():
        for tool in chains:run('shell','su','0',tool,'-Z','UMBRA_STARTUP')
    def observe(stage,dns_log,quiet=True):
        before=dns_log.read_text().count('TRAP_QUERY')
        start=time.monotonic();time.sleep(5)
        counts={tool:packets(tool) for tool in chains}
        queries=dns_log.read_text().count('TRAP_QUERY')-before
        apps=run('shell','cmd','appops','get',package).stdout.decode()
        (args.reports/(stage+'-appops.txt')).write_text(apps)
        sensors=sensor_access(apps)
        evidence['stages'][stage]={'observedMillis':round((time.monotonic()-start)*1000),'uidEgress':counts,'trapQueries':queries,'sensorOrScanAccess':sensors}
        save()
        if sensors:raise RuntimeError('Sensor/scan access before explicit action: '+stage)
        if stage in ('cold-activity-permissions-granted','cold-engine','unlock-no-network') and dns_log.read_text().count('TRAP_QUERY'):
            raise RuntimeError('Trap DNS query before consent')
        if quiet and (queries or any(v['packets'] for v in counts.values())):raise RuntimeError('Unexpected startup egress: '+stage)
        return counts
    runner=package+'.test/androidx.test.runner.AndroidJUnitRunner'
    command=[*adb,'shell','am','instrument','-w','-r','-e','class','app.umbra.DeviceSignalTest','-e','listener','app.umbra.PrivateStartupFixtureListener','-e','emergency',str(args.emergency).lower(),'-e','startupPhase']
    with tempfile.TemporaryDirectory(prefix='umbra-startup-') as temporary:
        dns_log=Path(temporary)/'dns.log'
        try:
            # Listener is loopback only. No system resolver or public service is changed.
            with dns_log.open('w') as stream:
                dns=subprocess.Popen(['sudo','-n',sys.executable,str(ROOT/'scripts/startup_dns_fixture.py')],stdout=stream,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+5
            while 'READY' not in dns_log.read_text():
                if dns.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Loopback DNS fixture could not bind port 53')
                time.sleep(.05)
            run('shell','settings','put','global','private_dns_mode','off')
            run('shell','settings','put','global','captive_portal_mode','0')
            for tool in ('iptables','ip6tables'):
                run('shell','su','0',tool,'-N','UMBRA_STARTUP');chains.append(tool)
                run('shell','su','0',tool,'-A','UMBRA_STARTUP','-j','RETURN')
                run('shell','su','0',tool,'-I','OUTPUT','-m','owner','--uid-owner',uid,'-j','UMBRA_STARTUP')
            permissions=['ACCESS_FINE_LOCATION','ACCESS_COARSE_LOCATION','BLUETOOTH_CONNECT','BLUETOOTH_SCAN','BLUETOOTH_ADVERTISE']
            if args.flavor=='connected':permissions+=['RECORD_AUDIO','CAMERA']
            for permission in permissions:run('shell','pm','grant',package,'android.permission.'+permission)
            reset();run('shell','am','start','-W','-n',package+'/app.umbra.ui.MainActivity')
            observe('cold-activity-permissions-granted',dns_log)
            run('shell','am','force-stop',package)
            # Independent real Vault/SQLite cases; not attributed as network acceptance.
            log=args.reports/'domain-tests.log'
            with log.open('w') as stream:
                result=subprocess.run([*adb,'shell','am','instrument','-w','-r','-e','class','app.umbra.PrivateStartupTest',runner],stdout=stream,stderr=subprocess.STDOUT,timeout=180)
            if result.returncode or not valid_report(log.read_text()):raise RuntimeError('Private startup domain instrumentation failed')
            with voice_relay() as relay:
                app('mkdir','-p','files')
                write('synthetic-startup-config.json',{'base':relay['base'],'certificate':relay['certificate'],'invitations':relay['invitations'],'realm':relay['admission'].realm.encode()})
                before=args.reports/'before-force-stop.log'
                reset()
                with before.open('w') as stream:process=subprocess.Popen([*command,'prepare',runner],stdout=stream,stderr=subprocess.STDOUT)
                read('synthetic-startup-cold.json');observe('cold-engine',dns_log);go('cold')
                request=read('synthetic-startup-admission-request.json');write('synthetic-startup-admission-credential.json',relay['admission'].approve(request['request']))
                read('synthetic-startup-unlocked.json');observe('unlock-no-network',dns_log);go('unlocked')
                if args.flavor=='connected':
                    request=read('synthetic-startup-peer-request.json');reset()
                    write('synthetic-startup-peer-credential.json',relay['admission'].approve(request['request']))
                    read('synthetic-startup-online.json')
                    counts=observe('explicit-connect',dns_log,quiet=False)
                    total_queries=dns_log.read_text().count('TRAP_QUERY')
                    if not total_queries or not counts['iptables']['packets']:raise RuntimeError('Missing positive DNS/IPv4 egress control')
                    evidence['positiveTrapQueries']=total_queries;save();go('online')
                    read('synthetic-startup-disconnected.json');time.sleep(1);reset();observe('disconnect',dns_log);go('disconnected')
                    read('synthetic-startup-loss-ready.json')
                    run('shell','svc','wifi','disable');run('shell','svc','data','disable');go('loss-ready')
                    read('synthetic-startup-network-lost.json')
                    reset();run('shell','svc','wifi','enable');run('shell','svc','data','enable');time.sleep(5)
                    observe('network-return-no-reconnect',dns_log);go('network-lost')
                    read('synthetic-startup-locked.json');time.sleep(1);reset();observe('vault-lock',dns_log);go('locked')
                else:
                    read('synthetic-startup-offline.json');reset();observe('offline-flavor',dns_log);go('offline')
                if args.emergency:
                    read('synthetic-startup-emergency-closed.json')
                    receipt=read('synthetic-startup-emergency-result.json')
                    if (receipt.get('state')!='CLOSED' or not 0<receipt.get('requestedNanos',0)<=receipt.get('invalidatedNanos',0)<=receipt.get('confirmedNanos',0)
                            or receipt['confirmedNanos']-receipt['requestedNanos']>5_000_000_000):
                        raise RuntimeError('Emergency closure was not bounded and confirmed')
                    evidence['emergency']=receipt;save()
                    reset();observe('emergency-closed',dns_log);go('emergency-closed')
                read('synthetic-startup-kill-ready.json')
                pid=run('shell','pidof',package).stdout.decode().strip()
                if not re.fullmatch(r'\d+',pid):raise RuntimeError('Missing live target before force-stop')
                run('shell','am','force-stop',package);process.wait(timeout=20)
                stopped=subprocess.run([*adb,'shell','pidof',package],capture_output=True,timeout=10)
                if stopped.returncode!=1 or stopped.stdout.strip():raise RuntimeError('Target survived force-stop')
                after=args.reports/'after-force-stop.log'
                reset()
                with after.open('w') as stream:process=subprocess.Popen([*command,'verify',runner],stdout=stream,stderr=subprocess.STDOUT)
                read('synthetic-startup-restarted.json');observe('process-restart',dns_log);go('restarted')
                if process.wait(timeout=60) or not valid_report(after.read_text()):raise RuntimeError('Restart instrumentation failed')
            evidence['result']='PASS';evidence['limits']=['AOSP emulator, no physical Keystore claim','Synthetic SQLite integration; encrypted Vault covered separately','IPv6 absence counters, no positive IPv6 route claim','No media or sensor acquisition requested'];save()
        finally:
            # Attempt every cleanup even if ADB or the fixture has failed. Errors
            # are reported together; none is converted into successful acceptance.
            actions=[]
            if dns is not None and dns.poll() is None:
                actions.append(lambda: (dns.terminate(),dns.wait(timeout=10)))
            actions.append(lambda: run('shell','am','force-stop',package))
            if process is not None and process.poll() is None:
                actions.append(lambda: (process.terminate(),process.wait(timeout=15)))
            for tool in chains:
                actions.extend([
                    lambda tool=tool: run('shell','su','0',tool,'-D','OUTPUT','-m','owner','--uid-owner',uid,'-j','UMBRA_STARTUP'),
                    lambda tool=tool: run('shell','su','0',tool,'-F','UMBRA_STARTUP'),
                    lambda tool=tool: run('shell','su','0',tool,'-X','UMBRA_STARTUP')])
            actions.extend([lambda: run('shell','svc','wifi','enable'),lambda: run('shell','svc','data','enable'),
                            lambda: app('sh','-c','rm -f files/synthetic-startup-*'),
                            lambda: run('shell','pm','clear',package)])
            errors=[]
            for action in actions:
                try: action()
                except Exception as failure: errors.append(failure)
            if dns_log.exists(): (args.reports/'dns-fixture.log').write_text(dns_log.read_text())
            if errors: raise ExceptionGroup('Private startup cleanup failed',errors)


if __name__=='__main__':main()
