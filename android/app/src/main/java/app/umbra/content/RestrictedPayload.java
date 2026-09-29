package app.umbra.content;

import app.umbra.core.Bytes;
import app.umbra.protocol.Wire;
import org.json.JSONObject;

/** Authenticated inside Signal; canonical descriptor also forms the per-object AEAD address. */
public final class RestrictedPayload {
    public static final int MAX_BYTES=262144;
    public static final long MAX_TTL=86400,MAX_SESSION=60;
    public enum Mode { ONCE, UMBRA_ONLY }
    public enum Format { PNG, AAC_ADTS, PDF_PAGES, AVC_MP4 }
    private RestrictedPayload() {}
    public static JSONObject descriptor(JSONObject p,long now) throws Exception {
        Wire.fields(p,"v","id","from","to","format","mode","created","expires","sessionSeconds","key","nonce","ciphertext");
        if(Wire.integer(p,"v")!=1)throw invalid();
        Wire.uuid(Wire.string(p,"id",36));Wire.identity(Wire.string(p,"from",64));Wire.identity(Wire.string(p,"to",64));
        if(p.getString("from").equals(p.getString("to")))throw invalid();
        try { Format.valueOf(Wire.string(p,"format",16));Mode.valueOf(Wire.string(p,"mode",16)); }
        catch(IllegalArgumentException bad) { throw invalid(); }
        long created=Wire.integer(p,"created"),expires=Wire.integer(p,"expires"),session=Wire.integer(p,"sessionSeconds");
        if(created<=0 || created>now+300 || expires<=now || expires<=created || expires-created>MAX_TTL || session<1 || session>MAX_SESSION)throw invalid();
        byte[] key=Bytes.unb64(Wire.string(p,"key",44));
        try { if(key.length!=32)throw invalid(); } finally {java.util.Arrays.fill(key,(byte)0);}
        if(Bytes.unb64(Wire.string(p,"nonce",16)).length!=12)throw invalid();
        byte[] ciphertext=Bytes.unb64(Wire.string(p,"ciphertext",350000));
        if(ciphertext.length<17 || ciphertext.length>MAX_BYTES+16)throw invalid();
        return new JSONObject().put("v",1).put("id",p.getString("id")).put("from",p.getString("from")).put("to",p.getString("to"))
                .put("format",p.getString("format")).put("mode",p.getString("mode")).put("created",created).put("expires",expires).put("sessionSeconds",session);
    }
    public static String address(JSONObject descriptor) throws Exception {
        StringBuilder canonical=new StringBuilder("UMBRA-RESTRICTED-1\n");
        for(String field:new String[]{"v","id","from","to","format","mode","created","expires","sessionSeconds"})
            canonical.append(descriptor.get(field)).append('\n');
        return Bytes.sha256(Bytes.utf8(canonical.toString()));
    }
    static ContentException invalid() { return new ContentException(ContentException.Code.INVALID); }
}
