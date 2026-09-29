"""Safety boundaries of the explicit physical runner; not physical execution evidence."""
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_physical_tests as runner


class PhysicalRunnerTests(unittest.TestCase):
    def test_selection_is_explicit_and_never_falls_back(self):
        listing = 'List of devices attached\nsynthetic-one device product:synthetic\nsynthetic-two unauthorized\n'
        self.assertEqual(runner.select_device(listing, 'synthetic-one'), 'synthetic-one')
        for serial in ('synthetic-two', 'absent', 'emulator-5554', '127.0.0.1:5555', 'x;reboot', ''):
            with self.assertRaises((ValueError, RuntimeError)):
                runner.select_device(listing, serial)
        with self.assertRaises(RuntimeError):
            runner.select_device(listing + 'synthetic-one device\n', 'synthetic-one')

    def test_device_lock_excludes_parallel_runner_and_releases(self):
        with tempfile.TemporaryDirectory() as directory:
            with runner.device_lock(Path(directory), 'synthetic-one') as state:
                self.assertNotIn('synthetic-one', state.name)
                with self.assertRaises(RuntimeError):
                    with runner.device_lock(Path(directory), 'synthetic-one'):
                        self.fail('overlapping device lease')
            with runner.device_lock(Path(directory), 'synthetic-one'):
                pass

    def test_preflight_rejects_emulator_old_api_unknown_or_unsafe_battery(self):
        def output(command, timeout=20):
            self.assertEqual(command[:3], ['owned-adb', '-s', 'synthetic-one'])
            if command[-2:] == ['dumpsys', 'battery']:
                return 'level: 80\nscale: 100\ntemperature: 250\n'
            return {'ro.build.version.sdk': '35', 'ro.kernel.qemu': '0'}.get(command[-1], 'synthetic')
        def call(command, timeout=20):
            if command == ['owned-adb', 'devices', '-l']:
                return 'List of devices attached\nsynthetic-one device\n'
            return output(command, timeout)
        with patch.object(runner, 'run', side_effect=call):
            self.assertEqual(runner.physical_profile('owned-adb','synthetic-one')['battery']['level'],80)
        for key, value in (('ro.kernel.qemu','1'), ('ro.build.version.sdk','30'), ('ro.build.version.sdk','')):
            def changed(command, timeout=20):
                return value if command[-1] == key else call(command,timeout)
            with patch.object(runner,'run',side_effect=changed), self.assertRaises(RuntimeError):
                runner.physical_profile('owned-adb','synthetic-one')
        for battery in ('', 'level: 1\nscale: 100\ntemperature: 250\n', 'level: 80\nscale: 100\ntemperature: 400\n'):
            def changed(command, timeout=20):
                return battery if command[-2:] == ['dumpsys','battery'] else call(command,timeout)
            with patch.object(runner,'run',side_effect=changed), self.assertRaises(RuntimeError):
                runner.physical_profile('owned-adb','synthetic-one')

    def test_collision_never_updates_existing_user_data(self):
        info={'sha256':'a'*64}
        runner.assert_no_collision(None,None,info)
        runner.assert_no_collision('a'*64,'a'*64,info)
        for installed,owned in (('a'*64,None),('b'*64,'a'*64),('a'*64,'b'*64)):
            with self.assertRaises(RuntimeError):runner.assert_no_collision(installed,owned,info)

    def test_receipt_requires_every_case_and_real_keystore_report(self):
        text = ''
        for case in runner.CASES:
            cls, method = case.split('#')
            for status in (1,0):
                text += f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {status}\n'
        text += f'OK ({len(runner.CASES)} tests)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_STATUS: physicalKeystoreLevel=TEE\nINSTRUMENTATION_STATUS_CODE: 0\n'
        self.assertTrue(runner.valid_receipt(text,0))
        for bad in (text.replace(f'OK ({len(runner.CASES)} tests)','OK (0 tests)'), text.replace('physicalKeystoreLevel=TEE','missing'),
                    text.replace('STATUS_CODE: 0','STATUS_CODE: -3',1),text+'Process crashed'):
            self.assertFalse(runner.valid_receipt(bad,0))
        self.assertFalse(runner.valid_receipt(text,1))

    def test_windows_adb_uses_explicit_path_conversion(self):
        with patch.object(runner,'run',return_value='C:\\synthetic\\app.apk\n') as call:
            self.assertEqual(runner.apk_path_for_adb('adb.exe',Path('/synthetic/app.apk')),'C:\\synthetic\\app.apk')
            self.assertEqual(call.call_args.args[0],['wslpath','-w','/synthetic/app.apk'])
        self.assertEqual(runner.apk_path_for_adb('/sdk/adb',Path('/synthetic/app.apk')),'/synthetic/app.apk')

    def test_only_reviewed_non_sensor_cases_and_isolated_packages(self):
        self.assertEqual(len(set(runner.CASES)),10)
        self.assertFalse(any('#nativeNotePlayback' in c or '.RestrictedRecordingAndroidTest#' in c or '.LocationAndroidTest#' in c for c in runner.CASES))
        self.assertEqual(runner.package('offline',False),'app.umbra.privatechat.offline.dev')
        with self.assertRaises(ValueError):runner.package('production',False)

    def test_absent_package_is_distinct_from_inspection_failure(self):
        import subprocess
        for code in (0,1):
            with patch.object(runner.subprocess,'run',return_value=subprocess.CompletedProcess([],code,'','')):
                self.assertIsNone(runner.installed_digest(['adb','-s','synthetic'],'app.umbra.privatechat.dev'))
        for code,out,err in ((2,'',''),(0,'','permission denied'),(1,'package:/data/app/a/base.apk',''),(0,'package:/personal/other','')):
            with patch.object(runner.subprocess,'run',return_value=subprocess.CompletedProcess([],code,out,err)), self.assertRaises(RuntimeError):
                runner.installed_digest(['adb','-s','synthetic'],'app.umbra.privatechat.dev')

    def test_r8_manifest_cannot_normalize_away_security_flags(self):
        from test_build_validation import BuildValidationTests
        import check_apk_policy
        for flag in ('debuggable','allowBackup','fullBackupContent','usesCleartextTraffic','testOnly'):
            root=BuildValidationTests().manifest()
            root.set('package','app.umbra.privatechat.vaultlab')
            root.find('application').set('android:debuggable','false')
            root.find('application').set('android:'+flag,'true')
            with patch.object(runner,'run',return_value='synthetic manifest') as call, \
                    patch.object(check_apk_policy,'decode_tree',return_value=root), self.assertRaises(RuntimeError):
                runner.inspect_optimized(Path('/synthetic.apk'),'connected',Path('/sdk'),Path('/mapping.txt'),{})
            self.assertEqual(call.call_count,1)
            self.assertEqual(root.get('package'),'app.umbra.privatechat.vaultlab')
            self.assertEqual(root.find('application').get('android:'+flag),'true')

    def test_explicit_server_remains_local_and_targeted(self):
        self.assertEqual(runner.adb_command('adb.exe',5038), ['adb.exe','-H','localhost','-P','5038'])
        for port in (0,503.8,'5038',65536):
            with self.assertRaises(ValueError):runner.adb_command('adb.exe',port)
        with patch.object(runner,'run',return_value='List of devices attached\n') as call:
            with self.assertRaises(RuntimeError):runner.physical_profile('adb.exe','synthetic',5038)
            self.assertEqual(call.call_args.args[0],['adb.exe','-H','localhost','-P','5038','devices','-l'])

    def test_install_reports_only_fixed_error_code(self):
        result=runner.install_outcome(1,'','adb: failed with INSTALL_FAILED_USER_RESTRICTED: synthetic private detail')
        self.assertEqual(result['code'],'INSTALL_FAILED_USER_RESTRICTED')
        self.assertNotIn('private',str(result))
        self.assertEqual(runner.install_outcome(0,'Success\n','')['result'],'SUCCESS')
        self.assertEqual(runner.install_outcome(1,'Success\n','')['result'],'FAILED')

    def test_update_requires_exact_recorded_install_and_explicit_flag(self):
        runner.assert_no_collision('a'*64,'a'*64,{'sha256':'b'*64},True)
        for owned in (None,'c'*64):
            with self.assertRaises(RuntimeError):runner.assert_no_collision('a'*64,owned,{'sha256':'b'*64},True)
        with self.assertRaises(RuntimeError):runner.assert_no_collision('a'*64,'a'*64,{'sha256':'b'*64},False)
