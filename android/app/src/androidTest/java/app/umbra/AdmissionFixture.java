package app.umbra;

import app.umbra.admission.*;
import app.umbra.crypto.Engine;

/** Test-only explicit synthetic admission; no production override or shared user secret. */
public final class AdmissionFixture {
    private static final AdmissionService AUTHORITY;
    private static final RealmConfig REALM;
    static {
        try {
            DeviceMemoryRecords records=new DeviceMemoryRecords(); Engine engine=new Engine(records); engine.initialize("Synthetic authority");
            AUTHORITY=engine.admission(); REALM=AUTHORITY.createAdmissionRealm(true);
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private AdmissionFixture() {}
    public static synchronized void enroll(Engine engine) throws Exception {
        AdmissionService member=engine.admission(); member.installRealmConfig(REALM.encode(),true);
        AdmissionRequest request=member.createAdmissionRequest();
        AdmissionCredential credential=AUTHORITY.approveAdmission(AUTHORITY.reviewAdmissionRequest(request.wire()),true,AdmissionCredential.MAX_TTL);
        member.installAdmissionCredential(credential.wire());
    }
}
