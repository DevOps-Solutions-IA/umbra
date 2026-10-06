#!/usr/bin/env python3
"""Validate a local JSON rendering of Compose; never start Docker or access DNS.

Render separately with `docker compose config --format json`, retaining output
locally with restricted permissions. This tool never echoes the rendered config.
"""
import argparse
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'relay'))


class PreflightError(ValueError):
    pass


def validate_config(config):
    try:
        return _validate_config(config)
    except PreflightError:
        raise
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
        raise PreflightError('Invalid or incomplete rendered Compose structure') from None


def _validate_config(config):
    from umbra_relay.admission_protocol import Realm
    try:
        services = config['services']
        relay, caddy = services['relay'], services['caddy']
        env = relay['environment']
        Realm.parse(env['UMBRA_ADMISSION_REALM'])
    except (KeyError, TypeError, ValueError):
        raise PreflightError('Missing or invalid public admission realm') from None
    origin = env.get('UMBRA_ADMISSION_ORIGIN')
    domain = caddy.get('environment', {}).get('UMBRA_DOMAIN')
    if not isinstance(domain, str) or not re.fullmatch(
            r'(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}', domain):
        raise PreflightError('A canonical public DNS domain is required')
    try:
        parsed = urlsplit(origin)
        if (origin != 'https://' + domain or parsed.scheme != 'https' or
                parsed.hostname != domain or parsed.port is not None or
                parsed.username is not None or parsed.password is not None or
                parsed.path or parsed.query or parsed.fragment):
            raise ValueError()
    except (TypeError, ValueError, AttributeError):
        raise PreflightError('Admission origin must exactly match the public HTTPS domain') from None
    if relay.get('ports') or relay.get('network_mode'):
        raise PreflightError('Relay backend must not publish ports or use host networking')
    if (relay.get('read_only') is not True or 'ALL' not in relay.get('cap_drop', []) or
            relay.get('security_opt') not in (['no-new-privileges:true'], ['no-new-privileges'])):
        raise PreflightError('Relay container hardening is incomplete')
    overrides = ('user', 'pid', 'ipc', 'uts', 'devices', 'device_cgroup_rules',
                 'group_add', 'volumes_from', 'use_api_socket', 'privileged',
                 'cap_add', 'entrypoint', 'command')
    if any(relay.get(key) for key in overrides):
        raise PreflightError('Unexpected relay execution or privilege override')
    mounts = relay.get('volumes', [])
    if (not isinstance(mounts, list) or len(mounts) != 1 or
            not isinstance(mounts[0], dict) or mounts[0].get('target') != '/data' or
            mounts[0].get('type') != 'volume' or mounts[0].get('source') != 'relay_data' or
            mounts[0].get('read_only')):
        raise PreflightError('Relay persistent data volume is missing')
    ports = caddy.get('ports', [])
    try:
        mappings = {(int(p['target']), str(p['published'])) for p in ports}
        if mappings != {(80, '80'), (443, '443')}:
            raise ValueError()
    except (TypeError, KeyError, ValueError):
        raise PreflightError('Caddy public port mapping is unexpected') from None
    return {'status': 'LOCAL_CONFIG_VALIDATED', 'network_checked': False,
            'deployment_authorized': False,
            'limits': 'No DNS, TLS, image, cloud, volume permissions or runtime checks performed'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose-json', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.compose_json.stat().st_size > 1_000_000:
            raise PreflightError('Rendered configuration exceeds local inspection limit')
        result = validate_config(json.loads(args.compose_json.read_text(encoding='utf-8')))
    except (OSError, ValueError, TypeError, KeyError, ImportError):
        print('LOCAL_CONFIG_REJECTED: invalid or incomplete local configuration', file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
