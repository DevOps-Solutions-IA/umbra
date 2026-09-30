package app.umbra.ui.model;

/**
 * Android-free icon identifiers; the design layer maps each one to a vector drawable. Icons carry no
 * meaning by color alone and never replace the text label or content description of a control.
 * VAULT_*, PASSWORD and CHANGE_PASSWORD now back the real vault password flow. EMERGENCY_LOCK is
 * still prepared only: its backend does not exist in this build and showing it implies nothing.
 */
public enum Glyph {
    BACK, CLOSE, CHECK, ADD, SEARCH, LOCK, CHAT, GROUP, PERSON, PERSON_ADD, CALL, CALL_END, VIDEO, VIDEO_OFF,
    MIC, MIC_OFF, SPEAKER, VOICE, CAMERA_SWITCH, LOCATION, FILE, PHOTO, ATTACH, SEND, DEVICES, SETTINGS,
    SHIELD, SHIELD_CHECK, WARNING, BLOCK, INFO, BLUETOOTH, CLOUD, CLOUD_OFF, QR, MORE, RETRY, TIMER, TRASH,
    CHEVRON, STOP, EYE, EYE_OFF, BELL_OFF, REPLY,
    // States and future (prepared-only) features.
    UNLOCK, EMERGENCY_LOCK, VAULT_LOCKED, VAULT_UNLOCKED, PASSWORD, CHANGE_PASSWORD, VERIFIED, IDENTITY_CHANGED, PERSON_BLOCK,
    NETWORK_OFF, OFFLINE_BLUETOOTH, CAMERA, CONTACTS, LOCATION_PRECISE, LOCATION_APPROX, LOCATION_ZONE, LOCATION_LIVE, LOCATION_OFF,
    DEVICE_CURRENT, DEVICE_AUTHORIZED, DEVICE_PENDING, DEVICE_REVOKED, DEVICE_OFFLINE
}
