import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'relay'))
from s1_preflight import PreflightError, validate_config
from umbra_relay.admission_protocol import encode, digest
from nacl.signing import SigningKey


class PreflightTest(unittest.TestCase):
    def setUp(self):
        public = bytes(SigningKey(bytes(range(32))).verify_key)
        realm = f'umbra:realm:1:{encode(bytes(range(32)))}:{encode(public)}:{digest(public)}'
        self.config = {'services': {
            'relay': {'environment': {'UMBRA_ADMISSION_REALM': realm,
                'UMBRA_ADMISSION_ORIGIN': 'https://chat.example.org'},
                'read_only': True, 'cap_drop': ['ALL'],
                'security_opt': ['no-new-privileges:true'],
                'volumes': [{'type': 'volume', 'source': 'relay_data', 'target': '/data'}]},
            'caddy': {'environment': {'UMBRA_DOMAIN': 'chat.example.org'},
                'ports': [{'target': 443, 'published': '443'}, {'target': 80, 'published': '80'}]}}}

    def test_valid_public_config(self):
        self.assertEqual('LOCAL_CONFIG_VALIDATED', validate_config(self.config)['status'])

    def test_rejects_missing_and_invalid_realm(self):
        for realm in ('', 'umbra:realm:1:invalid'):
            self.config['services']['relay']['environment']['UMBRA_ADMISSION_REALM'] = realm
            with self.assertRaises(PreflightError): validate_config(self.config)

    def test_rejects_nonexact_origins(self):
        for origin in ('http://chat.example.org', 'https://chat.example.org/',
            'https://user@chat.example.org', 'https://chat.example.org?q=x',
            'https://chat.example.org:443', 'https://other.example.org',
            'https://chat.example.org:bad', 'https://chat.example.org#x'):
            self.config['services']['relay']['environment']['UMBRA_ADMISSION_ORIGIN'] = origin
            with self.assertRaises(PreflightError): validate_config(self.config)

    def test_rejects_exposed_backend_or_missing_hardening(self):
        for mutation in ({'ports': [{'target': 8080, 'published': '8080'}]},
                         {'network_mode': 'host'}, {'read_only': False},
                         {'cap_drop': []}, {'security_opt': []}, {'volumes': []},
                         {'command': ['uvicorn', '--workers', '2']}):
            config = copy.deepcopy(self.config)
            config['services']['relay'].update(mutation)
            with self.assertRaises(PreflightError): validate_config(config)

    def test_errors_do_not_echo_config(self):
        self.config['services']['relay']['environment']['UMBRA_ADMISSION_REALM'] = 'DO-NOT-LOG'
        with self.assertRaises(PreflightError) as caught: validate_config(self.config)
        self.assertNotIn('DO-NOT-LOG', str(caught.exception))

    def test_malformed_structures_fail_closed_without_input_details(self):
        for ports in (None, ['DO-NOT-LOG'], [None], [{'target': float('inf'), 'published': '443'}], {}):
            config = copy.deepcopy(self.config)
            config['services']['caddy']['ports'] = ports
            with self.assertRaises(PreflightError) as caught: validate_config(config)
            self.assertNotIn('DO-NOT-LOG', str(caught.exception))
        for config in (None, [], {'services': []}, {'services': {'relay': None, 'caddy': None}}):
            with self.assertRaises(PreflightError): validate_config(config)

    def test_rejects_root_user_override_and_additional_mounts(self):
        for mutation in (
            {'user': '0:0'},
            {'volumes': self.config['services']['relay']['volumes'] + [
                {'type': 'bind', 'source': '/var/run/docker.sock', 'target': '/var/run/docker.sock'}]},
            {'volumes': self.config['services']['relay']['volumes'] + [
                {'type': 'bind', 'source': '/', 'target': '/host', 'read_only': True}]},
        ):
            config = copy.deepcopy(self.config)
            config['services']['relay'].update(mutation)
            with self.assertRaises(PreflightError): validate_config(config)

    def test_rejects_host_namespaces_and_privilege_extensions(self):
        for mutation in ({'pid': 'host'}, {'ipc': 'host'}, {'uts': 'host'},
                         {'devices': ['/dev/sda:/dev/sda']},
                         {'device_cgroup_rules': ['a *:* rwm']},
                         {'security_opt': ['no-new-privileges:true', 'seccomp:unconfined']},
                         {'group_add': ['0']}, {'volumes_from': ['other']},
                         {'use_api_socket': True}):
            config = copy.deepcopy(self.config)
            config['services']['relay'].update(mutation)
            with self.assertRaises(PreflightError): validate_config(config)
