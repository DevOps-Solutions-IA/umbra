"""Static deployment contracts; no container/HTTPS/admission execution is claimed.

Docker Compose rendering is checked separately in the S1 validation receipt. These
stdlib-only checks run in script CI without requiring Docker or a YAML dependency.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def service(source, name):
    match = re.search(rf'^  {name}:\n(.*?)(?=^  \w+:\n|^\w+:\n|\Z)',
                      source, re.MULTILINE | re.DOTALL)
    if not match:
        raise AssertionError(f'Missing Compose service {name}')
    return match.group(1)


class S1ComposeContractTests(unittest.TestCase):
    def setUp(self):
        self.compose = (ROOT / 'compose.yaml').read_text()
        self.relay = service(self.compose, 'relay')
        self.caddy = service(self.compose, 'caddy')

    def test_realm_is_required_and_has_no_default_credential(self):
        self.assertRegex(self.relay, r'(?m)^      UMBRA_ADMISSION_REALM: \$\{UMBRA_ADMISSION_REALM:\?[^}]+\}$')
        example = (ROOT / '.env.example').read_text()
        self.assertRegex(example, r'(?m)^UMBRA_ADMISSION_REALM=$')
        self.assertRegex(example, r'(?m)^UMBRA_DOMAIN=chat\.example\.com$')

    def test_origin_is_https_and_derived_from_same_required_caddy_domain(self):
        self.assertRegex(self.relay, r'(?m)^      UMBRA_ADMISSION_ORIGIN: https://\$\{UMBRA_DOMAIN:\?[^}]+\}$')
        self.assertRegex(self.caddy, r'(?m)^      UMBRA_DOMAIN: \$\{UMBRA_DOMAIN:\?[^}]+\}$')
        self.assertNotIn('${UMBRA_ADMISSION_ORIGIN', self.compose)
        self.assertIn('{$UMBRA_DOMAIN} {', (ROOT / 'Caddyfile').read_text())
        self.assertIn('reverse_proxy relay:8080', (ROOT / 'Caddyfile').read_text())

    def test_backend_has_no_host_port_and_retains_isolation(self):
        self.assertNotRegex(self.relay, r'(?m)^    (ports|network_mode|privileged|command|entrypoint|user):')
        for contract in ('expose: ["8080"]', 'read_only: true', 'tmpfs: [/tmp]',
                         'cap_drop: [ALL]', 'security_opt: [no-new-privileges:true]',
                         'pids_limit: 128', 'mem_limit: 512m', 'networks: [private]'):
            self.assertIn(contract, self.relay)
        self.assertIn('condition: service_healthy', self.caddy)
        self.assertNotIn('8080:8080', self.compose)

    def test_backend_retains_nonroot_single_worker_and_untrusted_proxy_headers(self):
        dockerfile = (ROOT / 'relay/Dockerfile').read_text()
        self.assertRegex(dockerfile, r'(?m)^USER 10001:10001$')
        self.assertIn('"--workers", "1"', dockerfile)
        self.assertIn('"--no-proxy-headers"', dockerfile)
        self.assertIn('"--no-access-log"', dockerfile)

    def test_caddy_is_pinned_to_reviewed_multiarch_index(self):
        self.assertRegex(self.caddy, r'(?m)^    image: caddy:2@sha256:8dc9fa87b36b25303d1c67d2a09f5824b7f3bfd72cb052f246e5da2133fe29a8$')


if __name__ == '__main__':
    unittest.main()
