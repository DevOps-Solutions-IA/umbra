"""Owned-AVD UDP reachability probe, never Bluetooth or native media evidence.

Run outside the multimedia packet observation window. A random challenge must
arrive at a confirmed listening socket; ICMP availability is not assumed.
"""
import ipaddress
import secrets
import subprocess
import time


def probe_udp(adb: str, sender: str, receiver: str, address: str) -> bool:
    if not all(value.startswith('emulator-') and value[9:].isdigit() for value in (sender,receiver)) or sender==receiver:
        raise ValueError('Two owned emulator serials required')
    peer=ipaddress.ip_address(address)
    if peer.version!=4 or peer not in ipaddress.ip_network('10.0.2.0/24'):
        raise ValueError('Expected owned AVD Wi-Fi address')
    port=40000+secrets.randbelow(5000)
    challenge=('umbra-owned-route-'+secrets.token_hex(16)).encode()
    listener=subprocess.Popen([adb,'-s',receiver,'shell','timeout','5','toybox','nc','-4','-u','-l','-p',str(port),'-W','1'],
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:
        ready=time.monotonic()+2
        while True:
            if listener.poll() is not None: raise RuntimeError('Owned UDP listener exited before probe')
            table=subprocess.run([adb,'-s',receiver,'shell','cat','/proc/net/udp'],capture_output=True,text=True,check=True,timeout=3).stdout
            if any(row.split()[1].endswith(f':{port:04X}') for row in table.splitlines()[1:] if row.strip()): break
            if time.monotonic()>=ready: raise RuntimeError('Owned UDP listener did not bind')
            time.sleep(0.05)
        sent=subprocess.run([adb,'-s',sender,'shell','timeout','3','toybox','nc','-4','-u','-q','1','-w','2',str(peer),str(port)],
                            input=challenge,capture_output=True,timeout=5)
        if sent.returncode not in (0,1,124): raise RuntimeError('AVD UDP probe tool failed')
        received,diagnostic=listener.communicate(timeout=7)
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
