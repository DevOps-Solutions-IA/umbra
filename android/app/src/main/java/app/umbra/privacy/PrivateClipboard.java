package app.umbra.privacy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.PersistableBundle;
import app.umbra.crypto.Engine;

/** Deliberate export of an ordinary stored text message only; never accepts arbitrary object bytes. */
public final class PrivateClipboard {
    private static final String OWNER="app.umbra.clipboard.owner";
    private final Activity owner;
    private String token;
    public PrivateClipboard(Activity owner) { this.owner=java.util.Objects.requireNonNull(owner); }
    private void foreground() {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper() || !owner.hasWindowFocus())
            throw new PrivacyException(PrivacyException.Code.CONSENT_REQUIRED);
    }
    /** Review before showing confirmation, never construct a new review in a delayed callback. */
    public OrdinaryTextExport.Review reviewMessage(Engine engine,String peer,String messageId) throws Exception {
        foreground();return OrdinaryTextExport.review(engine,peer,messageId);
    }
    public void copyMessage(OrdinaryTextExport.Review review,boolean confirmed) throws Exception {
        foreground();
        OrdinaryTextExport.export(review,confirmed,text->{
            foreground();String next=java.util.UUID.randomUUID().toString();
            ClipData clip=ClipData.newPlainText("UMBRA",text);PersistableBundle extras=new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE",true);extras.putString(OWNER,next);
            clip.getDescription().setExtras(extras);
            owner.getSystemService(ClipboardManager.class).setPrimaryClip(clip);token=next;
        });
    }
    /** Explicit foreground cleanup only, never a background global clear or claim of recall. */
    public void clearOwned() {
        foreground();if(token==null)return;
        ClipboardManager clipboard=owner.getSystemService(ClipboardManager.class);
        var description=clipboard.getPrimaryClipDescription();
        if(description!=null && description.getExtras()!=null && token.equals(description.getExtras().getString(OWNER)))
            clipboard.clearPrimaryClip();
        token=null;
    }
}
