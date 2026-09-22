package app.umbra.calls;

import app.umbra.protocol.Wire;
import java.util.Set;
import org.json.JSONObject;

/** Version 2 call extension. Directions name the original caller, never the SDP author. */
public final class VideoPayload {
    private VideoPayload() {}
    public static final Set<String> CONTROLS=Set.of("VIDEO_REQUEST","VIDEO_ACCEPT","VIDEO_REJECT","VIDEO_STOP");
    public static final long REVIEW_MILLIS=30_000, REQUEST_SECONDS=30;
    public static int direction(JSONObject data,String field) throws Exception {
        long value=Wire.integer(data,field);
        if(value!=0 && value!=1) throw new SecurityException("Invalid video direction");
        return (int)value;
    }
    public static void binding(JSONObject data) throws Exception {
        Wire.fields(data,"change","callerSend","calleeSend");
        Wire.uuid(Wire.string(data,"change",36));
        if(direction(data,"callerSend")+direction(data,"calleeSend")==0)
            throw new SecurityException("Video requires an approved direction");
    }
    public static void validate(String type,JSONObject data,int generation,long now,long ends) throws Exception {
        if(generation<2 || generation>4) throw new SecurityException("Invalid video generation");
        if(type.equals("VIDEO_REQUEST") || type.equals("VIDEO_ACCEPT")) {
            Wire.fields(data,"change","callerSend","calleeSend","expires");
            JSONObject directions=new JSONObject(data.toString()); directions.remove("expires"); binding(directions);
            long expires=Wire.integer(data,"expires");
            if(expires<=now || expires>now+REQUEST_SECONDS || expires>ends)
                throw new SecurityException("Video review expired");
        } else {
            Wire.fields(data,"change"); Wire.uuid(Wire.string(data,"change",36));
        }
    }
    public static JSONObject bindingOf(JSONObject video) throws Exception {
        return new JSONObject().put("change",video.getString("change"))
            .put("callerSend",video.getInt("callerSend")).put("calleeSend",video.getInt("calleeSend"));
    }
}
