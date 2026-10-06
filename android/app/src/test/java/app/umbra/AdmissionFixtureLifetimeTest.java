package app.umbra;

import app.umbra.core.AccessGate;
import app.umbra.crypto.Engine;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real AccessGate monotonic boundaries and signed admission; memory fixture only.
 * No shortened production policy, wall-clock sleeps or Android authentication claim. */
public final class AdmissionFixtureLifetimeTest {
    private static final long LIFETIME = TimeUnit.MINUTES.toNanos(4);
    private static Engine member() throws Exception {
        Engine engine=new Engine(new MemoryRecords()); engine.initialize("Synthetic lifetime member"); return engine;
    }
    @Test public void authorityGateExpiresExactlyAndFreshAuthorizationNeverRevivesOldLease() throws Exception {
        AtomicLong clock=new AtomicLong(1);
        MemoryRecords records=new MemoryRecords(new AccessGate(clock::get,LIFETIME));
        AdmissionFixture.Authority authority=new AdmissionFixture.Authority(records);
        Runnable old=records.authorization(); clock.addAndGet(LIFETIME-1); old.run();
        clock.incrementAndGet();
        assertThrows(AccessGate.LockedException.class,old::run);
        assertThrows(AccessGate.LockedException.class,records::authorization);
        assertThrows(AccessGate.LockedException.class,authority.service::createAdmissionRequest);
        records.reauthorizeSyntheticSession(); records.authorization().run();
        assertThrows(AccessGate.LockedException.class,old::run);
    }
    @Test public void sharedAuthorityCanEnrollAfterExpiryWithoutRotatingIdentityOrRevivingReviews() throws Exception {
        AtomicLong clock=new AtomicLong(1);
        MemoryRecords records=new MemoryRecords(new AccessGate(clock::get,LIFETIME));
        AdmissionFixture.Authority authority=new AdmissionFixture.Authority(records);
        Engine first=member(); authority.enroll(first);
        Engine pending=member(); pending.admission().installRealmConfig(authority.realm.encode(),true);
        var request=pending.admission().createAdmissionRequest();
        var oldReview=authority.service.reviewAdmissionRequest(request.wire());
        Runnable old=records.authorization();
        byte[] identity=records.get("meta","identity"), authoritySeed=records.get("admission-secret","authority");
        try {
            clock.addAndGet(LIFETIME);
            assertThrows(AccessGate.LockedException.class,old::run);
            assertThrows(AccessGate.LockedException.class,records::authorization);
            Engine second=member(); authority.enroll(second);
            assertEquals(authority.realm,second.admission().getRealmInfo());
            first.admission().requireAdmission(); second.admission().requireAdmission();
            assertTrue("Synthetic identity changed",Arrays.equals(identity,records.get("meta","identity")));
            assertTrue("Synthetic authority changed",Arrays.equals(authoritySeed,records.get("admission-secret","authority")));
            assertThrows(AccessGate.LockedException.class,old::run);
            assertThrows(AccessGate.LockedException.class,()->authority.service.approveAdmission(oldReview,true,600));
            // A later ordinary lease still expires; refresh is explicit per fixture enrollment.
            Runnable refreshed=records.authorization(); clock.addAndGet(LIFETIME);
            assertThrows(AccessGate.LockedException.class,refreshed::run);
        } finally { Arrays.fill(identity,(byte)0); Arrays.fill(authoritySeed,(byte)0); }
    }
    @Test public void authorityEnrollmentDoesNotReauthorizeAnExpiredMemberSession() throws Exception {
        AtomicLong clock=new AtomicLong(1);
        MemoryRecords memberRecords=new MemoryRecords(new AccessGate(clock::get,LIFETIME));
        Engine member=new Engine(memberRecords); member.initialize("Synthetic expired member");
        AdmissionFixture.Authority authority=new AdmissionFixture.Authority(new MemoryRecords());
        Runnable old=memberRecords.authorization(); clock.addAndGet(LIFETIME);
        assertThrows(AccessGate.LockedException.class,()->authority.enroll(member));
        assertThrows(AccessGate.LockedException.class,old::run);
        assertThrows(AccessGate.LockedException.class,memberRecords::authorization);
    }
}
