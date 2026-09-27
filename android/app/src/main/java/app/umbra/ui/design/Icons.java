package app.umbra.ui.design;

import app.umbra.R;
import app.umbra.ui.model.Glyph;

/** Maps Android-free {@link Glyph}s to the UMBRA vector icon set. */
public final class Icons {
    private Icons() {}

    public static int res(Glyph glyph) {
        return switch (glyph) {
            case BACK -> R.drawable.ic_back;
            case CLOSE -> R.drawable.ic_close;
            case CHECK -> R.drawable.ic_check;
            case ADD -> R.drawable.ic_add;
            case SEARCH -> R.drawable.ic_search;
            case LOCK -> R.drawable.ic_lock;
            case CHAT -> R.drawable.ic_chat;
            case GROUP -> R.drawable.ic_group;
            case PERSON -> R.drawable.ic_person;
            case PERSON_ADD -> R.drawable.ic_person_add;
            case CALL -> R.drawable.ic_call;
            case CALL_END -> R.drawable.ic_call_end;
            case VIDEO -> R.drawable.ic_video;
            case VIDEO_OFF -> R.drawable.ic_video_off;
            case MIC -> R.drawable.ic_mic;
            case MIC_OFF -> R.drawable.ic_mic_off;
            case SPEAKER -> R.drawable.ic_speaker;
            case VOICE -> R.drawable.ic_voice;
            case CAMERA_SWITCH -> R.drawable.ic_camera_switch;
            case LOCATION -> R.drawable.ic_location;
            case FILE -> R.drawable.ic_file;
            case PHOTO -> R.drawable.ic_photo;
            case ATTACH -> R.drawable.ic_attach;
            case SEND -> R.drawable.ic_send;
            case DEVICES -> R.drawable.ic_devices;
            case SETTINGS -> R.drawable.ic_settings;
            case SHIELD -> R.drawable.ic_shield;
            case SHIELD_CHECK -> R.drawable.ic_shield_check;
            case WARNING -> R.drawable.ic_warning;
            case BLOCK -> R.drawable.ic_block;
            case INFO -> R.drawable.ic_info;
            case BLUETOOTH -> R.drawable.ic_bluetooth;
            case CLOUD -> R.drawable.ic_cloud;
            case CLOUD_OFF -> R.drawable.ic_cloud_off;
            case QR -> R.drawable.ic_qr;
            case MORE -> R.drawable.ic_more;
            case RETRY -> R.drawable.ic_retry;
            case TIMER -> R.drawable.ic_timer;
            case TRASH -> R.drawable.ic_trash;
            case CHEVRON -> R.drawable.ic_chevron;
            case STOP -> R.drawable.ic_stop;
            case EYE -> R.drawable.ic_eye;
            case EYE_OFF -> R.drawable.ic_eye_off;
            case BELL_OFF -> R.drawable.ic_bell_off;
            case REPLY -> R.drawable.ic_reply;
            case UNLOCK -> R.drawable.ic_unlock;
            case EMERGENCY_LOCK -> R.drawable.ic_emergency_lock;
            case VAULT_LOCKED -> R.drawable.ic_vault_locked;
            case VAULT_UNLOCKED -> R.drawable.ic_vault_unlocked;
            case PASSWORD -> R.drawable.ic_password;
            case CHANGE_PASSWORD -> R.drawable.ic_change_password;
            case VERIFIED -> R.drawable.ic_verified;
            case IDENTITY_CHANGED -> R.drawable.ic_identity_changed;
            case PERSON_BLOCK -> R.drawable.ic_person_block;
            case NETWORK_OFF -> R.drawable.ic_network_off;
            case OFFLINE_BLUETOOTH -> R.drawable.ic_offline_bluetooth;
            case CAMERA -> R.drawable.ic_camera;
            case CONTACTS -> R.drawable.ic_contacts;
            case LOCATION_PRECISE -> R.drawable.ic_location_precise;
            case LOCATION_APPROX -> R.drawable.ic_location_approx;
            case LOCATION_ZONE -> R.drawable.ic_location_zone;
            case LOCATION_LIVE -> R.drawable.ic_location_live;
            case LOCATION_OFF -> R.drawable.ic_location_off;
            case DEVICE_CURRENT -> R.drawable.ic_device_current;
            case DEVICE_AUTHORIZED -> R.drawable.ic_device_authorized;
            case DEVICE_PENDING -> R.drawable.ic_device_pending;
            case DEVICE_REVOKED -> R.drawable.ic_device_revoked;
            case DEVICE_OFFLINE -> R.drawable.ic_device_offline;
        };
    }
}
