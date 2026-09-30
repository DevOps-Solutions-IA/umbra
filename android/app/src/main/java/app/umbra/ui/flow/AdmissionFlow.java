package app.umbra.ui.flow;

import app.umbra.admission.AdmissionCredential;
import app.umbra.admission.AdmissionRequest;
import app.umbra.admission.AdmissionService;
import app.umbra.admission.RealmConfig;
import app.umbra.core.AccessGate;
import app.umbra.data.Records;
import app.umbra.ui.model.AdmissionImport;

/**
 * Worker-thread bridge between the admission screens and {@link AdmissionService}. It reads public,
 * displayable fields and dispatches imported objects to the matching domain call. It never verifies
 * signatures, decides validity, reads private seeds or grants admission: the service does all of that.
 */
public final class AdmissionFlow {
    private AdmissionFlow() {}

    /** Public request fields shown before sharing; the private admission key never leaves the domain. */
    public record Request(String deviceFingerprint, String identityFingerprint, String realmId, long expiresAt, boolean expired, String wire) {}
    public record Snapshot(String state, String realmId, String authorityKeyId, Request request, Long credentialExpiresAt, String credentialWire) {}

    public static Snapshot read(AdmissionService admission, long nowSeconds) throws Exception {
        AdmissionService.State state = admission.getAdmissionState();
        RealmConfig realm = null;
        if (state != AdmissionService.State.UNCONFIGURED) realm = optional(admission::getRealmInfo);
        AdmissionRequest pending = state == AdmissionService.State.UNCONFIGURED ? null : optional(admission::pendingRequest);
        Request request = pending == null ? null : new Request(pending.deviceFingerprint(), pending.deviceId(), pending.realmId(),
            pending.expiresAt(), nowSeconds >= pending.expiresAt(), pending.wire());
        AdmissionCredential own = state == AdmissionService.State.ADMITTED ? admission.requireAdmission() : null;
        return new Snapshot(state.name(), realm == null ? null : realm.realmId(), realm == null ? null : realm.authorityKeyId(),
            request, own == null ? null : own.expiresAt(), own == null ? null : own.wire());
    }

    private interface Read<T> { T get() throws Exception; }
    /** Absent optional objects are reported by the domain as a SecurityException; a lock is never swallowed. */
    private static <T> T optional(Read<T> read) throws Exception {
        try { return read.get(); }
        catch (AccessGate.LockedException locked) { throw locked; }
        catch (SecurityException | IllegalArgumentException absent) { return null; }
    }

    /**
     * Member-side import after an explicit confirmation. {@code confirmed} must come from the user's
     * current action. Returns nothing: the caller re-reads the domain state to present the result.
     */
    public static void applyMember(Records records, AdmissionService admission, AdmissionImport.Parsed parsed, boolean confirmed) throws Exception {
        if (!confirmed) throw new SecurityException("Explicit confirmation required");
        switch (parsed.kind()) {
            case REALM -> admission.installRealmConfig(parsed.parts().get(0), true);
            case CREDENTIAL -> admission.installAdmissionCredential(parsed.parts().get(0));
            case REJECTION -> admission.installRejection(parsed.parts().get(0));
            case REVOCATION -> admission.applyRevocation(parsed.parts().get(0));
            // One transaction: the new credential and the revocation of the old one commit together or not at all.
            case RENEWAL_RESULT -> records.transaction(() -> {
                admission.installAdmissionCredential(parsed.parts().get(0));
                admission.applyRevocation(parsed.parts().get(1));
                return null;
            });
            default -> throw new IllegalArgumentException("Not a member admission object");
        }
    }

    /** Public fields of a credential chosen for revocation, decoded against the pinned realm for display only. */
    public record CredentialInfo(String identityFingerprint, long expiresAt) {}
    public static CredentialInfo describeCredential(AdmissionService admission, String wire) throws Exception {
        AdmissionCredential c = AdmissionCredential.decode(wire, admission.getRealmInfo());
        return new CredentialInfo(c.deviceId(), c.expiresAt());
    }
}
