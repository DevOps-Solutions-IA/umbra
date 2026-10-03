#!/usr/bin/env python3
"""Two AVDs with independent Engine/SQLite/Signal/HTTPS and native synthetic TURN voice."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import ipaddress
import shlex
from turn_lab import TurnLab, docker
from voice_relay_lab import voice_relay
from android_apk_install import ensure_apk
from admission_lab import reset_exchange
from voice_direct_route import probe_udp, wait_wifi_ipv4, observe_owned_network, initialize_owned_wifi
from check_optimized_media import inspect as inspect_optimized_media

ROOT=Path(__file__).resolve().parents[1]
PACKAGE="app.umbra.privatechat.dev"


def await_expired_turn_timestamp(expires, *, clock=time.time, monotonic=time.monotonic, sleep=time.sleep):
    """Lab-only wait for an already issued REST credential; never renew it."""
    deadline = monotonic() + 3
    # coturn REST auth uses time(NULL): equality is still valid for that whole second.
    while int(clock()) <= expires:
        if monotonic() >= deadline:
            raise RuntimeError("TURN expiry clock did not advance")
        sleep(0.1)


def issue_turn_after_selection(serials, processes, deadline, write, read, issue):
    """Issue once, after both real Engines authorize their selected media device.

    Bootstrap/enrollment time must not consume the short credential-expiry test
    interval. This does not extend, renew or replace a credential in use.
    """
    for serial in serials:
        if read(serial,"synthetic-voice-turn-ready.json",processes[serial],deadline)!={"selectedAndConsented":True}:
            raise RuntimeError("TURN requested before selected media authorization")
    for serial in serials:
        write(serial,"synthetic-voice-turn.json",issue())


def valid_impairment(value):
    return all(re.search(pattern,value) for pattern in (r"\bnetem\b",r"\blimit 20\b",r"\bloss 2%(?:\s|$)",r"\brate 128Kbit\b",r"\bdelay 80(?:\.0)?ms\b"))


def valid_audio(value):
    return (value.get("decodedBuffers",0)>=100 and value.get("capturedBuffers",0)>=100
            and value.get("verifiedNativeTransport") is True and value.get("receivedAudioPackets",0)>=50
            and value.get("codec")=="audio/opus" and value.get("sdpAddressAudit") is True)


def processing_barrier(serials, processes, deadline, index, write, read):
    applied=[]
    for serial in serials:
        value=read(serial,f"synthetic-voice-processing-applied-{index}.json",processes[serial],deadline)
        if (set(value)!={"step","applied","elapsedMillis"} or type(value["step"]) is not int
                or value["step"]!=index or value["applied"] is not True
                or type(value["elapsedMillis"]) is not int or value["elapsedMillis"]<=0):
            raise RuntimeError("Missing or replayed processing action confirmation")
        applied.append(value)
    for serial in serials:write(serial,f"synthetic-voice-processing-observe-{index}.json",{"bothApplied":True,"step":index})
    return applied


def coordinate_mute(serials, processes, deadline, write, read):
    for serial in serials: write(serial,"synthetic-voice-mute.json",{"mute":True})
    applied=[]
    for serial in serials:
        value=read(serial,"synthetic-voice-mute-applied.json",processes[serial],deadline)
        if set(value)!={"applied","elapsedMillis"} or value["applied"] is not True or type(value["elapsedMillis"]) is not int or value["elapsedMillis"]<=0:
            raise RuntimeError("Missing native mute application confirmation")
        applied.append(value)
    # No receive window starts while the other endpoint has yet to apply mute.
    for serial in serials:write(serial,"synthetic-voice-mute-observe.json",{"bothApplied":True})
    observed=[]
    for serial in serials:
        value=read(serial,"synthetic-voice-muted.json",processes[serial],deadline)
        if (set(value)!={"quiet","observedMillis","decodedTones"} or value["quiet"] is not True
                or type(value["observedMillis"]) is not int or not 1200<=value["observedMillis"]<=2500
                or type(value["decodedTones"]) is not int or not 0<=value["decodedTones"]<=3):
            raise RuntimeError("Muted endpoints continued delivering decoded tones or lacked a positive observation window")
        observed.append(value)
    return {"applied":applied,"observed":observed}


def valid_stop(report, *, expected_expiry=False):
    return (set(report)=={"failedClosed","nativeCaptureQuietAfterMillis","nativeCaptureObservedMillis","lateCaptureCallbacks","expiredDeliveriesRejected"}
            and report["failedClosed"] is True
            and all(type(report[key]) is int for key in ("nativeCaptureQuietAfterMillis","nativeCaptureObservedMillis","lateCaptureCallbacks","expiredDeliveriesRejected"))
            and 1000<=report["nativeCaptureQuietAfterMillis"]<=2000
            and 500<=report["nativeCaptureObservedMillis"]<=1500
            and report["lateCaptureCallbacks"]==0
            and 0<=report["expiredDeliveriesRejected"]<=(1 if expected_expiry else 0))


def valid_emergency_stop(report, *, video=False):
    base={"failedClosed","nativeCaptureQuietAfterMillis","nativeCaptureObservedMillis","lateCaptureCallbacks","expiredDeliveriesRejected"}
    extra={"emergencyState","requestedNanos","invalidatedNanos","confirmedNanos","lateVideoCallbacks","lastAudioCaptureNanos","lastVideoCaptureNanos"}
    return (set(report)==base|extra
            and valid_stop({key:report[key] for key in base})
            and report["emergencyState"]=="CLOSED"
            and all(type(report[key]) is int for key in extra-{"emergencyState"})
            and report["lateVideoCallbacks"]==0
            and 0<report["requestedNanos"]<=report["invalidatedNanos"]<=report["confirmedNanos"]
            and report["confirmedNanos"]-report["requestedNanos"]<=5_000_000_000
            and 0<report["lastAudioCaptureNanos"]<=report["confirmedNanos"]
            and (0<report["lastVideoCaptureNanos"]<=report["confirmedNanos"] if video else report["lastVideoCaptureNanos"]==0))


def permission_granted(dump, name):
    values=re.findall(r'^\s*'+re.escape(name)+r': granted=(true|false)(?:,|$)',dump,re.MULTILINE)
    if len(values)!=1:raise ValueError("Missing or ambiguous runtime permission evidence")
    return values[0]=='true'

def valid_processing(value, expected, *, video=False):
    fields=("natural","modified","loud","settleMillis","observedMillis","videoFrames","step")
    if any(type(value.get(key)) is not int or value[key]<0 for key in fields):return False
    if not 1200<=value["settleMillis"]<=2500 or not 2000<=value["observedMillis"]<=3500:return False
    if video and value["videoFrames"]<5:return False
    metrics=value.get("metrics")
    if not isinstance(metrics,list) or len(metrics)!=34 or any(type(n) is not int or n<0 for n in metrics):return False
    if metrics[0]<100 or metrics[1]<100:return False
    if expected=="natural":return value["natural"]>=40 and value["modified"]<=3
    if expected=="modified":return value["modified"]>=40 and value["natural"]<=3
    if expected=="quiet":return max(value["natural"],value["modified"],value["loud"])<=3
    return False


def valid_report(report, *, private_lock=False):
    return ((not private_lock or "privateStartupLock=PASS old relay rejected; no reconnect" in report)
            and "engineVoice=PASS" in report and re.search(r"^OK \(3 tests\)$",report,re.M)
            and "INSTRUMENTATION_CODE: -1" in report
            and not re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b",report)
            and not any(x in report for x in ("FAILURES!!!","INSTRUMENTATION_FAILED","Process crashed")))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--a",required=True); parser.add_argument("--b",required=True)
    parser.add_argument("--reports",type=Path,required=True)
    parser.add_argument("--turn-tls",choices=("valid","wrong-name","expired","untrusted"),help="Use only the isolated TURN/TLS endpoint; test CA stays in instrumentation")
    parser.add_argument("--turn-ipv6",action="store_true",help="IPv6 AVD-to-TURN leg with the native allocator's IPv4 relay allocation")
    parser.add_argument("--modulated-start",action="store_true",help="Select MODULATED before opening capture; reject any initial remote natural-tone block")
    parser.add_argument("--modulation",action="store_true",help="Prove remote local-voice modulation with real native capture/Opus, never a preview")
    parser.add_argument("--video",action="store_true",help="Require decoded remote synthetic images, video off with audio, and freshly consented reactivation")
    parser.add_argument("--optimized",action="store_true",help="Run the isolated non-debuggable R8 mediaLab APK on rooted AOSP AVDs")
    parser.add_argument("--scenario",choices=("audio","expired-auth","allocation-expiry","invalid-auth","unreachable","turn-loss","trust-loss","lock","emergency-lock","credential-expiry","direct-blocked","force-stop","permission-revoked","device-revoked","storage-failure","unauthorized-redirect","wrong-fingerprint","receive-only","degraded-network","camera-denied","camera-permission-revoked","video-stop-race"),default="audio")
    args=parser.parse_args()
    capture_owner_pid = int(os.environ.get("UMBRA_CAPTURE_OWNER_PID", "0"))
    if capture_owner_pid <= 1 or os.environ.get("UMBRA_FINALIZED_CAPTURE") != "1":
        raise RuntimeError("Media acceptance requires the explicit CI capture owner")
    os.kill(capture_owner_pid, 0)
    if (args.reports/"voice-evidence.json").exists():
        raise RuntimeError("Use a new media report directory; previous evidence must be preserved")
    if args.turn_ipv6 and args.scenario in ("unauthorized-redirect","unreachable"):
        parser.error("IPv6 redirect/unreachable packet evidence is not implemented")
    if (args.scenario.startswith("camera-") or args.scenario in ("receive-only","video-stop-race")) and not args.video: parser.error("Camera cases require the explicit video suite")
    tls_rejection=args.turn_tls in ("wrong-name","expired","untrusted")
    if args.modulated_start and not args.modulation:parser.error("Initial mode acceptance requires --modulation")
    if args.modulation and (args.scenario not in ("audio","lock","device-revoked") or args.turn_tls or args.turn_ipv6):parser.error("Modulation acceptance uses the isolated positive UDP topology")
    rejection=args.scenario in ("expired-auth","invalid-auth","unreachable","unauthorized-redirect","wrong-fingerprint") or tls_rejection
    admission_revocation_check=args.scenario=="audio" and not args.video and not args.modulation
    optimized_evidence=inspect_optimized_media() if args.optimized else None
    PACKAGE="app.umbra.privatechat.medialab" if args.optimized else "app.umbra.privatechat.dev"
    app_uids={}
    apk_hashes={}
    if args.a==args.b: raise ValueError("Two independent AVD serials required")
    adb=str(Path(os.environ["ANDROID_HOME"])/"platform-tools/adb")
    def command_for(serial,command):
        if args.optimized and tuple(command[:3])==("shell","run-as",PACKAGE):
            # This exact synthetic UID only, after checking qemu and PackageManager.
            # Keep R8 in release mode; do not make the target debuggable for run-as.
            script="cd /data/user/0/"+PACKAGE+" && "+" ".join(command[3:])
            command=("shell","su",app_uids[serial],"sh","-c",shlex.quote(script))
        return [adb,"-s",serial,*command]
    def run(serial,*command,**kwargs):
        return subprocess.run(command_for(serial,command),check=True,capture_output=True,timeout=120,**kwargs)
    def write(serial,name,payload):
        run(serial,"shell","run-as",PACKAGE,"sh","-c",f"'umask 077; cat > files/{name}.tmp && mv files/{name}.tmp files/{name}'",input=json.dumps(payload).encode())
    def read(serial,name,process,deadline):
        while time.monotonic()<deadline:
            if process.poll() is not None: raise RuntimeError("Android voice fixture exited before exchange; inspect sanitized instrumentation report")
            result=subprocess.run(command_for(serial,("shell","run-as",PACKAGE,"cat","files/"+name)),capture_output=True,timeout=15)
            if result.returncode==0: return json.loads(result.stdout)
            if result.returncode!=1: raise RuntimeError("ADB voice exchange failed")
            time.sleep(0.1)
        raise RuntimeError("Two-AVD voice exchange deadline exceeded: "+name)
    association_needed={}
    for serial in (args.a,args.b):
        if run(serial,"shell","getprop","ro.kernel.qemu").stdout.strip()!=b"1": raise RuntimeError("Only synthetic AVDs supported")
        run(serial,"shell","svc","power","stayon","true")
        run(serial,"shell","input","keyevent","KEYCODE_WAKEUP")
        # Disposable owned AVD only: prevent OS connectivity probes/private DNS
        # from contaminating the packet-level no-direct-media assertion. This
        # changes neither the APK nor its TLS trust or ICE policy.
        run(serial,"shell","settings","put","global","private_dns_mode","off")
        run(serial,"shell","settings","put","global","captive_portal_mode","0")
        # One declared lab network: owned netsim Wi-Fi. Disable the AVD's separate
        # virtual cellular uplink so captures cover the entire enabled topology.
        observe_owned_network(adb,serial,args.reports/f'network-before-data-disable-{serial}.json')
        run(serial,"shell","svc","data","disable")
        observe_owned_network(adb,serial,args.reports/f'network-after-data-disable-{serial}.json')
        association_needed[serial]=initialize_owned_wifi(adb,serial,args.reports/f'network-wifi-initialization-{serial}.json')
        observe_owned_network(adb,serial,args.reports/f'network-after-wifi-enable-{serial}.json')
        variant="mediaLab" if args.optimized else "debug"
        for path in (f"connected/{variant}/app-connected-{variant}.apk",f"androidTest/connected/{variant}/app-connected-{variant}-androidTest.apk"):
            apk=ROOT/"android/app/build/outputs/apk"/path
            with apk.open("rb") as stream:digest=hashlib.file_digest(stream,"sha256").hexdigest()
            if path in apk_hashes and apk_hashes[path]!=digest:raise RuntimeError("APK changed between endpoint installations")
            apk_hashes[path]=digest
            ensure_apk(adb,serial,PACKAGE+(".test" if path.startswith("androidTest/") else ""),apk)
        if args.optimized:
            listed=run(serial,"shell","cmd","package","list","packages","-U",PACKAGE).stdout.decode().splitlines()
            matches=[re.fullmatch(r"package:"+re.escape(PACKAGE)+r" uid:(\d+)",line) for line in listed]
            uids=[match[1] for match in matches if match]
            if len(uids)!=1 or not 10000<=int(uids[0])<=19999: raise RuntimeError("Unknown isolated mediaLab UID")
            app_uids[serial]=uids[0]
        run(serial,"shell","run-as",PACKAGE,"mkdir","-p","files")
        reset_exchange(run,serial,PACKAGE)
        if args.scenario in ("permission-revoked","camera-permission-revoked"):
            run(serial,"shell","pm","grant",PACKAGE,"android.permission.CAMERA" if args.scenario=="camera-permission-revoked" else "android.permission.RECORD_AUDIO")
        if args.scenario=="camera-denied":run(serial,"shell","pm","revoke",PACKAGE,"android.permission.CAMERA")
        # Explicit names only, confined to this disposable debug UID.
        run(serial,"shell","run-as",PACKAGE,"rm","-f",*[f"files/synthetic-voice-{prefix}{index}.json" for index in range(10) for prefix in ("processing-","processing-result-","processing-applied-","processing-observe-")])
        for suffix in ("admission-ready","admission-change","admission-result","public","peer","ready","start","turn-ready","turn","audio","mute","mute-applied","mute-observe","muted","resume","resumed","loss","lost","stop","video-start","video-active","video-off","video-stopped","video-resume","video-resumed","camera-denied","initial-processing","initial-natural","processing-diagnostic"):
            run(serial,"shell","run-as",PACKAGE,"rm","-f",f"files/synthetic-voice-{suffix}.json")
    args.reports.mkdir(parents=True,exist_ok=True)
    if optimized_evidence:
        (args.reports/"optimized-apk.json").write_text(json.dumps(optimized_evidence,indent=2)+"\n")
    probe_number=0
    def direct_probe(sender,receiver,address):
        nonlocal probe_number
        probe_number+=1
        evidence={'sender':sender,'receiver':receiver,'destination':address,'synthetic':True}
        def topology():
            result={}
            for serial in (sender,receiver):
                result[serial]={}
                for label,command in (
                    ('addresses',('ip','addr','show')),
                    ('rules',('ip','rule','show')),
                    ('routes',('ip','route','show','table','all')),
                    ('udp',('cat','/proc/net/udp'))):
                    captured=subprocess.run([adb,'-s',serial,'shell',*command],capture_output=True,text=True,timeout=5)
                    result[serial][label]={'exit':captured.returncode,'stdout':captured.stdout[:16384],'stderr':captured.stderr[:1024]}
            return result
        try:
            evidence['before']=topology()
            proved=probe_udp(adb,sender,receiver,address,evidence)
            evidence['delivered']=proved
            return proved
        finally:
            try: evidence['after']=topology()
            finally: (args.reports/f'direct-route-{probe_number}.json').write_text(json.dumps(evidence,indent=2)+'\n')
    processes={}; streams=[]
    with voice_relay(args.reports/"https-lifecycle.json") as relay, TurnLab(alternate_port=3479 if args.scenario=="unauthorized-redirect" else None, allocation_lifetime=180,tls_mode=args.turn_tls,ipv6=args.turn_ipv6) as turn:
        capture_paths=[]; blocked_routes=[]; shaped=[]; shape_evidence=[]; allocation_evidence=None
        try:
            addresses=[];addresses6=[]
            for serial in (args.a,args.b):
                response=run(serial,"emu","avd","path").stdout.decode()
                if "KO:" in response: raise RuntimeError("AVD path lookup failed")
                avd_path=Path(response.splitlines()[0])
                root=avd_path.parent.parent/"umbra-netsim-private"
                candidates=list(root.glob("android*/netsimd/pcaps/*-"+avd_path.stem+"-WIFI.pcap"))
                if len(candidates)!=1: raise RuntimeError("Owned netsim Wi-Fi capture missing; start with ci_emulator.sh")
                capture_paths.append(candidates[0])
                address=wait_wifi_ipv4(adb,serial,args.reports/f'wifi-ready-{serial}.json',associate=association_needed[serial])
                addresses.append(str(ipaddress.ip_address(address)))
                if args.turn_ipv6:
                    value=run(serial,"shell","ip","-6","addr","show","wlan0").stdout.decode()
                    found=[ipaddress.ip_address(item) for item in re.findall(r"inet6 ([0-9a-f:]+)/",value)]
                    found=[value for value in found if not value.is_link_local and not value.is_loopback]
                    if not 1<=len(found)<=8: raise RuntimeError("Expected bounded owned AVD IPv6 Wi-Fi addresses")
                    addresses6.append([str(value) for value in found])
            if addresses[0]==addresses[1]: raise RuntimeError("Expected independent AVD Wi-Fi addresses")
            for index,serial in enumerate((args.a,args.b)):
                if not direct_probe(serial,(args.a,args.b)[1-index],addresses[1-index]):
                    raise RuntimeError("Direct IPv4 UDP route between owned AVDs unavailable")
            capture_since=time.time()
            if args.scenario=="direct-blocked":
                for index,serial in enumerate((args.a,args.b)):
                    peer=addresses[1-index]
                    run(serial,"shell","su","0","iptables","-I","OUTPUT","-d",peer,"-m","comment","--comment","umbra-private-voice-test","-j","REJECT")
                    blocked_routes.append((serial,peer))
                    if direct_probe(serial,(args.a,args.b)[1-index],peer):
                        raise RuntimeError("Direct UDP route blocking was not demonstrated")
            clients_started=time.monotonic()
            def issue_turn():
                credentials=turn.credentials((60 if args.video else 30) if args.scenario=="credential-expiry" else 180)
                if args.scenario=="expired-auth":
                    credentials=turn.credentials(1)
                    await_expired_turn_timestamp(credentials["expires"])
                    # Deliberately inconsistent local metadata tests SERVER rejection,
                    # not a bypass in the productive credential/configuration provider.
                    credentials["expires"]=int(time.time())+180
                if args.scenario=="invalid-auth": credentials["password"]="synthetic-invalid-credential"
                if args.scenario=="unreachable": credentials["urls"]=[value.replace(":5349",":5348") if args.turn_tls else value.replace(":3478",":3479") for value in credentials["urls"]]
                return credentials
            for index,serial in enumerate((args.a,args.b)):
                write(serial,"synthetic-voice-engine.json",{"stopVideoRace":args.scenario=="video-stop-race","initialModulation":args.modulated_start,"modulation":args.modulation,"video":args.video and args.scenario!="camera-denied","cameraDenied":args.scenario=="camera-denied","receiveOnlyCallee":args.scenario=="receive-only","incorrectFingerprint":args.scenario=="wrong-fingerprint","expectedRejection":rejection,"role":"A" if index==0 else "B","base":relay["base"],
                    "admissionRevocationCheck":admission_revocation_check,"admissionRealm":relay["admission"].realm.encode(),"certificate":relay["certificate"],"invitation":relay["invitations"][index]})
                stream=(args.reports/("engine-voice-a.log" if index==0 else "engine-voice-b.log")).open("w")
                streams.append(stream)
                processes[serial]=subprocess.Popen([adb,"-s",serial,"shell","am","instrument","-w","-r",
                    "-e","class","app.umbra.DeviceSignalTest","-e","listener","app.umbra.media.VoiceEngineFixtureListener",
                    PACKAGE+".test/androidx.test.runner.AndroidJUnitRunner"],stdout=stream,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+(160 if args.modulation else 140 if args.video else 90)
            approvals={}
            for serial in (args.a,args.b):
                request=read(serial,"synthetic-admission-request.json",processes[serial],deadline)
                approvals[serial]=relay["admission"].approve(request["request"])
                write(serial,"synthetic-admission-credential.json",approvals[serial])
            a=read(args.a,"synthetic-voice-public.json",processes[args.a],deadline)
            b=read(args.b,"synthetic-voice-public.json",processes[args.b],deadline)
            write(args.a,"synthetic-voice-peer.json",b); write(args.b,"synthetic-voice-peer.json",a)
            for serial in (args.a,args.b):
                if read(serial,"synthetic-voice-ready.json",processes[serial],deadline)!={"ready":True}: raise RuntimeError("Identity preparation failed")
            for serial in (args.a,args.b): write(serial,"synthetic-voice-start.json",{"consent":True})
            issue_turn_after_selection((args.a,args.b),processes,deadline,write,read,issue_turn)
            initial_processing=[]
            if args.modulated_start:
                initial_processing=[read(serial,"synthetic-voice-initial-processing.json",processes[serial],deadline) for serial in (args.a,args.b)]
                if initial_processing[0].get("effective")!="ON" or initial_processing[0].get("natural",0)<100 or initial_processing[1].get("natural")!=0 or initial_processing[1].get("modified",0)<100:raise RuntimeError("Initial modulation leaked or lacked decoded transformed audio")
                for serial in (args.a,args.b):write(serial,"synthetic-voice-initial-natural.json",{"naturalVoiceExplicitlyConfirmed":True})
            evidence=[]
            stop_evidence=[]
            mute_evidence=None
            for serial in (args.a,args.b):
                value=read(serial,"synthetic-voice-audio.json",processes[serial],deadline)
                expected_rejection={"rejectedBeforeCapture":True}
                if args.scenario=="wrong-fingerprint":
                    if value.get("reason") not in ("native-certificate-binding","native-connection-failed","native-connection-disconnected","authorization-cancelled"):raise RuntimeError("Missing certificate rejection or native peer closure")
                    expected_rejection["reason"]=value["reason"]
                if not (value==expected_rejection if rejection else valid_audio(value)):
                    raise RuntimeError("Missing native decoded audio evidence")
                if args.turn_tls and not rejection and value.get("nativeRelayProtocol")!="tls":
                    raise RuntimeError("Native selected candidate is not TURN/TLS")
                evidence.append(value)
            if args.scenario=="wrong-fingerprint" and not any(item.get("reason")=="native-certificate-binding" for item in evidence):
                raise RuntimeError("Neither endpoint demonstrated native certificate binding rejection")
            if args.scenario=="camera-denied":
                for serial in (args.a,args.b):
                    denial=read(serial,"synthetic-voice-camera-denied.json",processes[serial],deadline)
                    if denial.get("deniedWithoutVideoState") is not True or denial.get("decodedAudioAfterDenial",0)<50:
                        raise RuntimeError("Camera denial did not preserve authorized audio")
            if not rejection:
                mute_evidence=coordinate_mute((args.a,args.b),processes,deadline,write,read)
                for serial in (args.a,args.b): write(serial,"synthetic-voice-resume.json",{"resume":True})
                for serial in (args.a,args.b):
                    if read(serial,"synthetic-voice-resumed.json",processes[serial],deadline).get("decodedAfterUnmute",0)<50:
                        raise RuntimeError("Native audio did not resume after unmute")
            if args.scenario=="degraded-network":
                for serial in (args.a,args.b):
                    run(serial,"shell","su","0","tc","qdisc","add","dev","wlan0","root","netem","limit","20","delay","80ms","loss","2%","rate","128kbit")
                    shaped.append(serial)
                    actual=run(serial,"shell","su","0","tc","qdisc","show","dev","wlan0").stdout.decode()
                    if not valid_impairment(actual):
                        raise RuntimeError("Owned AVD network impairment was not installed")
            if args.video and not rejection and args.scenario!="camera-denied":
                video_evidence={}
                for command,result in (("start","active"),("off","stopped"),("resume","resumed")):
                    for serial in (args.a,args.b): write(serial,"synthetic-voice-video-"+command+".json",{"consentedSyntheticOwnerAction":True})
                    values=[]
                    for serial in (args.a,args.b):
                        value=read(serial,"synthetic-voice-video-"+result+".json",processes[serial],deadline)
                        if result=="stopped":
                            if value.get("captureStopped") is not True or value.get("decodedAudioAfterVideoOff",0)<50:
                                raise RuntimeError("Camera off did not preserve audio or stop source")
                        elif (value.get("decodedRemotePatterns")!=0 or value.get("distinctPatternPhases")!=0 if args.scenario=="receive-only" and serial==args.a else value.get("decodedRemotePatterns",0)<20 or value.get("distinctPatternPhases")!=2) or value.get("decodedAudioDuringVideo",0)<50 or value.get("videoCodec") not in ("video/VP8","video/VP9","video/AV1") or value.get("sdpAddressAudit") is not True or value.get("generation")!=(2 if result=="active" else 3):
                            raise RuntimeError("Missing decoded remote video/audio evidence")
                        if args.scenario=="receive-only" and result!="stopped":
                            if value.get("sendPermitted")!=(serial==args.a) or value.get("receivePermitted")!=(serial==args.b):raise RuntimeError("Video consent directions diverged")
                            if (serial==args.b and value.get("capturedFrames")!=0) or (serial==args.a and value.get("capturedFrames",0)<20):raise RuntimeError("Receive-only camera isolation failed")
                        values.append(value)
                    video_evidence[result]=values
                    # Preserve validated earlier phases even if a later stop/resume fails.
                    (args.reports/"video-progress.json").write_text(json.dumps({"complete":result=="resumed","phases":video_evidence},indent=2)+"\n")
                (args.reports/"video-evidence.json").write_text(json.dumps({"apkSha256":apk_hashes,"synthetic":True,"physicalCamera":False,"nativeCodecPipeline":True,"endpoints":2,"phases":video_evidence},indent=2)+"\n")
            if args.modulation:
                processing=[]
                steps=(("on","modified","ON"),("mute","quiet",None),("off","quiet","DISABLING"),
                       ("on","quiet","ENABLING"),("unmute","modified","ON"),("unconfirmed-off","modified","ON"),
                       ("off","natural","OFF"),("on","modified","ON"),("fault","quiet","ERROR_MUTED"),("retry","modified","ON"))
                for index,(action,expected,effective) in enumerate(steps):
                    for serial in (args.a,args.b):write(serial,f"synthetic-voice-processing-{index}.json",{"action":action,"expected":expected})
                    applied=processing_barrier((args.a,args.b),processes,deadline,index,write,read)
                    results=[read(serial,f"synthetic-voice-processing-result-{index}.json",processes[serial],deadline) for serial in (args.a,args.b)]
                    if not valid_processing(results[0],"natural",video=args.video) or not valid_processing(results[1],expected,video=args.video):raise RuntimeError("Missing bounded decoded modulation evidence")
                    if any(value.get("step")!=index for value in results):raise RuntimeError("Replayed processing evidence")
                    if effective is not None and results[0].get("effective")!=effective:raise RuntimeError("Native processor did not confirm expected effective mode")
                    if results[1].get("effective")!="OFF":raise RuntimeError("Remote control changed independent peer processing")
                    processing.append({"action":action,"expectedRemote":expected,"applied":applied,"results":results})
                (args.reports/"voice-processing.json").write_text(json.dumps({"apkSha256":apk_hashes,"synthetic":True,"optimized":args.optimized,"video":args.video,"initialProcessing":initial_processing,"stages":processing},indent=2)+"\n")
            if args.scenario in ("turn-loss","trust-loss","lock","emergency-lock","credential-expiry","device-revoked","storage-failure"):
                for serial in (args.a,args.b): write(serial,"synthetic-voice-loss.json",{"action":args.scenario})
                if args.scenario=="turn-loss":
                    docker("stop","--time","0",turn.name)
                for serial in (args.a,args.b):
                    stopped=read(serial,"synthetic-voice-lost.json",processes[serial],deadline)
                    (args.reports/f"closure-observation-{serial}.json").write_text(json.dumps(stopped,indent=2)+"\n")
                    if args.scenario=="emergency-lock":
                        if not valid_emergency_stop(stopped,video=args.video):
                            raise RuntimeError("Emergency authorization/closure receipt missing or outside budget")
                    elif not valid_stop(stopped,expected_expiry=args.scenario=="credential-expiry"):
                        raise RuntimeError("Native media did not stop for scenario: "+args.scenario)
                    stop_evidence.append(stopped)
            if args.scenario in ("allocation-expiry","force-stop","permission-revoked","camera-permission-revoked"):
                if args.scenario=="allocation-expiry":
                    allocated=turn.allocation_count()
                    if allocated<2: raise RuntimeError("Two real TURN allocations were not observed before process death")
                    allocation_evidence={"maxLifetimeSeconds":180,"allocationsBeforeDeath":allocated}
                for index,serial in enumerate((args.a,args.b)):
                    pid=run(serial,"shell","pidof",PACKAGE).stdout.decode().strip()
                    if not re.fullmatch(r"[0-9]+",pid): raise RuntimeError("Native audio process missing before planned termination")
                    permission="android.permission.CAMERA" if args.scenario=="camera-permission-revoked" else "android.permission.RECORD_AUDIO"
                    revoking=args.scenario in ("permission-revoked","camera-permission-revoked")
                    if revoking and not permission_granted(run(serial,"shell","dumpsys","package",PACKAGE).stdout.decode(),permission):
                        raise RuntimeError("Permission was not granted before planned revocation")
                    requested=time.monotonic_ns()
                    if not revoking: run(serial,"shell","am","force-stop",PACKAGE)
                    else:
                        run(serial,"shell","pm","revoke",PACKAGE,permission)
                        if permission_granted(run(serial,"shell","dumpsys","package",PACKAGE).stdout.decode(),permission):
                            raise RuntimeError("Permission remained granted after revocation")
                    gone=time.monotonic()+10
                    while True:
                        probe=subprocess.run([adb,"-s",serial,"shell","pidof",PACKAGE],capture_output=True,timeout=5)
                        if probe.returncode==1 and not probe.stdout.strip(): break
                        if time.monotonic()>=gone: raise RuntimeError("Media process survived planned termination")
                        time.sleep(0.1)
                    exit_code=processes[serial].wait(timeout=15)
                    (args.reports/f"termination-{index}.json").write_text(json.dumps({"scenario":args.scenario,
                        "requestedMonotonicNanos":requested,"observedGoneMonotonicNanos":time.monotonic_ns(),
                        "permissionBefore":True if revoking else None,"permissionAfter":False if revoking else None,
                        "processAbsent":True,"instrumentationExitCode":exit_code},indent=2)+"\n")
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
                if admission_revocation_check:
                    for serial in (args.a,args.b):
                        if read(serial,"synthetic-voice-admission-ready.json",processes[serial],time.monotonic()+20)!={"ready":True}:
                            raise RuntimeError("Android admission revocation barrier missing")
                    write(args.a,"synthetic-voice-admission-change.json",relay["admission"].revoke(approvals[args.a]["credential"]))
                    write(args.b,"synthetic-voice-admission-change.json",{"unaffected":True})
                for serial in (args.a,args.b):
                    if processes[serial].wait(timeout=30)!=0: raise RuntimeError("Voice instrumentation process failed")
                for stream in streams: stream.flush()
                for name in ("engine-voice-a.log","engine-voice-b.log"):
                    report=(args.reports/name).read_text()
                    if not valid_report(report,private_lock=args.scenario in ("lock","emergency-lock")):
                        raise RuntimeError("Missing/failed authenticated voice evidence: "+name)
            if admission_revocation_check:
                admission_results=[json.loads(run(serial,"shell","run-as",PACKAGE,"cat","files/synthetic-voice-admission-result.json").stdout) for serial in (args.a,args.b)]
                if admission_results!=[{"relayDeniedBeforeLocalSync":True,"state":"REVOKED"},{"relayDeniedBeforeLocalSync":False,"state":"ADMITTED"}]:
                    raise RuntimeError("Android independent relay revocation evidence missing")
                (args.reports/"admission-revocation.json").write_text(json.dumps(admission_results,indent=2)+"\n")
            if allocation_evidence is not None:
                elapsed=time.monotonic()-clients_started
                if elapsed>=90:raise RuntimeError("Expiry fixture exceeded the pre-refresh window; do not infer a 180-second allocation")
                allocation_evidence["clientsTerminatedBeforeSeconds"]=round(elapsed,3)
                remaining=turn.allocation_count(); began=time.monotonic()
                allocation_evidence["allocationsAfterProcessRecovery"]=remaining
                while turn.allocation_count()!=0:
                    if time.monotonic()-began>=190: raise RuntimeError("TURN allocations survived their bounded lifetime without clients")
                    time.sleep(0.5)
                allocation_evidence.update({"allocationsAfterExpiry":0,"observedWaitSeconds":round(time.monotonic()-began,3)})
            if shaped:
                for serial in shaped:
                    counters=run(serial,"shell","su","0","tc","-s","qdisc","show","dev","wlan0").stdout.decode()
                    values=re.search(r"Sent (\d+) bytes (\d+) pkt \(dropped (\d+)",counters)
                    if not values or int(values[2])<1 or int(values[3])<1:raise RuntimeError("Configured impairment did not demonstrate processed packets and loss")
                    shape_evidence.append({"delayMillis":80,"lossPercent":2,"rateKbit":128,"queuePacketLimit":20,"processedPackets":int(values[2]),"droppedPackets":int(values[3])})
            capture_until=time.time()
            if args.scenario=="turn-loss":
                for index,serial in enumerate((args.a,args.b)):
                    if not direct_probe(serial,(args.a,args.b)[1-index],addresses[1-index]):
                        raise RuntimeError("Direct UDP route unavailable after TURN loss")
            from urllib.parse import urlparse
            voice = {"apkSha256":apk_hashes,"synthetic":True,"endpoints":2,"observedSeconds":round(capture_until-capture_since,3),"transport":"native WebRTC through coturn TLS" if args.turn_tls else "native WebRTC through coturn UDP","turnTlsCase":args.turn_tls,"scenario":args.scenario,"directIpv4Reachability":True,"directBlockedDuringMedia":args.scenario=="direct-blocked","muteUnmute":not rejection,"muteBarrier":mute_evidence,"network":[],"audio":evidence,"allocationExpiry":allocation_evidence,"nativeCaptureClosure":stop_evidence,"networkImpairment":shape_evidence}
            staged = {"status": "PENDING_CAPTURE_FINALIZATION", "ownerPid": capture_owner_pid,
                "capturePaths": [str(path.resolve()) for path in capture_paths],
                "since": capture_since, "until": capture_until,
                "scenario": args.scenario, "turnAddress": turn.address, "tls": bool(args.turn_tls),
                "ipv6": args.turn_ipv6, "relayPort": urlparse(relay["base"]).port,
                "addresses": addresses, "addresses6": addresses6, "rejection": rejection, "voice": voice}
            (args.reports/"network-pending.json").write_text(json.dumps(staged,indent=2)+"\n")
            print("PENDING_CAPTURE_FINALIZATION: native scenario completed, network not yet accepted")

        finally:
            errors=[]
            for serial in shaped:
                try: run(serial,"shell","su","0","tc","qdisc","del","dev","wlan0","root","netem")
                except Exception as failure: errors.append(failure)
            for serial,peer in blocked_routes:
                try: run(serial,"shell","su","0","iptables","-D","OUTPUT","-d",peer,"-m","comment","--comment","umbra-private-voice-test","-j","REJECT")
                except Exception as failure: errors.append(failure)
            for serial,process in processes.items():
                # Observe crash metadata before fixture cleanup. Never persist raw logcat,
                # SDP, abort messages, memory dumps or events belonging to another package.
                try:
                    from media_crash_diagnostic import summarize as summarize_crash
                    crash=run(serial,"logcat","-b","crash","-d","-t","128").stdout.decode("utf-8",errors="replace")
                    (args.reports/("crash-metadata-"+serial+".json")).write_text(json.dumps(summarize_crash(crash,PACKAGE),indent=2)+"\n")
                except Exception as failure: errors.append(failure)
                try: run(serial,"shell","am","force-stop",PACKAGE)
                except Exception as failure: errors.append(failure)
                if args.modulation:
                    try:
                        diagnostic=subprocess.run(command_for(serial,("shell","run-as",PACKAGE,"cat","files/synthetic-voice-processing-diagnostic.json")),capture_output=True,timeout=15)
                        if diagnostic.returncode==0:
                            detail=json.loads(diagnostic.stdout)
                            if not isinstance(detail,dict) or not set(detail)<= {"state","effective","step","failureStage","faults","maxBlockNanos","processed","processorDisposed"}:raise RuntimeError("Unexpected processing diagnostic")
                            (args.reports/("processing-diagnostic-"+serial+".json")).write_text(json.dumps(detail,indent=2)+"\n")
                    except Exception as failure: errors.append(failure)
                try:
                    if process.poll() is None: process.terminate()
                    process.wait(timeout=15)
                except Exception as failure: errors.append(failure)
                try: run(serial,"shell","run-as",PACKAGE,"rm","-f",*[f"files/synthetic-voice-{prefix}{index}.json" for index in range(10) for prefix in ("processing-","processing-result-","processing-applied-","processing-observe-")])
                except Exception as failure: errors.append(failure)
                for suffix in ("engine","admission-ready","admission-change","admission-result","public","peer","ready","start","turn-ready","turn","audio","mute","mute-applied","mute-observe","muted","resume","resumed","loss","lost","stop","video-start","video-active","video-off","video-stopped","video-resume","video-resumed","camera-denied","initial-processing","initial-natural","processing-diagnostic"):
                    try: run(serial,"shell","run-as",PACKAGE,"rm","-f",f"files/synthetic-voice-{suffix}.json")
                    except Exception as failure: errors.append(failure)
                # These exact files belong solely to this named synthetic fixture, including failed runs.
                for suffix in ("","-journal","-wal","-shm"):
                    try: run(serial,"shell","run-as",PACKAGE,"rm","-f","cache/synthetic-device-membership-lab/synthetic-device-voice-restart.db"+suffix)
                    except Exception as failure: errors.append(failure)
            for stream in streams: stream.close()
            if errors: raise RuntimeError("Voice lab cleanup failed; all endpoints were attempted") from errors[0]
    return args.reports/"network-pending.json"


def require_completed_run(result):
    """A zero-exception early return is not multimedia acceptance evidence."""
    if not isinstance(result,Path) or not result.is_file():
        raise RuntimeError("Media driver ended without a completed evidence receipt")
    report=json.loads(result.read_text())
    if (report.get('synthetic') is not True or report.get('endpoints')!=2
            or not isinstance(report.get('observedSeconds'),(int,float)) or report['observedSeconds']<=0
            or len(report.get('audio',[]))!=2 or len(report.get('network',[]))!=2
            or not all(isinstance(row,dict) and row for row in report['network'])
            or not all(valid_audio(row) or row.get('rejectedBeforeCapture') is True for row in report['audio'])):
        raise RuntimeError("Incomplete multimedia acceptance receipt")


def require_staged_run(result):
    if not isinstance(result, Path) or not result.is_file():
        raise RuntimeError("Native staging ended without its pending receipt")
    report = json.loads(result.read_text())
    if (report.get('status') != 'PENDING_CAPTURE_FINALIZATION'
            or report.get('ownerPid') != int(os.environ.get('UMBRA_CAPTURE_OWNER_PID', '0'))
            or len(report.get('capturePaths', [])) != 2
            or report.get('voice', {}).get('network') != []):
        raise RuntimeError("Invalid native staging receipt")


if __name__=="__main__": require_staged_run(main())
