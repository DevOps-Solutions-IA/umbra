import importlib.util
from pathlib import Path
import struct
import sys
import unittest

SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
from startup_dns_fixture import response
from run_private_startup import counters, sensor_access, valid_report


class StartupEvidenceTest(unittest.TestCase):
    def test_dns_trap_reply_and_strict_lengths(self):
        name=b'\x07startup\x05umbra\x04test\x00'
        request=struct.pack('!6H',123,0x100,1,0,0,0)+name+struct.pack('!HH',1,1)
        answer,matched=response(request,'startup.umbra.test')
        self.assertTrue(matched)
        self.assertEqual(struct.unpack('!6H',answer[:12]),(123,0x8183,1,0,0,0))
        self.assertEqual(answer[12:],request[12:])
        for bad in (b'',request[:15],request[:-1],request+b'x'*513):
            with self.assertRaises(ValueError):response(bad,'startup.umbra.test')
        self.assertFalse(response(request,'another.umbra.test')[1])

    def test_counter_requires_real_unambiguous_row(self):
        self.assertEqual(counters(' pkts bytes target\n 13 1024 RETURN all -- * * 0/0 0/0'),{'packets':13,'bytes':1024})
        for bad in ('', 'permission denied', '0 0 RETURN\n0 0 RETURN'):
            with self.assertRaises(RuntimeError):counters(bad)

    def test_sensor_grant_is_not_access_and_access_is_not_hidden(self):
        self.assertFalse(sensor_access('CAMERA: allow\nRECORD_AUDIO: allow'))
        for op in ('CAMERA','RECORD_AUDIO','FINE_LOCATION','COARSE_LOCATION','BLUETOOTH_SCAN'):
            self.assertTrue(sensor_access(op+': allow; time=+2s ago'))
            self.assertTrue(sensor_access(op+': allow; duration=running'))

    def test_instrumentation_requires_executed_tests(self):
        good='OK (3 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(valid_report(good))
        for bad in ('',good.replace('3','0'),good+'FAILURES!!!',good+'INSTRUMENTATION_STATUS_CODE: -2'):
            self.assertFalse(valid_report(bad))


if __name__=='__main__':unittest.main()
