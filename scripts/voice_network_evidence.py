"""Summarize only owned emulator captures; never publish packet bodies or addresses.

Uses libpcap/tcpdump's packet parser. This is bounded IPv4 UDP evidence, not proof
about IPv6, physical devices, arbitrary TCP media or unobserved platforms.
"""
import ipaddress
from pathlib import Path
import subprocess


def count(path: Path, expression: str, since: float = 0, until: float = float("inf")) -> int:
    result = subprocess.run(["tcpdump", "-tt", "-nn", "-q", "-r", str(path), expression],
                            capture_output=True, text=True, check=True, timeout=30)
    return sum(since <= float(line.split()[0]) <= until for line in result.stdout.splitlines() if line.strip())


def summarize(path: Path, turn_address: str, turn_port: int = 3478, since: float = 0, until: float = float("inf"), require_turn: bool = True) -> dict:
    if turn_port not in (3478,3479): raise ValueError("Unexpected laboratory port")
    address = ipaddress.ip_address(turn_address)
    if address.version != 4 or not address.is_private:
        raise ValueError("Expected private IPv4 TURN laboratory")
    outbound = "ip and src net 10.0.2.0/24 and udp"
    turn = f"(dst host {address} and dst port {turn_port})"
    stun = "udp[12:4] = 0x2112a442"
    permitted_system = "(port 53 or port 67 or port 68 or port 123 or port 5353 or port 5355)"
    observed = count(path, f"{outbound} and {turn}", since, until)
    allocations = count(path, f"{outbound} and {turn} and {stun}", since, until)
    direct_stun = count(path, f"{outbound} and not {turn} and {stun}", since, until)
    other_udp = count(path, f"{outbound} and not {turn} and not {permitted_system}", since, until)
    if (require_turn and (observed == 0 or allocations == 0)) or direct_stun != 0 or other_udp != 0:
        raise RuntimeError(f"Owned AVD capture rejected: TURN={observed}, TURN_STUN={allocations}, nonTURN_STUN={direct_stun}, otherUDP={other_udp}; raw capture is not published")
    return {"scope": "owned AVD outbound IPv4 UDP", "mediaAttempt": "OBSERVED" if observed else "NOT_EXECUTED: no remote description after initiator rejection", "turnPackets": observed,
            "turnStunPackets": allocations, "nonTurnStunPackets": direct_stun,
            "otherNonSystemUdpPackets": other_udp, "ipv6": "NOT_EXECUTED"}
