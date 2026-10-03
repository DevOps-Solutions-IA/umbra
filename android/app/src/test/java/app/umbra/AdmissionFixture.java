package app.umbra;

import app.umbra.admission.*;
import app.umbra.crypto.Engine;

/** Test-only explicit synthetic admission; no production override or shared user secret. */
public final class AdmissionFixture {
    private static final Authority SHARED;
    static {
        try {
            SHARED=new Authority(new MemoryRecords());
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private AdmissionFixture() {}
    public static void enroll(Engine engine) throws Exception { SHARED.enroll(engine); }
    /** Package-private fixture scope permits deterministic expiry tests without sleeping. */
    static final class Authority {
        final MemoryRecords records;
        final AdmissionService service;
        final RealmConfig realm;
        Authority(MemoryRecords records) throws Exception {
            this.records=records; Engine engine=new Engine(records); engine.initialize("Synthetic authority");
            service=engine.admission(); realm=service.createAdmissionRealm(true);
        }
        synchronized void enroll(Engine engine) throws Exception {
            // Each synthetic setup operation gets a new authority epoch, never an old review.
            records.reauthorizeSyntheticSession();
            AdmissionService member=engine.admission(); member.installRealmConfig(realm.encode(),true);
            AdmissionRequest request=member.createAdmissionRequest();
            AdmissionCredential credential=service.approveAdmission(service.reviewAdmissionRequest(request.wire()),true,AdmissionCredential.MAX_TTL);
            member.installAdmissionCredential(credential.wire());
        }
    }
}
