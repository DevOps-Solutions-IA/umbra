package app.umbra.ui.model;

import java.util.Objects;

/** A destination in the app. {@code arg} is an opaque identifier (peer id, call id, section name). */
public record Route(Kind kind, String arg) {
    public enum Kind { LOCKED, ONBOARDING, HOME, CHAT, GROUP_CHAT, CONTACT, VERIFY, NEW_CHAT, NEW_GROUP, DEVICES, SETTINGS_SECTION, CALL, INCOMING_CALL, FEATURE_STATUS }

    public static Route of(Kind kind) { return new Route(kind, null); }
    public static Route of(Kind kind, String arg) { return new Route(kind, Objects.requireNonNull(arg)); }

    /** Routes that expose conversation content or identity material. */
    public boolean sensitive() { return kind != Kind.LOCKED && kind != Kind.ONBOARDING; }
}
