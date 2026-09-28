package app.umbra;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.EditText;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.privacy.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrivacyAdaptersAndroidTest {
    @Test public void imageCopyRemovesTextMetadataWithoutChangingOriginal() throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff449944);
        ByteArrayOutputStream output=new ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));bitmap.recycle();
        byte[] png=output.toByteArray();
        byte[] text="synthetic-key\0synthetic-private-marker".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream withMetadata=new ByteArrayOutputStream();
        withMetadata.write(png,0,png.length-12);
        java.io.DataOutputStream data=new java.io.DataOutputStream(withMetadata);data.writeInt(text.length);
        byte[] type="tEXt".getBytes(StandardCharsets.US_ASCII);data.write(type);data.write(text);
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(type);crc.update(text);data.writeInt((int)crc.getValue());
        data.write(png,png.length-12,12);byte[] source=withMetadata.toByteArray(),before=source.clone();
        byte[] prepared=ImagePreparation.sanitize(source,()->{});
        assertArrayEquals(before,source);
        assertFalse(new String(prepared,StandardCharsets.ISO_8859_1).contains("synthetic-private-marker"));
        Bitmap decoded=BitmapFactory.decodeByteArray(prepared,0,prepared.length);
        assertNotNull(decoded);assertEquals(32,decoded.getWidth());assertEquals(24,decoded.getHeight());
        assertEquals(0xff449944,decoded.getPixel(8,8));decoded.recycle();
    }
    @Test public void invalidAndOversizedImagesOrStaleLeaseCannotProduceCopy() throws Exception {
        assertThrows(PrivacyException.class,()->ImagePreparation.sanitize(new byte[ImagePreparation.MAX_INPUT+1],()->{}));
        assertThrows(java.io.IOException.class,()->ImagePreparation.sanitize(new byte[]{1,2,3},()->{}));
        assertThrows(SecurityException.class,()->ImagePreparation.sanitize(new byte[]{1},()->{throw new SecurityException("Locked");}));
    }
    @Test public void secureWindowAndSensitiveInputConfiguredBeforePresentation() {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(()->{
            Dialog host=new Dialog(instrumentation.getTargetContext());
            PrivateAndroidSurface.protect(host.getWindow());
            assertTrue((host.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            EditText input=new EditText(instrumentation.getTargetContext());PrivateAndroidSurface.sensitiveInput(input);
            assertFalse(input.isSaveEnabled());
            assertEquals(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,input.getImportantForAutofill());
            var notification=PrivateAndroidSurface.notification(instrumentation.getTargetContext(),"synthetic",android.R.drawable.ic_lock_lock);
            assertEquals(android.app.Notification.VISIBILITY_SECRET,notification.visibility);
            assertNull(notification.contentIntent);assertEquals("Actividad privada",notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT));
        });
    }
}
