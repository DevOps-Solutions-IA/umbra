#!/usr/bin/env python3
"""Validate a private, offline two-phone plan. Never discovers or touches a device."""
import argparse
import json
import re
from pathlib import Path


def validate_plan(plan):
    """Owner-assigned asset IDs describe phones, not packages or Android profiles.

    This checks declared evidence only; it cannot authenticate physical identity,
    replace fresh preflight, or authorize installation/execution.
    """
    if not isinstance(plan, dict) or set(plan) != {'owner_confirmed_two_physical_devices', 'peers'}:
        raise ValueError('Invalid plan schema')
    if plan['owner_confirmed_two_physical_devices'] is not True:
        raise ValueError('Explicit owner confirmation of two physical phones required')
    peers = plan['peers']
    if not isinstance(peers, list) or len(peers) != 2:
        raise ValueError('Exactly two physical peer records required')
    serials, assets = set(), set()
    for peer in peers:
        if not isinstance(peer, dict) or set(peer) != {'serial', 'physical_asset_id', 'transport', 'state', 'qemu', 'api'}:
            raise ValueError('Invalid peer schema; packages cannot identify physical phones')
        serial, asset = peer['serial'], peer['physical_asset_id']
        if (not isinstance(serial, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', serial)
                or serial.startswith('emulator-') or peer['transport'] != 'usb'):
            raise ValueError('Distinct explicit USB hardware serials required')
        if not isinstance(asset, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', asset):
            raise ValueError('Owner-confirmed physical inventory identity required')
        if serial in serials or asset in assets:
            raise ValueError('Two apps, profiles or serial aliases on one phone do not form two peers')
        if peer['state'] != 'device' or peer['qemu'] != '0':
            raise ValueError('Physical ready-device evidence required; unknown is rejected')
        if type(peer['api']) is not int or peer['api'] < 31:
            raise ValueError('Verified API 31 or newer required')
        serials.add(serial)
        assets.add(asset)
    return {'result': 'PLAN_VALID_ONLY', 'peer_count': 2, 'device_execution': False,
            'installation_authorized': False, 'physical_identity_attested': False,
            'fresh_preflight_required': True}



def unique_object(pairs):
    """Reject ambiguous JSON at every object depth before schema validation."""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('plan', type=Path, help='Private JSON; do not commit USB serials')
    args = parser.parse_args()
    try:
        if args.plan.stat().st_size > 16384:
            raise ValueError('Plan exceeds bounded input size')
        plan = json.loads(args.plan.read_text(encoding='utf-8'), object_pairs_hook=unique_object)
        result = validate_plan(plan)
    except (OSError, ValueError, TypeError):
        parser.exit(2, 'Physical pair plan rejected; check private input against schema.\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
