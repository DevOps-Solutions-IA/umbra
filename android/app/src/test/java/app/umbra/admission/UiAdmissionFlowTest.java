package app.umbra.admission;

import app.umbra.connectivity.ConnectivityService;
import app.umbra.ui.flow.AdmissionFlow;
import app.umbra.ui.model.AdmissionImport;
import app.umbra.ui.model.AdmissionPresentation;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * UI admission flow against the real {@link AdmissionService} (JVM, libsignal, synthetic memory records with
 * lock epochs). Exercises the exact bridge the Activity uses; not a claim of SQLite/Keystore or radio coverage.
 */
public class UiAdmissionFlowTest {
    private static AdmissionFlow.Snapshot read(AdmissionServiceTest.Device d) throws Exception { return AdmissionFlow.read(d.admission, d.clock.get()); }
    private static AdmissionPresentation present(AdmissionFlow.Snapshot s) {
        return AdmissionPresentation.of(s.state(), s.request() != null, s.request() != null && s.request().expired());
    }
    private static AdmissionImport.Parsed parsed(String... wires) { return AdmissionImport.classify(AdmissionImport.file(wires)); }

    @Test public void unconfiguredRealmImportConfiguresWithoutAdmitting() throws Exception {
        AdmissionServiceTest.Device admin = new AdmissionServiceTest.Device(), device = new AdmissionServiceTest.Device();
        RealmConfig realm = admin.admission.createAdmissionRealm(true);
        AdmissionFlow.Snapshot before = read(device);
        assertEquals("UNCONFIGURED", before.state());
        assertNull(before.realmId()); assertNull(before.request());
        assertTrue(present(before).canImportRealm());
        AdmissionFlow.applyMember(device.admission, parsed(realm.encode()), true);
        AdmissionFlow.Snapshot after = read(device);
        assertEquals("NOT_ADMITTED", after.state());
        assertEquals(realm.realmId(), after.realmId());
        assertEquals(realm.authorityKeyId(), after.authorityKeyId());
        assertFalse(present(after).admitted());
        assertNull(after.credentialExpiresAt());
    }
    @Test public void importRequiresTheCurrentExplicitConfirmation() throws Exception {
        AdmissionServiceTest.Device admin = new AdmissionServiceTest.Device(), device = new AdmissionServiceTest.Device();
        RealmConfig realm = admin.admission.createAdmissionRealm(true);
        assertThrows(SecurityException.class, () -> AdmissionFlow.applyMember(device.admission, parsed(realm.encode()), false));
        assertEquals("UNCONFIGURED", read(device).state());
        assertThrows(IllegalArgumentException.class, () -> AdmissionFlow.applyMember(device.admission, AdmissionImport.classify("umbra:invite:x"), true));
    }
    @Test public void authorityMismatchIsAnImportFailureThatKeepsThePreviousPin() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        RealmConfig foreign = new AdmissionServiceTest.Device().admission.createAdmissionRealm(true);
        assertThrows(SecurityException.class, () -> AdmissionFlow.applyMember(s.a.admission, parsed(foreign.encode()), true));
        AdmissionFlow.Snapshot after = read(s.a);
        assertEquals("NOT_ADMITTED", after.state());
        assertEquals(s.realm.realmId(), after.realmId());
        assertEquals(s.realm.authorityKeyId(), after.authorityKeyId());
    }
    @Test public void generatedRequestIsPendingAndExportableButNotAdmitted() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionRequest request = s.a.admission.createAdmissionRequest();
        AdmissionFlow.Snapshot snap = read(s.a);
        assertEquals("REQUEST_PENDING", snap.state());
        assertEquals(request.deviceFingerprint(), snap.request().deviceFingerprint());
        assertEquals(request.deviceId(), snap.request().identityFingerprint());
        assertEquals(s.realm.realmId(), snap.request().realmId());
        assertFalse(snap.request().expired());
        AdmissionPresentation p = present(snap);
        assertEquals("Solicitando acceso", p.title());
        assertFalse(p.admitted());
        AdmissionImport.Parsed exported = parsed(snap.request().wire());
        assertEquals(AdmissionImport.Kind.REQUEST, exported.kind());
        assertEquals(request.wire(), exported.parts().get(0));
        // A second request while one is pending is refused by the domain, not by the UI.
        assertThrows(SecurityException.class, s.a.admission::createAdmissionRequest);
    }
    @Test public void rejectionThenCredentialAreAppliedByTheDomain() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionRequest first = s.a.admission.createAdmissionRequest();
        AdmissionRejection rejection = s.admin.admission.rejectAdmission(s.admin.admission.reviewAdmissionRequest(first.wire()), true);
        AdmissionFlow.applyMember(s.a.admission, parsed(rejection.wire()), true);
        AdmissionFlow.Snapshot rejected = read(s.a);
        assertEquals("REJECTED", rejected.state());
        assertTrue(present(rejected).canCreateRequest());
        assertFalse(present(rejected).admitted());
        AdmissionRequest second = s.a.admission.createAdmissionRequest();
        AdmissionCredential credential = s.admin.admission.approveAdmission(s.admin.admission.reviewAdmissionRequest(second.wire()), true, 3600);
        AdmissionFlow.applyMember(s.a.admission, parsed(credential.wire()), true);
        AdmissionFlow.Snapshot admitted = read(s.a);
        assertEquals("ADMITTED", admitted.state());
        assertTrue(present(admitted).admitted());
        assertEquals(Long.valueOf(credential.expiresAt()), admitted.credentialExpiresAt());
        assertEquals(credential.wire(), admitted.credentialWire());
        assertNull(admitted.request());
    }
    @Test public void aCredentialForAnotherDeviceIsRejectedAndChangesNothing() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        s.a.admission.createAdmissionRequest();
        AdmissionRequest other = s.b.admission.createAdmissionRequest();
        AdmissionCredential forB = s.admin.admission.approveAdmission(s.admin.admission.reviewAdmissionRequest(other.wire()), true, 3600);
        assertThrows(SecurityException.class, () -> AdmissionFlow.applyMember(s.a.admission, parsed(forB.wire()), true));
        assertEquals("REQUEST_PENDING", read(s.a).state());
    }
    @Test public void requestExpiryAndCredentialExpiryAreDistinguished() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        s.a.admission.createAdmissionRequest();
        s.a.clock.addAndGet(AdmissionRequest.MAX_TTL);
        AdmissionFlow.Snapshot requestExpired = read(s.a);
        assertEquals("EXPIRED", requestExpired.state());
        assertTrue(requestExpired.request().expired());
        assertEquals("Solicitud vencida", present(requestExpired).title());

        AdmissionRequest request = s.b.admission.createAdmissionRequest();
        AdmissionCredential shortLived = s.admin.admission.approveAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()), true, 60);
        AdmissionFlow.applyMember(s.b.admission, parsed(shortLived.wire()), true);
        assertEquals("ADMITTED", read(s.b).state());
        s.b.clock.addAndGet(61);
        AdmissionFlow.Snapshot credentialExpired = read(s.b);
        assertEquals("EXPIRED", credentialExpired.state());
        assertNull(credentialExpired.request());
        assertEquals("Acceso vencido", present(credentialExpired).title());
        assertTrue("renewal needs a new request, never automatic", present(credentialExpired).canCreateRequest());
    }
    @Test public void revocationIsTerminalOffersNoBypassAndKeepsHistory() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionCredential credential = s.enroll(s.a);
        byte[] identity = s.a.db.get("meta", "identity");
        AdmissionRevocation revocation = s.admin.admission.revokeAdmission(credential.wire(), true, "device_lost");
        AdmissionFlow.applyMember(s.a.admission, parsed(revocation.wire()), true);
        AdmissionFlow.Snapshot revoked = read(s.a);
        assertEquals("REVOKED", revoked.state());
        AdmissionPresentation p = present(revoked);
        assertFalse(p.admitted() || p.canCreateRequest() || p.canImportDecision() || p.canImportRealm());
        assertArrayEquals("revocation does not delete local identity", identity, s.a.db.get("meta", "identity"));
        assertFalse(s.a.admission.connectivity().canConnect());
    }
    @Test public void invalidStoredAdmissionIsReportedNotRepaired() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        s.enroll(s.a);
        s.a.db.rows.put("admission:credential", "umbra:admission:credential:1:broken.broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AdmissionFlow.Snapshot invalid = read(s.a);
        assertEquals("INVALID", invalid.state());
        assertFalse(present(invalid).canCreateRequest() || present(invalid).canImportRealm());
        assertEquals("umbra:admission:credential:1:broken.broken", new String(s.a.db.rows.get("admission:credential"), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void renewalRoundTripUsesTheDomainRenewalAndRevokesTheOldCredential() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionCredential old = s.enroll(s.a);
        s.a.admission.createAdmissionRequest();
        AdmissionFlow.Snapshot pending = read(s.a);
        assertEquals("ADMITTED", pending.state());
        assertTrue(present(pending).canExportRequest());
        AdmissionImport.Parsed renewalRequest = parsed(pending.request().wire(), pending.credentialWire());
        assertEquals(AdmissionImport.Kind.RENEWAL_REQUEST, renewalRequest.kind());
        AdmissionService.Review review = s.admin.admission.reviewAdmissionRequest(renewalRequest.parts().get(0));
        AdmissionService.Renewal renewal = s.admin.admission.renewAdmission(review, renewalRequest.parts().get(1), true, 3600);
        AdmissionImport.Parsed result = parsed(renewal.credential().wire(), renewal.revocation().wire());
        assertEquals(AdmissionImport.Kind.RENEWAL_RESULT, result.kind());
        AdmissionFlow.applyMember(s.a.admission, result, true);
        AdmissionFlow.Snapshot renewed = read(s.a);
        assertEquals("ADMITTED", renewed.state());
        assertEquals(renewal.credential().wire(), renewed.credentialWire());
        assertNotNull(s.a.db.get("admission-revoked", old.credentialId()));
    }
    @Test public void renewalResultIsAtomicWhenTheRevocationIsRejected() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionCredential old = s.enroll(s.a);
        s.a.admission.createAdmissionRequest();
        AdmissionFlow.Snapshot pending = read(s.a);
        AdmissionService.Renewal renewal = s.admin.admission.renewAdmission(
            s.admin.admission.reviewAdmissionRequest(pending.request().wire()), pending.credentialWire(), true, 3600);
        AdmissionImport.Parsed broken = parsed(renewal.credential().wire(), "umbra:admission:revocation:1:broken.broken");
        assertEquals(AdmissionImport.Kind.RENEWAL_RESULT, broken.kind());
        assertThrows(Exception.class, () -> AdmissionFlow.applyMember(s.a.admission, broken, true));
        AdmissionFlow.Snapshot unchanged = read(s.a);
        assertEquals("ADMITTED", unchanged.state());
        assertEquals("the new credential was rolled back with the failed revocation", old.wire(), unchanged.credentialWire());
        assertNotNull("the renewal request is still pending", unchanged.request());
    }
    @Test public void snapshotUsesDomainStatusExpiriesAndAuthoritySnapshot() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionRequest request = s.a.admission.createAdmissionRequest();
        AdmissionFlow.Snapshot pending = read(s.a);
        assertEquals("REQUEST_PENDING", pending.state());
        assertEquals(Long.valueOf(request.expiresAt()), pending.requestExpiresAt());
        assertEquals(request.requestId(), pending.request().requestId());
        assertNull(pending.credentialExpiresAt());
        assertFalse("a member is not the authority", pending.authority());
        assertTrue(pending.issued().isEmpty());
        AdmissionCredential c = s.admin.admission.approveAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()), true, 3600);
        AdmissionFlow.applyMember(s.a.admission, parsed(c.wire()), true);
        AdmissionFlow.Snapshot admitted = read(s.a);
        assertEquals(Long.valueOf(c.expiresAt()), admitted.credentialExpiresAt());
        AdmissionFlow.Snapshot authority = read(s.admin);
        assertTrue(authority.authority());
        assertEquals(1, authority.issued().size());
        assertEquals(c.credentialId(), authority.issued().get(0).credentialId());
        assertFalse(authority.issued().get(0).revoked());
        s.admin.admission.revokeAdmission(c.wire(), true, "device_lost");
        assertTrue("issued list reports the local revocation flag", read(s.admin).issued().get(0).revoked());
    }
    @Test public void cancellationIsLocalAndBoundToTheShownRequest() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionRequest request = s.a.admission.createAdmissionRequest();
        assertThrows("a different request id is refused", SecurityException.class, () -> AdmissionFlow.cancelRequest(s.a.admission, "stale-request-id"));
        assertEquals("REQUEST_PENDING", read(s.a).state());
        AdmissionFlow.cancelRequest(s.a.admission, request.requestId());
        AdmissionFlow.Snapshot after = read(s.a);
        assertEquals("NOT_ADMITTED", after.state());
        assertNull(after.request());
        assertNull(after.requestExpiresAt());
    }
    @Test public void ownApprovalIsAtomicInTheDomain() throws Exception {
        AdmissionServiceTest.Device authority = new AdmissionServiceTest.Device();
        authority.admission.createAdmissionRealm(true);
        AdmissionRequest own = authority.admission.createAdmissionRequest();
        AdmissionService.Review review = authority.admission.reviewAdmissionRequest(own.wire());
        AdmissionFlow.approveOwn(authority.admission, review, 3600);
        AdmissionFlow.Snapshot admitted = read(authority);
        assertEquals("ADMITTED", admitted.state());
        assertTrue(admitted.authority());
        assertNull("the pending request was consumed with the install", admitted.request());
        assertThrows("the same review cannot approve twice", SecurityException.class, () -> AdmissionFlow.approveOwn(authority.admission, review, 3600));
    }
    @Test public void admissionIsPerDeviceStore() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        s.enroll(s.a);
        assertEquals("ADMITTED", read(s.a).state());
        assertEquals("another device of the same realm stays unadmitted", "NOT_ADMITTED", read(s.b).state());
    }
    @Test public void unlockingAndAdmissionNeverConnectOrStartNearby() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        s.enroll(s.a);
        ConnectivityService connectivity = s.a.admission.connectivity();
        assertEquals(ConnectivityService.State.LOCKED_PRIVATE, connectivity.getConnectivityState());
        connectivity.vaultUnlocked();
        assertEquals(ConnectivityService.State.UNLOCKED_OFFLINE, connectivity.getConnectivityState());
        read(s.a);
        assertFalse(connectivity.isNetworkSessionAllowed());
        assertFalse(connectivity.isNearbySessionAllowed());
        assertThrows(SecurityException.class, () -> connectivity.connect("https://relay.example.test", false));
        assertEquals(ConnectivityService.State.UNLOCKED_OFFLINE, connectivity.getConnectivityState());
        assertTrue("admission creates no contact or verification", s.a.engine.contacts().isEmpty());
    }
    @Test public void lockedRecordsAreNeverReportedAsAbsentAdmission() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        s.a.admission.createAdmissionRequest();
        s.a.db.lock();
        assertThrows(SecurityException.class, () -> read(s.a));
        s.a.db.unlock();
        assertEquals("REQUEST_PENDING", read(s.a).state());
    }
    @Test public void contactInvitationDoesNotAdmit() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        String invitation = new app.umbra.pairing.PairingService(s.a.db).createInvitation(3600);
        assertEquals(AdmissionImport.Kind.UNKNOWN, AdmissionImport.classify(invitation).kind());
        assertEquals("NOT_ADMITTED", read(s.a).state());
    }
    @Test public void describedCredentialIsDecodedAgainstThePinnedRealmForDisplayOnly() throws Exception {
        AdmissionServiceTest.Setup s = new AdmissionServiceTest.Setup();
        AdmissionCredential c = s.enroll(s.a);
        AdmissionFlow.CredentialInfo info = AdmissionFlow.describeCredential(s.admin.admission, c.wire());
        assertEquals(c.deviceId(), info.identityFingerprint());
        assertEquals(c.expiresAt(), info.expiresAt());
        AdmissionServiceTest.Device stranger = new AdmissionServiceTest.Device();
        stranger.admission.createAdmissionRealm(true);
        assertThrows(SecurityException.class, () -> AdmissionFlow.describeCredential(stranger.admission, c.wire()));
    }
}
