"""Shared permission allowlist. This is not a substitute for Android instrumentation."""
from xml.etree.ElementTree import Element

NETWORK = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
COMMON = {
    "android.permission.USE_BIOMETRIC", "android.permission.HIDE_OVERLAY_WINDOWS",
    "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_ADVERTISE",
    # Visible, opt-in foreground location; no background/foreground-service permission.
    "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
}
NAME = "{http://schemas.android.com/apk/res/android}name"


def manifest_permissions(root: Element) -> set[str]:
    permissions = set()
    for element in root:
        if element.tag in {"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"}:
            name = element.get(NAME)
            if not name:
                raise RuntimeError("Permission declaration has no name")
            permissions.add(name)
    return permissions


def validate_permissions(permissions: set[str], variant: str) -> None:
    if variant not in {"connected", "offline"}:
        raise ValueError("Unknown transport variant")
    if not permissions or any(not isinstance(value, str) for value in permissions):
        raise RuntimeError("Missing or malformed permission declarations")
    unexpected = permissions - COMMON - NETWORK
    expected_network = NETWORK if variant == "connected" else set()
    if unexpected or permissions & NETWORK != expected_network:
        raise RuntimeError(f"Unexpected permissions for {variant}: "
                           f"extra={sorted(unexpected)}, network={sorted(permissions & NETWORK)}")
