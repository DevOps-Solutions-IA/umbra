package app.umbra.ui.flow;

import app.umbra.admission.AdmissionCredential;
import app.umbra.admission.AdmissionRequest;
import app.umbra.admission.AdmissionService;
import app.umbra.admission.RealmConfig;
import app.umbra.ui.model.AdmissionImport;
import java.util.List;

/**
 * Worker-thread bridge between the admission screens and {@link AdmissionService}
 * (contract UI_SECURITY_CONTENT_API_V1). It reads public, displayable fields through the domain's own
 * snapshot APIs and dispatches imported objects to the matching domain call. It never verifies signatures,
 * decides validity, reads private seeds, parses exception messages or grants admission: the service does all of that.
 */
public final class AdmissionFlow {
    private AdmissionFlow() {}

    /** Public request fields shown before sharing; the private admission key never leaves the domain. */
    public record Request(String requestId, String deviceFingerprint, String identityFingerprint, String realmId, long expiresAt, boolean expired, String wire) {}
    /** Authority-only public metadata from {@link AdmissionService#issuedCredentials()}. */
    public record Issued(String credentialId, String deviceId, long issuedAt, long expiresAt, boolean revoked) {}
    public record Snapshot(String state, String realmId, String authorityKeyId, Request request, Long requestExpiresAt,
                           Long credentialExpiresAt, String credentialWire, boolean authority, List<Issued> issued) {}

    /**
     * One consistent read: {@code status()} supplies state and both expiries; authority and the issued list are
     * snapshots for presentation only (the domain re-checks at each operation boundary).
     */
    public static Snapshot read(AdmissionService admission, long nowSeconds) throws Exception {
        AdmissionService.Status status = admission.status();
        AdmissionService.State state = status.state();
        RealmConfig realm = state == AdmissionService.State.UNCONFIGURED || state == AdmissionService.State.INVALID ? null : admission.getRealmInfo();
        // status() reports whether a local request exists (its expiry); only then is the request read.
        AdmissionRequest pending = realm == null || status.requestExpiresAt() == null ? null : admission.pendingRequest();
        Request request = pending == null ? null : new Request(pending.requestId(), pending.deviceFingerprint(), pending.deviceId(), pending.realmId(),
            pending.expiresAt(), nowSeconds >= pending.expiresAt(), pending.wire());
        // The own public credential is needed only to build a renewal request file (ADMITTED or EXPIRED).
        AdmissionCredential own = state == AdmissionService.State.ADMITTED ? admission.requireAdmission() : null;
        boolean authority = realm != null && admission.isAdmissionAuthority();
        List<Issued> issued = List.of();
        if (authority) {
            List<Issued> rows = new java.util.ArrayList<>();
            for (AdmissionService.IssuedCredential c : admission.issuedCredentials())
                rows.add(new Issued(c.credentialId(), c.deviceId(), c.issuedAt(), c.expiresAt(), c.revoked()));
            issued = List.copyOf(rows);
        }
        return new Snapshot(state.name(), realm == null ? null : realm.realmId(), realm == null ? null : realm.authorityKeyId(),
            request, status.requestExpiresAt(), status.credentialExpiresAt(), own == null ? null : own.wire(), authority, issued);
    }

    /**
     * Member-side import after an explicit confirmation. {@code confirmed} must come from the user's
     * current action. Renewal results use the domain's atomic pair import; the UI never splits it.
     */
    public static void applyMember(AdmissionService admission, AdmissionImport.Parsed parsed, boolean confirmed) throws Exception {
        if (!confirmed) throw new SecurityException("Explicit confirmation required");
        switch (parsed.kind()) {
            case REALM -> admission.installRealmConfig(parsed.parts().get(0), true);
            case CREDENTIAL -> admission.installAdmissionCredential(parsed.parts().get(0));
            case REJECTION -> admission.installRejection(parsed.parts().get(0));
            case REVOCATION -> admission.applyRevocation(parsed.parts().get(0));
            case RENEWAL_RESULT -> admission.installRenewal(parsed.parts().get(0), parsed.parts().get(1));
            default -> throw new IllegalArgumentException("Not a member admission object");
        }
    }

    /**
     * Authority approval of this same phone's own request: the domain approves and installs atomically
     * ({@link AdmissionService#approveAndInstallOwnAdmission}); the UI orchestrates no transaction.
     */
    public static void approveOwn(AdmissionService admission, AdmissionService.Review review, long ttlSeconds) throws Exception {
        admission.approveAndInstallOwnAdmission(review, true, ttlSeconds);
    }

    /** Local abandonment of the exact request being shown; not a remote recall or credential revocation. */
    public static void cancelRequest(AdmissionService admission, String expectedRequestId) throws Exception {
        admission.cancelPendingRequest(expectedRequestId);
    }

    /** Public fields of a credential chosen for revocation, decoded against the pinned realm for display only. */
    public record CredentialInfo(String identityFingerprint, long expiresAt) {}
    public static CredentialInfo describeCredential(AdmissionService admission, String wire) throws Exception {
        AdmissionCredential c = AdmissionCredential.decode(wire, admission.getRealmInfo());
        return new CredentialInfo(c.deviceId(), c.expiresAt());
    }
}
