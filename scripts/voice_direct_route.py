"""Owned-AVD UDP reachability probe, never Bluetooth or native media evidence.

Run outside the multimedia packet observation window. A random challenge must
arrive at a confirmed listening socket; ICMP availability is not assumed.
"""
import ipaddress
import os
import select
import shlex
import re
import secrets
import subprocess
import time


def wifi_control_summary(text):
    """Keep service state tokens, never saved SSIDs, passphrases or packet dumps."""
    fields = re.findall(
        r"\b(curState|mWifiState|mWifiEnabled|mWifiToggleEnabled|mNetworkSelectionStatus|"
        r"networkSelectionStatus|mNetworkSelectionDisableReason|mIsWifiEnabled|"
        r"mIsInterfaceUp|mIsStopped|mTargetRole|mRole)\s*[:=]\s*([A-Za-z0-9_]+)", text)
    fields += re.findall(r'\b(NetworkSelectionStatus|NetworkSelectionDisableReason)\s*[:=]?\s+(NETWORK_SELECTION_[A-Z_]+)',text)
    return [{'field': key, 'value': value[:80]} for key, value in fields[:128]]


def observe_owned_wifi(adb,serial,report=None):
    """Read-only Wi-Fi service evidence, solely for the owned disposable AVD."""
    if not serial.startswith('emulator-') or not serial[9:].isdigit():
        raise ValueError('Owned emulator required')
    qemu=subprocess.run([adb,'-s',serial,'shell','getprop','ro.kernel.qemu'],
                        capture_output=True,text=True,timeout=3)
    if qemu.returncode or qemu.stdout.strip()!='1':
        raise ValueError('Disposable emulator required')
    import json
    evidence={'observedNanos':time.monotonic_ns()}
    for name,command in (('status',('cmd','wifi','status')),('state',('dumpsys','wifi'))):
        try:
            result=subprocess.run([adb,'-s',serial,'shell',*command],
                                  capture_output=True,text=True,timeout=3)
            entry={'exit':result.returncode}
            if name=='status':
                entry.update(enabled=result.stdout.startswith('Wifi is enabled'),
                             disabled=result.stdout.startswith('Wifi is disabled'),
                             disconnected='Wifi is not connected' in result.stdout)
            else:
                entry['fields']=wifi_control_summary(result.stdout)
            evidence[name]=entry
        except subprocess.TimeoutExpired:
            evidence[name]={'error':'diagnostic_timeout'}
    if report is not None:report.write_text(json.dumps(evidence,indent=2)+'\n')
    return evidence


def observe_owned_network(adb: str, serial: str, report):
    """Read-only control-plane evidence around setup; never an acceptance result.

    Only disposable AVDs are supported. No app logs, packet payloads, credentials,
    DNS probes or route mutation. Each command is independently bounded.
    """
    if not serial.startswith('emulator-') or not serial[9:].isdigit():
        raise ValueError('Owned emulator required')
    import json
    evidence = {'observedNanos': time.monotonic_ns(), 'commands': {}}
    for label, arguments in (
        ('qemu', ('getprop', 'ro.kernel.qemu')),
        ('route', ('ip', '-4', 'route', 'get', '10.0.2.2')),
        ('routes', ('ip', '-4', 'route', 'show', 'table', 'all')),
        ('rules', ('ip', '-4', 'rule', 'show')),
        ('addresses', ('ip', '-4', 'addr', 'show'))):
        try:
            result = subprocess.run([adb, '-s', serial, 'shell', *arguments],
                                    capture_output=True, text=True, timeout=3)
            evidence['commands'][label] = {'exit': result.returncode,
                'stdout': result.stdout[:16384], 'stderr': result.stderr[:1024]}
            if label == 'qemu' and (result.returncode != 0 or result.stdout.strip() != '1'):
                raise ValueError('Selected target is not a disposable emulator')
        except subprocess.TimeoutExpired:
            evidence['commands'][label] = {'error': 'diagnostic_timeout'}
            if label == 'qemu':
                raise ValueError('Could not verify disposable emulator')
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(evidence, indent=2)+'\n')


def select_owned_wifi(adb, serial, report):
    """Explicitly associate to the disposable emulator's fixed virtual AP.

    Enabling a radio does not select a saved network. This is host laboratory
    setup, not application connectivity consent or proof of route readiness.
    Runtime help must support the API; never select a physical/supplied SSID.
    """
    import json
    if not serial.startswith('emulator-') or not serial[9:].isdigit():
        raise ValueError('Owned emulator required')
    def command(*args):
        return subprocess.run([adb, '-s', serial, 'shell', *args],
                              capture_output=True, text=True, timeout=3)
    qemu = command('getprop', 'ro.kernel.qemu')
    if qemu.returncode or qemu.stdout.strip() != '1':
        raise ValueError('Disposable emulator required')
    receipt = {'purpose': 'owned virtual AP selection, not network acceptance'}
    try:
        # API 35 exposes this administrative command to root on AOSP test images.
        # Match the existing owned-AVD firewall context, never root a phone.
        help_result = command('su', '0', 'cmd', 'wifi', 'help')
        # Android BasicShellCommandHandler prints help then returns -1 (ADB 255).
        # Only this read-only help command may use that documented status.
        receipt['helpExit'] = help_result.returncode
        receipt['helpSyntax'] = [line.strip()[:240] for line in help_result.stdout.splitlines()
                                 if line.strip().startswith('connect-network ')][:2]
        supported = help_result.returncode in (0,255) and not help_result.stderr.strip() and re.search(
            r'connect-network\s+<ssid>\s+open(?:\||\s)', help_result.stdout) is not None
        receipt['supported'] = supported
        if not supported:
            raise RuntimeError('Installed AVD Wi-Fi CLI does not support explicit open AP selection')
        state = command('cmd', 'wifi', 'status')
        if state.returncode or not state.stdout.startswith('Wifi is enabled'):
            raise RuntimeError('Owned AVD Wi-Fi radio is not enabled for selection')
        result = command('su', '0', 'cmd', 'wifi', 'connect-network', 'AndroidWifi', 'open')
        receipt['exit'] = result.returncode
        receipt['reportedFailure'] = 'fail' in (result.stdout + result.stderr).lower()
        if result.returncode or receipt['reportedFailure']:
            raise RuntimeError('Owned AVD virtual AP selection failed')
    finally:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(receipt, indent=2)+'\n')


def initialize_owned_wifi(adb: str, serial: str, report):
    """Preserve a routed association; initialize an inconsistent disposable AVD.

    A booted first AVD can retain a DHCP address without netd policy routes at
    media setup. `wifi enable` on that already-enabled agent is
    not initialization. Do not cycle a healthy association: this discards IPv6
    autoconfiguration used by later scenarios. Observe OFF before ON only when
    the IPv4 address/route is missing; never inject a route or retry a
    failed media scenario. OFF has a separate bounded 10s setup deadline. Existing
    wait_wifi_ipv4 still requires real routes within its unchanged 20s budget.
    """
    if not serial.startswith('emulator-') or not serial[9:].isdigit():
        raise ValueError('Owned emulator required')
    import json
    def command(*arguments):
        return subprocess.run([adb,'-s',serial,'shell',*arguments],
                              capture_output=True,text=True,timeout=3)
    verified=command('getprop','ro.kernel.qemu')
    if verified.returncode != 0 or verified.stdout.strip() != '1':
        raise ValueError('Selected target is not a disposable emulator')
    start=time.monotonic(); observations=[]; action='not-initialized'; before={}
    try:
        address=command('ip','-4','addr','show','wlan0')
        route=command('ip','-4','route','get','10.0.2.2')
        ipv6=command('ip','-6','addr','show','wlan0')
        for label,value in (('address',address),('route',route),('ipv6',ipv6)):
            before[label]={'exit':value.returncode,'stdout':value.stdout[:8192],'stderr':value.stderr[:512]}
        found=re.search(r'inet (10\.0\.2\.[0-9]+)/',address.stdout)
        if address.returncode==0 and route.returncode==0 and found and wifi_ipv4_route(route.stdout,found[1]):
            action='preserved-existing-route'
            return False
        action='initialize-missing-route'
        result=command('svc','wifi','disable')
        if result.returncode:raise RuntimeError('Owned AVD Wi-Fi disable failed')
        while True:
            state=command('ip','-4','addr','show','wlan0')
            absent=(state.returncode==1 and 'does not exist' in state.stderr)
            cleared=(state.returncode==0 and re.search(r'\binet\s',state.stdout) is None)
            observations.append({'elapsedMillis':round((time.monotonic()-start)*1000),
                'exit':state.returncode,'addressCleared':absent or cleared})
            if absent or cleared:break
            if time.monotonic()-start>=10:
                raise RuntimeError('Owned AVD Wi-Fi did not disconnect within setup budget')
            time.sleep(.2)
        result=command('svc','wifi','enable')
        if result.returncode:raise RuntimeError('Owned AVD Wi-Fi enable failed')
        return True  # Selection waits for enabled state within wait_wifi_ipv4's existing budget.
    finally:
        report.parent.mkdir(parents=True,exist_ok=True)
        report.write_text(json.dumps({'action':action,'before':before,'observations':observations,
            'purpose':'initialization, not media acceptance'},indent=2)+'\n')


def wifi_ipv4_route(text: str, source: str) -> bool:
    """An address alone is insufficient: netd may not have populated policy routes."""
    return (re.search(r'(?:^|\s)dev wlan0(?:\s|$)',text) is not None
            and re.search(r'(?:^|\s)src '+re.escape(source)+r'(?:\s|$)',text) is not None
            and not re.search(r'\b(?:unreachable|prohibit|blackhole)\b',text))


def wait_wifi_ipv4(adb: str, serial: str, reports, timeout=20, associate=False):
    """Observe netlink; optional owned-AP association shares the existing 20s budget.

    Default is read-only. Association is host fixture setup, never application
    network consent, and does not replace the subsequent UDP/media assertions.
    """
    if not serial.startswith('emulator-') or not serial[9:].isdigit():
        raise ValueError('Owned emulator required')
    import json
    start=time.monotonic();attempts=[];failure_state={}
    try:
        while True:
            address=subprocess.run([adb,'-s',serial,'shell','ip','-4','addr','show','wlan0'],capture_output=True,text=True,timeout=3)
            route=subprocess.run([adb,'-s',serial,'shell','ip','-4','route','get','10.0.2.2'],capture_output=True,text=True,timeout=3)
            found=re.search(r'inet (10\.0\.2\.[0-9]+)/',address.stdout)
            ready=address.returncode==0 and route.returncode==0 and found and wifi_ipv4_route(route.stdout,found[1])
            attempts.append({'elapsedMillis':round((time.monotonic()-start)*1000),
                'addressExit':address.returncode,'routeExit':route.returncode,
                'source':found[1] if found else None,'route':route.stdout[:1024],'error':route.stderr[:512]})
            if ready:return found[1]
            if time.monotonic()-start>=timeout:raise RuntimeError('Owned AVD Wi-Fi IPv4 policy route unavailable within readiness budget')
            if associate:
                state=subprocess.run([adb,'-s',serial,'shell','cmd','wifi','status'],capture_output=True,text=True,timeout=3)
                if state.returncode==0 and state.stdout.startswith('Wifi is enabled'):
                    select_owned_wifi(adb,serial,reports.with_name(reports.stem+'-selection.json'))
                    associate=False  # A single explicit selection, never a retry-until-green loop.
            time.sleep(.2)
    except (RuntimeError, subprocess.TimeoutExpired):
        # Owned synthetic AVD only. Capture control-plane state before cleanup;
        # never app logcat, packet payloads, SDP or TURN credentials.
        for label,command in (
                ('addresses',('ip','-4','addr','show')),
                ('rules',('ip','-4','rule','show')),
                ('routes',('ip','-4','route','show','table','all')),
                ('connectivity',('dumpsys','connectivity')),
                ('network_stack',('dumpsys','network_stack'))):
            try:
                captured=subprocess.run([adb,'-s',serial,'shell',*command],capture_output=True,text=True,timeout=5)
                failure_state[label]={'exit':captured.returncode,'stdout':captured.stdout[:65536],'stderr':captured.stderr[:1024]}
            except subprocess.TimeoutExpired:
                failure_state[label]={'error':'diagnostic_timeout'}
        try:
            failure_state['wifi_service']=observe_owned_wifi(adb,serial)
        except (ValueError, OSError, subprocess.TimeoutExpired):
            failure_state['wifi_service']={'error':'diagnostic_unavailable'}
        raise
    finally:
        reports.write_text(json.dumps({'serial':serial,'attempts':attempts,'failureState':failure_state},indent=2)+'\n')



def _listener_header(listener, deadline):
    """Read exactly two bounded lines; leave datagram bytes in the pipe."""
    lines=[]
    for limit in (32, 8):
        value=bytearray()
        while True:
            remaining=deadline-time.monotonic()
            if remaining<=0 or not select.select([listener.stdout],[],[],remaining)[0]:
                raise RuntimeError('Owned UDP listener header deadline exceeded')
            byte=os.read(listener.stdout.fileno(),1)
            if not byte:raise RuntimeError('Owned UDP listener exited before reservation')
            if byte==b'\n':break
            value.extend(byte)
            if len(value)>limit:raise RuntimeError('Malformed owned UDP listener header')
        lines.append(bytes(value))
    if not re.fullmatch(rb'UMBRA_PID=[1-9][0-9]{0,9}',lines[0]) or not re.fullmatch(rb'[1-9][0-9]{0,4}',lines[1]):
        raise RuntimeError('Malformed owned UDP listener reservation')
    pid=int(lines[0].split(b'=')[1]);port=int(lines[1])
    if pid<=1 or not 1024<=port<=65535:
        raise RuntimeError('Invalid owned UDP listener reservation')
    return pid,port


def _owned_listener_inode(table, ownership, port):
    """The reserved IPv4 socket must belong to the exact foreground nc PID."""
    command,separator,links=ownership.partition('\n')
    if not separator or command.split('\0') != ['toybox','nc','-4','-u','-l','-W','1','']:
        raise RuntimeError('Owned UDP listener process identity mismatch')
    inodes=set(re.findall(r'^socket:\[([1-9][0-9]*)\]$',links,re.MULTILINE))
    matches=[]
    for row in table.splitlines()[1:]:
        fields=row.split()
        if len(fields)>=10 and fields[1]==f'00000000:{port:04X}' and fields[2]=='00000000:0000' and fields[3]=='07':
            matches.append(fields[9])
    if len(matches)!=1 or matches[0] not in inodes:
        raise RuntimeError('Owned UDP listener socket identity mismatch')
    return int(matches[0])


def probe_udp(adb: str, sender: str, receiver: str, address: str, evidence: dict | None = None) -> bool:
    if not all(value.startswith('emulator-') and value[9:].isdigit() for value in (sender,receiver)) or sender==receiver:
        raise ValueError('Two owned emulator serials required')
    peer=ipaddress.ip_address(address)
    if peer.version!=4 or peer not in ipaddress.ip_network('10.0.2.0/24'):
        raise ValueError('Expected owned AVD Wi-Fi address')
    help_result=subprocess.run([adb,'-s',receiver,'shell','toybox','nc','--help'],
                               capture_output=True,text=True,timeout=3)
    help_text=help_result.stdout+help_result.stderr
    if help_result.returncode or not re.search(r'If no -p specified, -l prints the port it bound to',help_text):
        raise RuntimeError('Installed toybox lacks kernel-assigned listener port support')
    challenge=('umbra-owned-route-'+secrets.token_hex(16)).encode()
    # No positional COMMAND: Android toybox prints its atomically reserved port
    # and remains foreground. -p 0 is invalid; omitting -p selects kernel port 0.
    script='printf "UMBRA_PID=%s\\n" "$$"; exec toybox nc -4 -u -l -W 1'
    command=[adb,'-s',receiver,'shell','timeout','5','sh','-c',shlex.quote(script)]
    if evidence is not None: evidence['listenerCommand']=command
    listener=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        ready=time.monotonic()+2
        pid,port=_listener_header(listener,ready)
        if listener.poll() is not None:raise RuntimeError('Owned UDP listener exited before probe')
        table=subprocess.run([adb,'-s',receiver,'shell','cat','/proc/net/udp'],capture_output=True,text=True,check=True,timeout=3).stdout
        script=f'cat /proc/{pid}/cmdline && printf "\\n" && readlink /proc/{pid}/fd/*'
        ownership=subprocess.run([adb,'-s',receiver,'shell','sh','-c',shlex.quote(script)],
                                 capture_output=True,text=True,check=True,timeout=3).stdout
        inode=_owned_listener_inode(table,ownership,port)
        if time.monotonic()>ready or listener.poll() is not None:
            raise RuntimeError('Owned UDP listener reservation expired before probe')
        if evidence is not None:evidence.update(boundPort=port,listenerPid=pid,listenerInode=inode,portAllocation='kernel-reserved')
        sent=subprocess.run([adb,'-s',sender,'shell','timeout','3','toybox','nc','-4','-u','-q','1','-w','2',str(peer),str(port)],
                            input=challenge,capture_output=True,timeout=5)
        if evidence is not None:
            evidence['senderExit']=sent.returncode
            evidence['senderStderr']=repr(sent.stderr[:256])
        if sent.returncode not in (0,1,124): raise RuntimeError('AVD UDP probe tool failed')
        received,diagnostic=listener.communicate(timeout=7)
        if evidence is not None:
            evidence['listenerExit']=listener.returncode
            evidence['listenerStderr']=repr(diagnostic[:256])
            evidence['receivedBytes']=len(received)
        if listener.returncode not in (0,124) or diagnostic:
            kind='timeout' if b'timeout' in diagnostic.lower() else ('refused' if b'refused' in diagnostic.lower() else 'other' if diagnostic else 'none')
            # This stderr belongs only to toybox nc on our synthetic UDP probe,
            # never an app, PCM, SDP or credential-bearing command. Bound and escape it.
            detail=repr(diagnostic[:256])
            raise RuntimeError(f'AVD UDP probe receiver failed: exit={listener.returncode}, diagnostic={kind}, receivedBytes={len(received)}, toolError={detail}')
        return received==challenge
    finally:
        if listener.poll() is None:
            # Guest timeout remains bounded even if ADB disconnects.
            listener.terminate()
            try: listener.wait(timeout=2)
            except subprocess.TimeoutExpired: listener.kill();listener.wait(timeout=2)
        if evidence is not None and 'listenerExit' not in evidence:
            received,diagnostic=listener.communicate(timeout=2)
            evidence.update(listenerExit=listener.returncode,listenerStderr=repr(diagnostic[:256]),receivedBytes=len(received))
