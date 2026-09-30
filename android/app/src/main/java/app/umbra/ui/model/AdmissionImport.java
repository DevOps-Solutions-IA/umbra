package app.umbra.ui.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Routes an imported public admission file to the matching domain operation by its documented wire
 * prefix (docs/ADMISSION.md). This is format dispatch only: it never verifies a signature, decides
 * validity or admits anyone. Every object is re-validated by {@code AdmissionService}.
 *
 * <p>A file carries one object per line (LF). Two-line files bundle objects that belong to one
 * administrative decision: a renewal request plus the current credential, or a renewal result
 * (new credential plus the revocation of the old one). No other combination is accepted.
 */
public final class AdmissionImport {
    private AdmissionImport() {}

    public static final int MAX_WIRE = 4096, MAX_LINES = 2;
    public static final String REALM = "umbra:realm:1:", REQUEST = "umbra:admission:request:1:",
        CREDENTIAL = "umbra:admission:credential:1:", REJECTION = "umbra:admission:rejection:1:",
        REVOCATION = "umbra:admission:revocation:1:";

    public enum Kind {
        /** Public realm configuration; importing it never admits. */ REALM,
        /** A device's signed request (reviewed only by the authority). */ REQUEST,
        /** Renewal request plus the device's current public credential (authority). */ RENEWAL_REQUEST,
        /** Signed credential for this device. */ CREDENTIAL,
        /** Signed rejection of this device's request. */ REJECTION,
        /** Signed revocation (own or a peer's credential). */ REVOCATION,
        /** New credential plus revocation of the old one, produced by an explicit renewal. */ RENEWAL_RESULT,
        UNKNOWN
    }
    public record Parsed(Kind kind, List<String> parts) {}

    public static Parsed classify(String text) {
        if (text == null) return unknown();
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        if (body.isEmpty() || body.indexOf('\r') >= 0) return unknown();
        String[] lines = body.split("\n", -1);
        if (lines.length > MAX_LINES) return unknown();
        List<String> parts = new ArrayList<>();
        for (String line : lines) { if (line.isEmpty() || line.length() > MAX_WIRE || line.indexOf(' ') >= 0) return unknown(); parts.add(line); }
        if (parts.size() == 1) {
            String w = parts.get(0);
            Kind k = w.startsWith(REALM) ? Kind.REALM : w.startsWith(REQUEST) ? Kind.REQUEST : w.startsWith(CREDENTIAL) ? Kind.CREDENTIAL
                : w.startsWith(REJECTION) ? Kind.REJECTION : w.startsWith(REVOCATION) ? Kind.REVOCATION : Kind.UNKNOWN;
            return k == Kind.UNKNOWN ? unknown() : new Parsed(k, List.copyOf(parts));
        }
        String a = parts.get(0), b = parts.get(1);
        if (a.startsWith(REQUEST) && b.startsWith(CREDENTIAL)) return new Parsed(Kind.RENEWAL_REQUEST, List.copyOf(parts));
        if (a.startsWith(CREDENTIAL) && b.startsWith(REVOCATION)) return new Parsed(Kind.RENEWAL_RESULT, List.copyOf(parts));
        return unknown();
    }
    private static Parsed unknown() { return new Parsed(Kind.UNKNOWN, List.of()); }

    /** Export text for one or two public objects, in the order {@link #classify} expects. */
    public static String file(String... wires) {
        if (wires.length == 0 || wires.length > MAX_LINES) throw new IllegalArgumentException("Unsupported admission export");
        return String.join("\n", wires) + "\n";
    }

    /** Human label for a confirmation dialog; never claims validity. */
    public static String describe(Kind kind) {
        return switch (kind) {
            case REALM -> "Entorno";
            case REQUEST -> "Solicitud de admisión";
            case RENEWAL_REQUEST -> "Solicitud de renovación";
            case CREDENTIAL -> "Credencial firmada";
            case REJECTION -> "Rechazo firmado";
            case REVOCATION -> "Revocación firmada";
            case RENEWAL_RESULT -> "Renovación firmada";
            case UNKNOWN -> "Archivo no reconocido";
        };
    }

    /** Short, non-secret fingerprint grouping for display (public keys and identifiers only). */
    public static String group(String hex, int groups) {
        if (hex == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length() && i < groups * 4; i += 4) {
            if (out.length() > 0) out.append(' ');
            out.append(hex, i, Math.min(hex.length(), i + 4));
        }
        return out.toString();
    }
}
