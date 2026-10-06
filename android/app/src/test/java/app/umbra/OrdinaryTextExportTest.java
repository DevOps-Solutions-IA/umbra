package app.umbra;

import app.umbra.privacy.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class OrdinaryTextExportTest {
    @Test public void ordinaryMessageNeedsConfirmationAndCannotReplayExportConsent() throws Exception {
        var pair=new RestrictedContentTest.Pair();String id=pair.a.e.sendText(pair.b.e.id(),"synthetic text",600);
        var review=OrdinaryTextExport.review(pair.a.e,pair.b.e.id(),id);var writes=new AtomicInteger();
        OrdinaryTextExport.Sink sink=text->{assertEquals("synthetic text",text);writes.incrementAndGet();};
        assertThrows(PrivacyException.class,()->OrdinaryTextExport.export(review,false,sink));assertEquals(0,writes.get());
        OrdinaryTextExport.export(review,true,sink);assertEquals(1,writes.get());
        assertThrows(PrivacyException.class,()->OrdinaryTextExport.export(review,true,sink));assertEquals(1,writes.get());
    }
    @Test public void previousVaultGenerationCannotExportAfterUnlockAndRestrictedObjectIsRejected() throws Exception {
        var pair=new RestrictedContentTest.Pair();String id=pair.a.e.sendText(pair.b.e.id(),"synthetic text",600);
        var review=OrdinaryTextExport.review(pair.a.e,pair.b.e.id(),id);pair.a.db.gate.lock();pair.a.db.gate.unlock();
        assertThrows(SecurityException.class,()->OrdinaryTextExport.export(review,true,text->fail("Stale export")));
        String restricted=pair.send(app.umbra.content.RestrictedPayload.Mode.ONCE);
        assertThrows(PrivacyException.class,()->OrdinaryTextExport.review(pair.b.e,pair.a.e.id(),restricted));
    }
    @Test public void failedExternalSinkDoesNotAuthorizeASecondWrite() throws Exception {
        var pair=new RestrictedContentTest.Pair();String id=pair.a.e.sendText(pair.b.e.id(),"synthetic text",600);
        var review=OrdinaryTextExport.review(pair.a.e,pair.b.e.id(),id);
        assertThrows(java.io.IOException.class,()->OrdinaryTextExport.export(review,true,text->{throw new java.io.IOException("Synthetic sink failure");}));
        assertThrows(PrivacyException.class,()->OrdinaryTextExport.export(review,true,text->fail("Replayed sink")));
    }
}
