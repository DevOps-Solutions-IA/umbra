package app.umbra.privacy;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.PersistableBundle;
import app.umbra.crypto.Engine;
import org.json.JSONObject;

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
    public void copyMessage(Engine engine,String peer,String messageId,boolean confirmed) throws Exception {
        foreground();
        if(!confirmed)throw new PrivacyException(PrivacyException.Code.CONSENT_REQUIRED);
        var grant=engine.deliveryAuthorization(peer);
        app.umbra.protocol.Wire.uuid(messageId);
        JSONObject selected=engine.get("message",messageId);
        if(selected==null)selected=engine.get("message",peer+":"+messageId);
        if(selected==null || selected.optLong("expires")<=app.umbra.core.Bytes.now() || !peer.equals(selected.optString("peer")) || !"text".equals(selected.optString("kind")))
            throw new PrivacyException(PrivacyException.Code.RESTRICTED_EXPORT);
        String text=selected.getString("text");
        if(app.umbra.core.Bytes.utf8(text).length>16000)throw new PrivacyException(PrivacyException.Code.LIMIT_EXCEEDED);
        String next=java.util.UUID.randomUUID().toString();
        ClipData clip=ClipData.newPlainText("UMBRA",text);PersistableBundle extras=new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE",true);extras.putString(OWNER,next);
        clip.getDescription().setExtras(extras);
        grant.run();foreground();owner.getSystemService(ClipboardManager.class).setPrimaryClip(clip);token=next;
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
