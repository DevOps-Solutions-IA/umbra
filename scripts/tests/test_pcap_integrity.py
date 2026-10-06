import ipaddress
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pcap_integrity import inspect_pcap, PcapIntegrityError, sanitized_tcpdump_diagnostic
import voice_network_evidence as network


def header():
    return struct.pack('<IHHIIII', 0xa1b2c3d4, 2, 4, 0, 0, 65535, 1)


def record(packet):
    return struct.pack('<IIII', 1, 0, len(packet), len(packet)) + packet


def udp4():
    # Synthetic protocol headers only; no credentials or application payload.
    ethernet = bytes(12) + b'\x08\x00'
    stun = struct.pack('!HHI', 1, 0, 0x2112a442) + bytes(12)
    udp = struct.pack('!HHHH', 49152, 3478, 8 + len(stun), 0) + stun
    ip = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20 + len(udp), 1, 0, 64, 17, 0,
                     ipaddress.ip_address('10.0.2.16').packed, ipaddress.ip_address('172.20.0.2').packed)
    return ethernet + ip + udp


def tcp6():
    tcp = struct.pack('!HHIIBBHHH', 49152, 5349, 1, 0, 0x50, 2, 1024, 0, 0)
    ip = struct.pack('!IHBB16s16s', 6 << 28, len(tcp), 6, 64,
                     ipaddress.ip_address('fd42::16').packed, ipaddress.ip_address('fd42::2').packed)
    return bytes(12) + b'\x86\xdd' + ip + tcp


class PcapIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'synthetic.pcap'

    def write(self, data):
        self.path.write_bytes(data)
        return self.path

    def test_valid_pcap_is_readable_by_real_tcpdump(self):
        self.write(header() + record(udp4()))
        self.assertEqual(1, inspect_pcap(self.path)['records'])
        self.assertEqual(1, network.count(self.path, 'udp'))

    def test_truncated_header_rejected(self):
        for size in (0, 1, 4, 23):
            self.write(header()[:size])
            with self.assertRaises(PcapIntegrityError): inspect_pcap(self.path)
            with self.assertRaises(RuntimeError): network.count(self.path, 'ip')

    def test_truncated_packet_header_and_body_rejected(self):
        data = header() + record(udp4())
        for truncated in (header() + bytes(7), data[:-1]):
            self.write(truncated)
            with self.assertRaises(PcapIntegrityError): inspect_pcap(self.path)
            with self.assertRaises(RuntimeError): network.count(self.path, 'ip')

    def test_invalid_header_fields_rejected(self):
        for data in (b'XXXX' + header()[4:], header()[:4] + bytes(20),
                     header() + struct.pack('<IIII', 1, 0, 65536, 65536)):
            self.write(data)
            with self.assertRaises(PcapIntegrityError): inspect_pcap(self.path)

    def test_zero_packets_valid_capture_not_malformed(self):
        self.write(header())
        self.assertEqual(0, inspect_pcap(self.path)['records'])
        self.assertEqual(0, network.count(self.path, 'ip'))
        self.write(b'')
        with self.assertRaises(RuntimeError): network.count(self.path, 'ip')

    def test_parser_stderr_never_exposes_content_paths_or_addresses(self):
        secret = 'synthetic-secret-not-for-diagnostic'
        result = subprocess.CompletedProcess([], 1, secret, '/private/' + secret + ' 192.0.2.5 truncated dump file')
        with patch.object(network.subprocess, 'run', return_value=result):
            with self.assertRaises(RuntimeError) as error: network.count(self.path, 'ip')
        message = str(error.exception)
        self.assertIn('TRUNCATED_CAPTURE', message)
        for value in (secret, '/private/', '192.0.2.5'): self.assertNotIn(value, message)

    def test_other_parser_failure_stays_failure_not_zero(self):
        with patch.object(network.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'unknown synthetic error')):
            with self.assertRaises(RuntimeError): network.count(self.path, 'ip')
        self.assertEqual('OTHER', sanitized_tcpdump_diagnostic('unrecognized')['category'])

    def test_ipv4_summary_uses_real_parser(self):
        self.write(header() + record(udp4()))
        result = network.summarize(self.path, '172.20.0.2')
        self.assertEqual(1, result['turnPackets'])
        self.assertEqual(1, result['turnStunPackets'])
        self.assertEqual(0, result['otherNonSystemUdpPackets'])

    def test_ipv6_tls_summary_uses_real_parser_not_tls_acceptance(self):
        self.write(header() + record(tcp6()))
        result = network.summarize_ipv6_turn(self.path, 'fd42::2', '10.0.2.16', ['fd42::16'], 43210, 0, 10, tls=True)
        self.assertEqual(1, result['turnIpv6Packets'])
        self.assertEqual(0, result['nonAuthorizedTcpUdpPackets'])
        self.assertEqual('TLS', result['transport'])


if __name__ == '__main__': unittest.main()
