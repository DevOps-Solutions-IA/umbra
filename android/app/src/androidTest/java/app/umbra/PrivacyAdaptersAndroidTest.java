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
    /** Disposable AVD only: never inspect a phone's clipboard or restore an unknown clip. */
    @Test public void nativeClipboardRequiresConsentAndClearsOnlyItsOwnSyntheticClip() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        try(var descriptor=instrumentation.getUiAutomation().executeShellCommand("getprop ro.kernel.qemu");
            var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            assertEquals("Clipboard fixture requires a disposable emulator", "1",
                new String(input.readAllBytes(),StandardCharsets.US_ASCII).trim());
        }
        var context=instrumentation.getTargetContext();
        try(var ar=new app.umbra.lab.SqliteDeviceRecords();var br=new app.umbra.lab.SqliteDeviceRecords()) {
            var a=new app.umbra.crypto.Engine(ar);var b=new app.umbra.crypto.Engine(br);
            LocationAndroidTest.pair(a,b,ar,br);
            String id=a.sendText(b.id(),"Synthetic clipboard fixture",600);
            try(var host=androidx.test.core.app.ActivityScenario.<android.app.Activity>launch(
                    context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()))) {
                var focused=new java.util.concurrent.atomic.AtomicBoolean();
                long deadline=android.os.SystemClock.elapsedRealtime()+5000;
                do {
                    host.onActivity(activity->focused.set(activity.hasWindowFocus()));
                    if(!focused.get())Thread.sleep(20);
                }while(!focused.get() && android.os.SystemClock.elapsedRealtime()<deadline);
                assertTrue("Foreground clipboard host never acquired focus",focused.get());
                host.onActivity(activity->{
                    var manager=activity.getSystemService(android.content.ClipboardManager.class);
                    // Reject a reused/nonempty environment without reading its payload.
                    assertFalse("Clipboard fixture must start empty",manager.hasPrimaryClip());
                    var clipboard=new PrivateClipboard(activity);
                    String foreignLabel="synthetic-other-owner-"+java.util.UUID.randomUUID();
                    try {
                        var review=clipboard.reviewMessage(a,b.id(),id);
                        assertThrows(PrivacyException.class,()->clipboard.copyMessage(review,false));
                        assertFalse(manager.hasPrimaryClip());
                        clipboard.copyMessage(review,true);
                        assertEquals("Synthetic clipboard fixture",manager.getPrimaryClip().getItemAt(0).getText());
                        assertTrue(manager.getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
                        assertThrows(PrivacyException.class,()->clipboard.copyMessage(review,true));
                        clipboard.clearOwned();assertFalse(manager.hasPrimaryClip());
                        clipboard.copyMessage(clipboard.reviewMessage(a,b.id(),id),true);
                        manager.setPrimaryClip(android.content.ClipData.newPlainText(foreignLabel,"Synthetic other owner"));
                        clipboard.clearOwned();
                        assertEquals("Synthetic other owner",manager.getPrimaryClip().getItemAt(0).getText());
                        var stale=clipboard.reviewMessage(a,b.id(),id);ar.gate.lock();ar.gate.unlock();
                        assertThrows(SecurityException.class,()->clipboard.copyMessage(stale,true));
                        assertEquals("Synthetic other owner",manager.getPrimaryClip().getItemAt(0).getText());
                    }catch(Exception failure){throw new AssertionError("Native clipboard fixture failed",failure);}
                    finally {
                        clipboard.clearOwned();
                        var description=manager.getPrimaryClipDescription();
                        if(description!=null && foreignLabel.contentEquals(description.getLabel()))manager.clearPrimaryClip();
                    }
                });
            }
        }
    }
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
