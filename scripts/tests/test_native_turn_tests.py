import sys
import tempfile
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_native_turn_tests import validate


class NativeTurnEvidenceTest(unittest.TestCase):
    def report(self):
        root = ET.Element('testsuites', tests='19', failures='0')
        suite = ET.SubElement(root, 'testsuite', name='TurnPortTest')
        suffixes = ['UDP', 'TCP', 'TLS', 'V4toV6UDP', 'V4toV6TCP', 'V4toV6TLS',
                    'PingPongUDP', 'PingPongTCP', 'PingPongTLS',
                    'DetectRepetitionUDP', 'DetectRepetitionTCP', 'DetectRepetitionTLS',
                    'LoopbackUdpIpv4', 'LoopbackUdpIpv6', 'LoopbackTcpIpv4',
                    'LoopbackTcpIpv6', 'LoopbackTlsIpv4', 'LoopbackTlsIpv6']
        for suffix in suffixes:
            ET.SubElement(suite, 'testcase', classname='TurnPortTest',
                          name='TestTurnAlternateServer' + suffix,
                          status='run', result='completed')
        ET.SubElement(suite, 'testcase', classname='TurnPortTest',
                      name='DISABLED_TestTurnCustomizerAddAttribute',
                      status='run', result='completed')
        return root

    def check(self, root):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'turn.xml'
            ET.ElementTree(root).write(path)
            return validate(path)

    def test_executed_results(self):
        self.assertEqual(19, self.check(self.report()))

    def test_empty_missing_duplicate_or_inconsistent_results(self):
        for change in ('empty', 'missing', 'customizer', 'duplicate', 'count'):
            with self.subTest(change=change):
                root = self.report()
                suite = root.find('testsuite')
                if change == 'empty':
                    root.clear()
                elif change == 'missing':
                    suite.remove(suite[0])
                elif change == 'customizer':
                    suite.remove(suite[-1])
                    root.set('tests', '18')
                elif change == 'duplicate':
                    suite.append(ET.fromstring(ET.tostring(suite[0])))
                else:
                    root.set('tests', '20')
                with self.assertRaises(ValueError):
                    self.check(root)

    def test_failures_skips_and_unexecuted_cases(self):
        for change in ('failure', 'error', 'skipped', 'notrun', 'suppressed', 'summary'):
            with self.subTest(change=change):
                root = self.report()
                case = root.find('.//testcase')
                if change in ('failure', 'error', 'skipped'):
                    ET.SubElement(case, change)
                elif change == 'notrun':
                    case.set('status', 'notrun')
                elif change == 'suppressed':
                    case.set('result', 'suppressed')
                else:
                    root.set('failures', '1')
                with self.assertRaises(ValueError):
                    self.check(root)
