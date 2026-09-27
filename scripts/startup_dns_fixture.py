#!/usr/bin/env python3
"""Loopback-only, test-only NXDOMAIN responder; count one synthetic trap name.

No forwarding, dynamic records, user data or external DNS. Run only in disposable CI.
"""
import argparse
import socket
import struct


def response(packet, trap):
    if len(packet) < 12 or len(packet) > 512:
        raise ValueError('DNS fixture packet size')
    ident, flags, count, answers, authorities, additional = struct.unpack('!6H', packet[:12])
    if flags & 0xF800 or count != 1 or answers or authorities:
        raise ValueError('DNS fixture question')
    labels, offset = [], 12
    while True:
        if offset >= len(packet): raise ValueError('DNS fixture truncation')
        size = packet[offset]; offset += 1
        if not size: break
        if size > 63 or offset + size > len(packet): raise ValueError('DNS fixture label')
        labels.append(packet[offset:offset+size].decode('ascii').lower()); offset += size
    if offset + 4 > len(packet): raise ValueError('DNS fixture question truncation')
    question = packet[12:offset+4]
    return struct.pack('!6H', ident, 0x8183, 1, 0, 0, 0) + question, '.'.join(labels) == trap


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port',type=int,default=53)
    args=parser.parse_args()
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as server:
        server.bind(('127.0.0.1',args.port))
        print('READY',flush=True)
        while True:
            packet,peer=server.recvfrom(513)
            try: answer,matched=response(packet,'startup.umbra.test')
            except (ValueError,UnicodeError,struct.error):
                print('REJECTED_MALFORMED',flush=True); continue
            if matched: print('TRAP_QUERY',flush=True)
            server.sendto(answer,peer)


if __name__=='__main__': main()
