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


def summarize_tls(path: Path, turn_address: str, relay_port: int, since: float, until: float, require_turn: bool = True, *, source_address: str, turn_port: int = 5349) -> dict:
    """Owned IPv4 TCP/TLS traffic; certificate/decoded-media checks are separate native evidence."""
    address=ipaddress.ip_address(turn_address)
    if address.version!=4 or not address.is_private or type(relay_port) is not int or not 1024<=relay_port<=65535 or turn_port not in (5348,5349):
        raise ValueError("Expected isolated TLS laboratory endpoints")
    source=ipaddress.ip_address(source_address)
    if source not in ipaddress.ip_network("10.0.2.0/24") or source == ipaddress.ip_address("10.0.2.2"):
        raise ValueError("Expected owned AVD source address")
    outbound=f"ip and src host {source}"
    turn=f"(dst host {address} and dst port {turn_port})"
    https=f"(dst host 10.0.2.2 and dst port {relay_port})"
    system="(port 53 or port 67 or port 68 or port 123 or port 5353 or port 5355)"
    observed=count(path,f"{outbound} and tcp and {turn}",since,until)
    direct_stun=count(path,f"{outbound} and udp and udp[12:4] = 0x2112a442",since,until)
    other_udp=count(path,f"{outbound} and udp and not {system}",since,until)
    other_tcp=count(path,f"{outbound} and tcp and not {turn} and not {https} and not {system}",since,until)
    if (require_turn and observed==0) or direct_stun or other_udp or other_tcp:
        raise RuntimeError(f"Owned TLS capture rejected: TURN_TLS={observed}, nonTURN_STUN={direct_stun}, otherUDP={other_udp}, otherTCP={other_tcp}; no raw packet data is published")
    return {"scope":"owned AVD outbound IPv4 TCP/TLS and UDP fallback rejection",
            "turnTlsPackets":observed,"nonTurnStunPackets":direct_stun,"otherNonSystemUdpPackets":other_udp,
            "otherNonSystemTcpPackets":other_tcp,"ipv6":"NOT_EXECUTED"}


def summarize_ipv6_turn(path: Path, turn_address: str, source4: str, source6: list[str], relay_port: int,
                        since: float, until: float, *, tls: bool, require_turn: bool = True) -> dict:
    """Dual-family observation; IPv6 client/TURN leg, IPv4 allocated relay addresses."""
    turn=ipaddress.ip_address(turn_address);a4=ipaddress.ip_address(source4);sources6=[ipaddress.ip_address(value) for value in source6]
    if turn.version!=6 or not turn.is_private or a4 not in ipaddress.ip_network('10.0.2.0/24') or not 1<=len(sources6)<=8 or any(value.version!=6 or value.is_link_local or value.is_loopback for value in sources6):
        raise ValueError('Owned IPv6 laboratory endpoints required')
    if type(relay_port) is not int or not 1024<=relay_port<=65535:raise ValueError('Isolated HTTPS port required')
    outbound=f'((ip and src host {a4}) or (ip6 and ('+' or '.join(f'src host {value}' for value in sources6)+')))'
    allowed=f'(ip6 and dst host {turn} and {"tcp" if tls else "udp"} and dst port {5349 if tls else 3478})'
    https=f'(ip and tcp and dst host 10.0.2.2 and dst port {relay_port})'
    system='(port 53 or port 67 or port 68 or port 123 or port 5353 or port 5355)'
    packets=count(path,f'{outbound} and {allowed}',since,until)
    other=count(path,f'{outbound} and (udp or tcp) and not {allowed} and not {https} and not {system}',since,until)
    if (require_turn and packets==0) or other:
        raise RuntimeError(f'Owned IPv6 TURN capture rejected: TURN={packets}, otherTCPUDP={other}; no raw packet data published')
    return {'scope':'owned Wi-Fi IPv4+IPv6 TCP/UDP; IPv6 client-to-TURN, IPv4 relay allocation',
            'turnIpv6Packets':packets,'nonAuthorizedTcpUdpPackets':other,'transport':'TLS' if tls else 'UDP',
            'allIpv6Allocation':'NOT_EXECUTED: pinned allocator uses default IPv4 allocation'}
