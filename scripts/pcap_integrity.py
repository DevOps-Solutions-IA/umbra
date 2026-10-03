"""Strict classic-PCAP framing checks, without reading/publishing packet bodies.

Framing validation alone does NOT demonstrate that the writer has stopped.
The capture owner must close it before creating an acceptance snapshot.
"""
import struct
from pathlib import Path


class PcapIntegrityError(RuntimeError):
    def __init__(self, reason):
        self.reason = reason
        super().__init__('PCAP integrity failure: ' + reason)


def inspect_pcap(path: Path) -> dict:
    size = path.stat().st_size
    with path.open('rb') as stream:
        header = stream.read(24)
        if len(header) != 24:
            raise PcapIntegrityError('TRUNCATED_HEADER')
        formats = {b'\xd4\xc3\xb2\xa1': ('<', 1_000_000), b'\xa1\xb2\xc3\xd4': ('>', 1_000_000),
                   b'\x4d\x3c\xb2\xa1': ('<', 1_000_000_000), b'\xa1\xb2\x3c\x4d': ('>', 1_000_000_000)}
        if header[:4] not in formats:
            raise PcapIntegrityError('UNSUPPORTED_MAGIC')
        endian, resolution = formats[header[:4]]
        major, minor, _, _, snaplen, linktype = struct.unpack(endian + 'HHIIII', header[4:])
        if (major, minor) != (2, 4) or snaplen == 0:
            raise PcapIntegrityError('INVALID_HEADER')
        offset, records = 24, 0
        while offset < size:
            record = stream.read(16)
            if len(record) != 16:
                raise PcapIntegrityError('TRUNCATED_PACKET_HEADER')
            _, fraction, included, original = struct.unpack(endian + 'IIII', record)
            if fraction >= resolution or included > snaplen or included > original:
                raise PcapIntegrityError('INVALID_PACKET_HEADER')
            if included > size - offset - 16:
                raise PcapIntegrityError('TRUNCATED_PACKET')
            stream.seek(included, 1)
            offset += 16 + included
            records += 1
        if stream.read(1) or path.stat().st_size != size:
            raise PcapIntegrityError('CHANGED_DURING_INSPECTION')
    return {'bytes': size, 'records': records, 'headerValid': True, 'framingValid': True,
            'linkType': linktype, 'timestampResolution': resolution}


def sanitized_tcpdump_diagnostic(stderr: str) -> dict:
    """Fixed categories only. Never return file paths, addresses or packet text."""
    lower = stderr.lower()
    reason = 'OTHER'
    for marker, category in (('truncated dump file', 'TRUNCATED_CAPTURE'),
                             ('unknown file format', 'UNKNOWN_FORMAT'),
                             ('bad dump file format', 'BAD_FORMAT'),
                             ('permission denied', 'PERMISSION_DENIED'),
                             ('no such file', 'FILE_MISSING'),
                             ('syntax error', 'FILTER_ERROR')):
        if marker in lower:
            reason = category
            break
    return {'category': reason, 'stderrBytes': len(stderr.encode('utf-8', errors='replace'))}
