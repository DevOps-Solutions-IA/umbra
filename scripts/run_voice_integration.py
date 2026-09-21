#!/usr/bin/env python3
"""Two AVDs with independent Engine/SQLite/Signal/HTTPS and native synthetic TURN voice."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import tempfile
import ipaddress
import shutil
from voice_network_evidence import summarize, count
from turn_lab import TurnLab, docker
from voice_relay_lab import voice_relay
from android_apk_install import ensure_apk
from voice_direct_route import probe_udp

ROOT=Path(__file__).resolve().parents[1]
PACKAGE="app.umbra.privatechat.dev"


def valid_audio(value):
    return (value.get("decodedBuffers",0)>=100 and value.get("capturedBuffers",0)>=100
            and value.get("verifiedNativeTransport") is True and value.get("receivedAudioPackets",0)>=50
            and value.get("codec")=="audio/opus" and value.get("sdpAddressAudit") is True)


def valid_report(report):
    return ("engineVoice=PASS" in report and re.search(r"^OK \(3 tests\)$",report,re.M)
            and "INSTRUMENTATION_CODE: -1" in report
            and not re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b",report)
            and not any(x in report for x in ("FAILURES!!!","INSTRUMENTATION_FAILED","Process crashed")))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--a",required=True); parser.add_argument("--b",required=True)
    parser.add_argument("--reports",type=Path,required=True)
    parser.add_argument("--scenario",choices=("audio","expired-auth","allocation-expiry","invalid-auth","unreachable","turn-loss","trust-loss","lock","credential-expiry","direct-blocked","force-stop","permission-revoked","device-revoked","storage-failure","unauthorized-redirect"),default="audio")
    args=parser.parse_args()
    if args.a==args.b: raise ValueError("Two independent AVD serials required")
    adb=str(Path(os.environ["ANDROID_HOME"])/"platform-tools/adb")
    def run(serial,*command,**kwargs):
        return subprocess.run([adb,"-s",serial,*command],check=True,capture_output=True,timeout=120,**kwargs)
    def write(serial,name,payload):
        run(serial,"shell","run-as",PACKAGE,"sh","-c",f"'umask 077; cat > files/{name}.tmp && mv files/{name}.tmp files/{name}'",input=json.dumps(payload).encode())
    def read(serial,name,process,deadline):
        while time.monotonic()<deadline:
            if process.poll() is not None: raise RuntimeError("Android voice fixture exited before exchange; inspect sanitized instrumentation report")
            result=subprocess.run([adb,"-s",serial,"shell","run-as",PACKAGE,"cat","files/"+name],capture_output=True,timeout=15)
            if result.returncode==0: return json.loads(result.stdout)
            if result.returncode!=1: raise RuntimeError("ADB voice exchange failed")
            time.sleep(0.1)
        raise RuntimeError("Two-AVD voice exchange deadline exceeded: "+name)
    for serial in (args.a,args.b):
        if run(serial,"shell","getprop","ro.kernel.qemu").stdout.strip()!=b"1": raise RuntimeError("Only synthetic AVDs supported")
        run(serial,"shell","svc","power","stayon","true")
        run(serial,"shell","input","keyevent","KEYCODE_WAKEUP")
        run(serial,"shell","svc","wifi","enable")
        for path in ("connected/debug/app-connected-debug.apk","androidTest/connected/debug/app-connected-debug-androidTest.apk"):
            ensure_apk(adb,serial,PACKAGE+(".test" if path.startswith("androidTest/") else ""),ROOT/"android/app/build/outputs/apk"/path)
        run(serial,"shell","run-as",PACKAGE,"mkdir","-p","files")
        if args.scenario=="permission-revoked": run(serial,"shell","pm","grant",PACKAGE,"android.permission.RECORD_AUDIO")
        # Explicit names only, confined to this disposable debug UID.
        for suffix in ("public","peer","ready","start","audio","mute","muted","resume","resumed","loss","lost","stop"):
            run(serial,"shell","run-as",PACKAGE,"rm","-f",f"files/synthetic-voice-{suffix}.json")
    args.reports.mkdir(parents=True,exist_ok=True)
    processes={}; streams=[]
    with voice_relay() as relay, TurnLab(alternate_port=3479 if args.scenario=="unauthorized-redirect" else None, allocation_lifetime=20 if args.scenario=="allocation-expiry" else 180) as turn:
        capture_paths=[]; blocked_routes=[]; allocation_evidence=None
        try:
            addresses=[]
            for serial in (args.a,args.b):
                response=run(serial,"emu","avd","path").stdout.decode()
                if "KO:" in response: raise RuntimeError("AVD path lookup failed")
                avd_path=Path(response.splitlines()[0])
                root=avd_path.parent.parent/"umbra-netsim-private"
                candidates=list(root.glob("android*/netsimd/pcaps/*-"+avd_path.stem+"-WIFI.pcap"))
                if len(candidates)!=1: raise RuntimeError("Owned netsim Wi-Fi capture missing; start with ci_emulator.sh")
                capture_paths.append(candidates[0])
                ready=time.monotonic()+20
                while True:
                    result=run(serial,"shell","ip","-4","addr","show","wlan0").stdout.decode()
                    address=re.search(r"inet (10\.0\.2\.[0-9]+)/",result)
                    if address: break
                    if time.monotonic()>=ready: raise RuntimeError("Disposable AVD Wi-Fi did not acquire an address")
                    time.sleep(0.2)
                addresses.append(str(ipaddress.ip_address(address[1])))
            if addresses[0]==addresses[1]: raise RuntimeError("Expected independent AVD Wi-Fi addresses")
            for index,serial in enumerate((args.a,args.b)):
                if not probe_udp(adb,serial,(args.a,args.b)[1-index],addresses[1-index]):
                    raise RuntimeError("Direct IPv4 UDP route between owned AVDs unavailable")
            capture_since=time.time()
            if args.scenario=="direct-blocked":
                for index,serial in enumerate((args.a,args.b)):
                    peer=addresses[1-index]
                    run(serial,"shell","su","0","iptables","-I","OUTPUT","-d",peer,"-m","comment","--comment","umbra-private-voice-test","-j","REJECT")
                    blocked_routes.append((serial,peer))
                    if probe_udp(adb,serial,(args.a,args.b)[1-index],peer):
                        raise RuntimeError("Direct UDP route blocking was not demonstrated")
            for index,serial in enumerate((args.a,args.b)):
                credentials=turn.credentials(30 if args.scenario=="credential-expiry" else 180)
                if args.scenario=="expired-auth":
                    credentials=turn.credentials(1)
                    while time.time()<=credentials["expires"]: time.sleep(0.1)
                    # Deliberately inconsistent local metadata tests SERVER rejection,
                    # not a bypass in the productive credential/configuration provider.
                    credentials["expires"]=int(time.time())+180
                if args.scenario=="invalid-auth": credentials["password"]="synthetic-invalid-credential"
                if args.scenario=="unreachable": credentials["urls"]=[value.replace(":3478",":3479") for value in credentials["urls"]]
                write(serial,"synthetic-voice-engine.json",{"expectedRejection":args.scenario in ("expired-auth","invalid-auth","unreachable","unauthorized-redirect"),"role":"A" if index==0 else "B","base":relay["base"],
                    "certificate":relay["certificate"],"invitation":relay["invitations"][index],"turn":credentials})
                stream=(args.reports/("engine-voice-a.log" if index==0 else "engine-voice-b.log")).open("w")
                streams.append(stream)
                processes[serial]=subprocess.Popen([adb,"-s",serial,"shell","am","instrument","-w","-r",
                    "-e","class","app.umbra.DeviceSignalTest","-e","listener","app.umbra.media.VoiceEngineFixtureListener",
                    PACKAGE+".test/androidx.test.runner.AndroidJUnitRunner"],stdout=stream,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+90
            a=read(args.a,"synthetic-voice-public.json",processes[args.a],deadline)
            b=read(args.b,"synthetic-voice-public.json",processes[args.b],deadline)
            write(args.a,"synthetic-voice-peer.json",b); write(args.b,"synthetic-voice-peer.json",a)
            for serial in (args.a,args.b):
                if read(serial,"synthetic-voice-ready.json",processes[serial],deadline)!={"ready":True}: raise RuntimeError("Identity preparation failed")
            for serial in (args.a,args.b): write(serial,"synthetic-voice-start.json",{"consent":True})
            evidence=[]
            for serial in (args.a,args.b):
                value=read(serial,"synthetic-voice-audio.json",processes[serial],deadline)
                if not (value=={"rejectedBeforeCapture":True} if args.scenario in ("expired-auth","invalid-auth","unreachable","unauthorized-redirect") else valid_audio(value)):
                    raise RuntimeError("Missing native decoded audio evidence")
                evidence.append(value)
            if args.scenario not in ("expired-auth","invalid-auth","unreachable","unauthorized-redirect"):
                for serial in (args.a,args.b): write(serial,"synthetic-voice-mute.json",{"mute":True})
                for serial in (args.a,args.b):
                    if read(serial,"synthetic-voice-muted.json",processes[serial],deadline)!={"quiet":True}:
                        raise RuntimeError("Muted endpoints continued delivering decoded tones")
                for serial in (args.a,args.b): write(serial,"synthetic-voice-resume.json",{"resume":True})
                for serial in (args.a,args.b):
                    if read(serial,"synthetic-voice-resumed.json",processes[serial],deadline).get("decodedAfterUnmute",0)<50:
                        raise RuntimeError("Native audio did not resume after unmute")
            if args.scenario in ("turn-loss","trust-loss","lock","credential-expiry","device-revoked","storage-failure"):
                for serial in (args.a,args.b): write(serial,"synthetic-voice-loss.json",{"action":args.scenario})
                if args.scenario=="turn-loss":
                    docker("stop","--time","0",turn.name)
                for serial in (args.a,args.b):
                    if read(serial,"synthetic-voice-lost.json",processes[serial],deadline)!={"failedClosed":True}:
                        raise RuntimeError("Native media did not stop for scenario: "+args.scenario)
            if args.scenario in ("allocation-expiry","force-stop","permission-revoked"):
                if args.scenario=="allocation-expiry":
                    allocated=turn.allocation_count()
                    if allocated<2: raise RuntimeError("Two real TURN allocations were not observed before process death")
                    allocation_evidence={"maxLifetimeSeconds":20,"allocationsBeforeDeath":allocated}
                for index,serial in enumerate((args.a,args.b)):
                    pid=run(serial,"shell","pidof",PACKAGE).stdout.decode().strip()
                    if not re.fullmatch(r"[0-9]+",pid): raise RuntimeError("Native audio process missing before planned termination")
                    if args.scenario in ("force-stop","allocation-expiry"): run(serial,"shell","am","force-stop",PACKAGE)
                    else: run(serial,"shell","pm","revoke",PACKAGE,"android.permission.RECORD_AUDIO")
                    gone=time.monotonic()+10
                    while True:
                        probe=subprocess.run([adb,"-s",serial,"shell","pidof",PACKAGE],capture_output=True,timeout=5)
                        if probe.returncode==1 and not probe.stdout.strip(): break
                        if time.monotonic()>=gone: raise RuntimeError("Media process survived planned termination")
                        time.sleep(0.1)
                    processes[serial].wait(timeout=15)
                    # The BEFORE reports intentionally end abruptly. Do not classify them as successful JUnit runs.
                    report=args.reports/f"restart-{index}.log"
                    with report.open("w") as stream:
                        result=subprocess.run([adb,"-s",serial,"shell","am","instrument","-w","-r",
                            "-e","class","app.umbra.DeviceSignalTest","-e","listener","app.umbra.media.VoiceRestartFixtureListener",
                            PACKAGE+".test/androidx.test.runner.AndroidJUnitRunner"],stdout=stream,stderr=subprocess.STDOUT,timeout=45)
                    text=report.read_text()
                    if result.returncode!=0 or "voiceRestart=PASS" not in text or not valid_report(text.replace("voiceRestart=PASS","engineVoice=PASS")):
                        raise RuntimeError("SQLite/native voice restart rejection did not execute successfully")
            else:
                for serial in (args.a,args.b): write(serial,"synthetic-voice-stop.json",{"stop":True})
                for serial in (args.a,args.b):
                    if processes[serial].wait(timeout=30)!=0: raise RuntimeError("Voice instrumentation process failed")
                for stream in streams: stream.flush()
                for name in ("engine-voice-a.log","engine-voice-b.log"):
                    report=(args.reports/name).read_text()
                    if not valid_report(report):
                        raise RuntimeError("Missing/failed authenticated voice evidence: "+name)
            if allocation_evidence is not None:
                remaining=turn.allocation_count(); began=time.monotonic()
                allocation_evidence["allocationsAfterProcessRecovery"]=remaining
                while turn.allocation_count()!=0:
                    if time.monotonic()-began>=35: raise RuntimeError("TURN allocations survived their bounded lifetime without clients")
                    time.sleep(0.5)
                allocation_evidence.update({"allocationsAfterExpiry":0,"observedWaitSeconds":round(time.monotonic()-began,3)})
            capture_until=time.time()
            if args.scenario=="turn-loss":
                for index,serial in enumerate((args.a,args.b)):
                    if not probe_udp(adb,serial,(args.a,args.b)[1-index],addresses[1-index]):
                        raise RuntimeError("Direct UDP route unavailable after TURN loss")
            network=[]
            with tempfile.TemporaryDirectory(prefix="umbra-owned-wifi-snapshot-") as snapshot_dir:
                for index,path in enumerate(capture_paths):
                    snapshot=Path(snapshot_dir)/f"endpoint-{index}.pcap"
                    shutil.copyfile(path,snapshot)
                    if args.scenario=="unauthorized-redirect":
                        redirected=count(snapshot,f"ip and src net 10.0.2.0/24 and udp and dst host {turn.address} and dst port 3479",capture_since,capture_until)
                        if index==0 and redirected==0: raise RuntimeError("Native unauthorized TURN redirection was not reproduced")
                        network.append({"nativePolicy":"BLOCKED: unapproved TURN redirect", "unapprovedTurnPackets":redirected,
                                        "productionEntry":"FAIL_CLOSED", "ipv6":"NOT_EXECUTED"})
                    else:
                        network.append(summarize(snapshot,turn.address,turn_port=3479 if args.scenario=="unreachable" else 3478,
                                                 since=capture_since,until=capture_until,require_turn=(index==0 or args.scenario not in ("expired-auth","invalid-auth","unreachable"))))
            (args.reports/"voice-evidence.json").write_text(json.dumps({"synthetic":True,"endpoints":2,"observedSeconds":round(capture_until-capture_since,3),"transport":"native WebRTC through coturn UDP","scenario":args.scenario,"directIpv4Reachability":True,"directBlockedDuringMedia":args.scenario=="direct-blocked","muteUnmute":args.scenario not in ("expired-auth","invalid-auth","unreachable","unauthorized-redirect"),"network":network,"audio":evidence,"allocationExpiry":allocation_evidence},indent=2)+"\n")
            print(("CONFIRMED native policy BLOCKED; production entry disabled: " if args.scenario=="unauthorized-redirect" else "PASS two AVD native voice scenario: ")+args.scenario)
        finally:
            errors=[]
            for serial,peer in blocked_routes:
                try: run(serial,"shell","su","0","iptables","-D","OUTPUT","-d",peer,"-m","comment","--comment","umbra-private-voice-test","-j","REJECT")
                except Exception as failure: errors.append(failure)
            for serial,process in processes.items():
                try: run(serial,"shell","am","force-stop",PACKAGE)
                except Exception as failure: errors.append(failure)
                try:
                    if process.poll() is None: process.terminate()
                    process.wait(timeout=15)
                except Exception as failure: errors.append(failure)
                for suffix in ("engine","public","peer","ready","start","audio","mute","muted","resume","resumed","loss","lost","stop"):
                    try: run(serial,"shell","run-as",PACKAGE,"rm","-f",f"files/synthetic-voice-{suffix}.json")
                    except Exception as failure: errors.append(failure)
                # These exact files belong solely to this named synthetic fixture, including failed runs.
                for suffix in ("","-journal","-wal","-shm"):
                    try: run(serial,"shell","run-as",PACKAGE,"rm","-f","cache/synthetic-device-membership-lab/synthetic-device-voice-restart.db"+suffix)
                    except Exception as failure: errors.append(failure)
            for stream in streams: stream.close()
            if errors: raise RuntimeError("Voice lab cleanup failed; all endpoints were attempted") from errors[0]


if __name__=="__main__": main()
