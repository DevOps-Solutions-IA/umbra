package app.umbra;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.content.*;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic local PDF -> isolated framework parser -> Signal -> consumed page session. */
public class RestrictedDocumentAndroidTest {
    private static byte[] pdf(int count)throws Exception {
        var document=new PdfDocument();
        try(var bytes=new ByteArrayOutputStream()) {
            for(int n=0;n<count;n++) {
                var page=document.startPage(new PdfDocument.PageInfo.Builder(64,48,n).create());
                page.getCanvas().drawColor(n%2==0?Color.RED:Color.BLUE);document.finishPage(page);
            }
            document.writeTo(bytes);return bytes.toByteArray();
        } finally {document.close();}
    }
    @Test public void isolatedPreparationSignalAndNavigationShareOnePersistentOpening()throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            byte[] original=pdf(2),unchanged=original.clone();String id;
            try(var prepared=RestrictedDocuments.prepare(context,a,review,original,true)) {
                assertArrayEquals(unchanged,original);id=a.restricted().send(review,prepared,true);
            }finally{Arrays.fill(original,(byte)0);Arrays.fill(unchanged,(byte)0);}
            for(var row:a.outbox()){b.receive(row.getJSONObject("envelope"));b.receive(row.getJSONObject("envelope"));}
            assertTrue(b.messages(a.id()).isEmpty());
            var session=b.restricted().open(b.restricted().reviewOpen(id),true);
            Bitmap target=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888);
            try(var reader=new RestrictedDocuments.Decoder(session)) {
                assertEquals(2,reader.pageCount());Canvas canvas=new Canvas(target);Rect bounds=new Rect(0,0,64,48);
                reader.render(1,canvas,bounds);assertEquals(Color.BLUE,target.getPixel(20,20));
                reader.render(0,canvas,bounds);assertEquals(Color.RED,target.getPixel(20,20));
                assertThrows(ContentException.class,()->reader.render(2,canvas,bounds));
                br.gate.lock();session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
                assertThrows(SecurityException.class,()->reader.render(0,canvas,bounds));
                br.gate.unlock();assertThrows(SecurityException.class,()->reader.render(0,canvas,bounds));
            }finally{target.recycle();}
            br.reopen();Engine reopened=new Engine(br);
            assertEquals(ContentException.Code.CONSUMED,assertThrows(ContentException.class,
                ()->reopened.restricted().open(reopened.restricted().reviewOpen(id),true)).code());
        }
    }
    @Test public void malformedAndTooManyPagesFailWithoutPoisoningNextPreparation()throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            assertThrows(ContentException.class,()->RestrictedDocuments.prepare(context,a,review,new byte[]{1,2,3},true));
            byte[] tooMany=pdf(5);
            try{assertThrows(ContentException.class,()->RestrictedDocuments.prepare(context,a,review,tooMany,true));}
            finally{Arrays.fill(tooMany,(byte)0);}
            byte[] valid=pdf(1);
            try(var prepared=RestrictedDocuments.prepare(context,a,review,valid,true)){assertNotNull(prepared);}
            finally{Arrays.fill(valid,(byte)0);}
            assertTrue(a.outbox().isEmpty());assertFalse(a.connectivity().isNetworkSessionAllowed());
            String malformed=a.restricted().send(review,SyntheticDocuments.invalidRaster(ar.authorization()),true);
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            var session=b.restricted().open(b.restricted().reviewOpen(malformed),true);
            assertThrows(ContentException.class,()->new RestrictedDocuments.Decoder(session));
            session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertTrue(b.restricted().status(malformed).consumed());
            assertThrows(ContentException.class,()->b.restricted().open(b.restricted().reviewOpen(malformed),true));
        }
    }
    @Test public void admissionConsentAndManifestIsolationAreRequired()throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        @SuppressWarnings("deprecation") // minSdk 31 needs the int-flags overload.
        var service=context.getPackageManager().getServiceInfo(new android.content.ComponentName(context,RestrictedPdfService.class),0);
        assertFalse(service.exported);
        assertTrue((service.flags & android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS)!=0);
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,30);
            assertEquals(ContentException.Code.CONSENT_REQUIRED,assertThrows(ContentException.class,
                ()->RestrictedDocuments.prepare(context,a,review,new byte[]{1},false)).code());
            ar.gate.lock();ar.gate.unlock();
            assertThrows(SecurityException.class,()->RestrictedDocuments.prepare(context,a,review,new byte[]{1},true));
            assertTrue(a.outbox().isEmpty());
        }
    }
}
