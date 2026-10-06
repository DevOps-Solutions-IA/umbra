package app.umbra.admission;

import app.umbra.connectivity.ConnectivityService;
import app.umbra.core.Bytes;
import app.umbra.crypto.SignalStore;
import app.umbra.data.Records;
import app.umbra.transport.RelayClient;
import org.json.JSONObject;

/** Offline import of public configuration. No network, enrollment, registration or unlock.
 * Only AdmissionService constructs this adapter, with its own Records and connectivity.
 * Persistent values inherit the encrypted Vault's transaction and authorization guarantees;
 * a synthetic Records implementation does not demonstrate encrypted Android persistence. */
public final class ProvisioningService {
    private final Records records;
    private final AdmissionService admission;
    ProvisioningService(Records records, AdmissionService admission) { this.records=records; this.admission=admission; }

    /** Snapshot of separate facts, never proof of Internet reachability or human identity. */
    public record Status(boolean configured, AdmissionService.State admissionState, boolean registered,
                         String exactOrigin, RealmConfig realm) {
        @Override public String toString() { return "ProvisioningStatus[redacted]"; }
    }
    public final class Review {
        private final ProvisioningService owner=ProvisioningService.this;
        private final Runnable authorization;
        private final String origin;
        private final RealmConfig realm;
        private Review(Runnable authorization,String origin,RealmConfig realm) {
            this.authorization=authorization; this.origin=origin; this.realm=realm;
        }
        public String exactOrigin() { authorization.run(); return origin; }
        public RealmConfig realm() { authorization.run(); return realm; }
        @Override public String toString() { return "ProvisioningReview[redacted]"; }
    }
    private static ProvisioningException rejected(ProvisioningException.Code code) { return new ProvisioningException(code); }
    private static ProvisioningException invalid() { return rejected(ProvisioningException.Code.INVALID_CONFIGURATION); }
    private Runnable lease() { Runnable check=records.authorization(); check.run(); return check; }

    /** Require the exact origin that admission challenges will hash. Never silently trim,
     * remove a slash, fold case, or replace an explicit port with its default. No DNS occurs. */
    private static String origin(String input) throws Exception {
        if(input==null || input.isEmpty() || input.length()>2048) throw invalid();
        try { if(!input.equals(RelayClient.validate(input))) throw invalid(); }
        catch(IllegalArgumentException | java.net.URISyntaxException malformed) { throw invalid(); }
        return input;
    }
    private JSONObject profile() {
        byte[] stored=records.get("meta","profile");
        if(stored==null) throw invalid();
        try {
            JSONObject profile=new JSONObject(Bytes.text(stored));
            SignalStore signal=new SignalStore(records);
            if(!Bytes.identity(signal.getIdentityKeyPair().getPublicKey().serialize()).equals(profile.getString("id"))) throw invalid();
            int registration=signal.getLocalRegistrationId();
            if(registration<1 || registration>16380 || !(profile.get("registered") instanceof Boolean) ||
                    !(profile.get("online") instanceof Boolean)) throw invalid();
            profile.getString("relay");
            return profile;
        } catch(org.json.JSONException | NumberFormatException malformed) { throw invalid(); }
    }
    private Status snapshot(JSONObject profile) throws Exception {
        String current=profile.getString("relay");
        if(!current.isEmpty()) origin(current);
        AdmissionService.State state=admission.getAdmissionState();
        if(state==AdmissionService.State.INVALID) throw invalid();
        RealmConfig realm=state==AdmissionService.State.UNCONFIGURED?null:admission.getRealmInfo();
        boolean registered=profile.getBoolean("registered");
        if(current.isEmpty() && registered) throw invalid();
        return new Status(!current.isEmpty() && realm!=null,state,registered,current,realm);
    }
    private void compatible(Status saved,String origin,RealmConfig realm) {
        if(!saved.exactOrigin().isEmpty() && !saved.exactOrigin().equals(origin) ||
                saved.realm()!=null && !saved.realm().equals(realm))
            throw rejected(ProvisioningException.Code.BINDING_MISMATCH);
    }
    private void offline() {
        ConnectivityService connectivity=admission.connectivity();
        ConnectivityService.State state=connectivity.getConnectivityState();
        if(connectivity.cleanupFailed() || connectivity.isNearbySessionAllowed() ||
                state==ConnectivityService.State.CONNECTING || state==ConnectivityService.State.CONNECTED ||
                state==ConnectivityService.State.DISCONNECTING)
            throw rejected(ProvisioningException.Code.CONNECTIVITY_ACTIVE);
    }
    public Status status() throws Exception {
        Runnable check=lease();
        return records.transaction(() -> { check.run(); Status status=snapshot(profile()); check.run(); return status; });
    }
    /** Parses existing public RealmConfig only. The caller must verify this exact tuple via
     * an authenticated, out-of-band source; parsing and the review do not authenticate it. */
    public Review review(String exactOrigin,String publicRealmConfig) throws Exception {
        Runnable check=lease();
        String proposedOrigin=origin(exactOrigin); RealmConfig proposedRealm=RealmConfig.decode(publicRealmConfig);
        return records.transaction(() -> {
            check.run(); offline(); compatible(snapshot(profile()),proposedOrigin,proposedRealm);
            check.run(); return new Review(check,proposedOrigin,proposedRealm);
        });
    }
    /** Confirmation attests explicit verification of the exact Review's source; it is not a
     * signature, relay response, device admission, or registration. A stale review is never
     * refreshed with a new vault lease. Origin/authority changes need a separate future API.
     * Connectivity consent has an independent process monitor. The offline checks before
     * and after the write do not provide exclusive coordination with a simultaneous explicit
     * connect, which requires its own consent and lease. Installation grants no connectivity
     * and never changes an existing origin or realm binding; status is only a snapshot. */
    public void install(Review review,boolean sourceAuthenticatedConfirmed) throws Exception {
        if(review==null || review.owner!=this) throw rejected(ProvisioningException.Code.REVIEW_UNAVAILABLE);
        review.authorization.run();
        records.transaction(() -> {
            review.authorization.run();
            if(!sourceAuthenticatedConfirmed) throw rejected(ProvisioningException.Code.SOURCE_CONFIRMATION_REQUIRED);
            offline(); JSONObject profile=profile(); compatible(snapshot(profile),review.origin,review.realm);
            admission.installRealmConfig(review.realm.encode(),true);
            if(!profile.getString("relay").equals(review.origin)) {
                profile.put("relay",review.origin);
                records.put("meta","profile",Bytes.utf8(profile.toString()));
            }
            review.authorization.run(); offline(); return null;
        });
    }
    @Override public String toString() { return "ProvisioningService[redacted]"; }
}
