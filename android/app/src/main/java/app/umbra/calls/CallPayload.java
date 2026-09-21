package app.umbra.calls;

import app.umbra.core.Bytes;
import app.umbra.protocol.Wire;
import org.json.*;
import java.util.*;

/** Strict application envelope, not an SDP/ICE parser or a multimedia implementation. */
public final class CallPayload {
    private CallPayload() {}
    public enum NetworkPolicy { RELAY_ONLY, DIRECT_ALLOWED }
    public static final Set<String> TYPES=Set.of("INVITE","ACCEPT","SELECT","REJECT","BUSY","CANCEL","DESCRIPTION","ICE","END");
    public static final Set<String> TERMINAL=Set.of("CANCELLED","REJECTED","BUSY","EXPIRED","ENDED","FAILED","NOT_SELECTED");
    public static final long RING_SECONDS=60, SESSION_SECONDS=180, DELIVERY_SECONDS=30;
    public static List<String> targets(JSONObject c) throws Exception {
        if(!(c.get("targets") instanceof JSONArray a) || a.length()<1 || a.length()>8) throw new SecurityException("Invalid call recipients");
        List<String> result=new ArrayList<>(); String previous="";
        for(int i=0;i<a.length();i++) {
            if(!(a.get(i) instanceof String id) || id.compareTo(previous)<=0) throw new SecurityException("Invalid call recipient ordering");
            result.add(Wire.identity(id)); previous=id;
        }
        return List.copyOf(result);
    }
    public static NetworkPolicy policy(String s) {
        try { NetworkPolicy value=NetworkPolicy.valueOf(s); if(value!=NetworkPolicy.RELAY_ONLY) throw new SecurityException("Direct media is disabled in this delivery"); return value; } catch(IllegalArgumentException e) { throw new SecurityException("Invalid network policy"); }
    }
    public static NetworkPolicy effective(NetworkPolicy a,NetworkPolicy b) {
        policy(Objects.requireNonNull(a).name()); policy(Objects.requireNonNull(b).name());
        return NetworkPolicy.RELAY_ONLY;
    }
    public static void validate(JSONObject p,long now) throws Exception {
        Wire.fields(p,"v","purpose","context","type","event","device","selected","generation","policy","data");
        if(Wire.integer(p,"v")!=1 || !Wire.string(p,"purpose",32).equals("UMBRA-CALL-SIGNALING") || p.toString().length()>40000) throw new SecurityException("Invalid call protocol");
        String type=Wire.string(p,"type",16); if(!TYPES.contains(type)) throw new SecurityException("Unknown call control");
        Wire.uuid(Wire.string(p,"event",36)); Wire.identity(Wire.string(p,"device",64));
        String selected=Wire.string(p,"selected",64); if(!selected.isEmpty()) Wire.identity(selected);
        policy(Wire.string(p,"policy",16));
        long gen=Wire.integer(p,"generation"); if(gen<0 || gen>4) throw new SecurityException("Invalid negotiation generation");
        if(!(p.get("context") instanceof JSONObject c) || !(p.get("data") instanceof JSONObject data)) throw new SecurityException("Invalid call objects");
        Wire.fields(c,"callId","caller","callerDevice","callee","targets","callerVersion","calleeVersion","created","inviteUntil","ends");
        Wire.uuid(Wire.string(c,"callId",36));
        for(String f:List.of("caller","callerDevice","callee")) Wire.identity(Wire.string(c,f,64));
        List<String> targets=targets(c);
        if(c.getString("caller").equals(c.getString("callee")) || targets.contains(c.getString("callerDevice")) || (!selected.isEmpty()&&!targets.contains(selected))) throw new SecurityException("Invalid call participants");
        if(Wire.integer(c,"callerVersion")<1 || Wire.integer(c,"calleeVersion")<1) throw new SecurityException("Invalid membership version");
        long start=Wire.integer(c,"created"), ring=Wire.integer(c,"inviteUntil"), end=Wire.integer(c,"ends");
        if(start<1 || start>now+30 || ring-start!=RING_SECONDS || end-start!=SESSION_SECONDS || now>=end) throw new SecurityException("Call expired or invalid clock");
        if(type.equals("DESCRIPTION")) {
            Wire.fields(data,"role","sdp","digest","fingerprint");
            if(!Set.of("offer","answer").contains(Wire.string(data,"role",6))) throw new SecurityException("Invalid description role");
            String sdp=Wire.string(data,"sdp",24000); if(sdp.isEmpty() || Bytes.utf8(sdp).length>24000 || sdp.indexOf(0)>=0) throw new SecurityException("Invalid description size");
            if(!Bytes.sha256(Bytes.utf8(sdp)).equals(Wire.identity(Wire.string(data,"digest",64)))) throw new SecurityException("Description digest mismatch");
            Wire.identity(Wire.string(data,"fingerprint",64));
            if(gen<1 || selected.isEmpty()) throw new SecurityException("Description before selection");
        } else if(type.equals("ICE")) {
            Wire.fields(data,"candidate","description","mid");
            if(Wire.string(data,"candidate",2048).isEmpty() || Wire.string(data,"mid",32).isEmpty()) throw new SecurityException("Empty ICE data");
            Wire.identity(Wire.string(data,"description",64));
            if(gen<1 || selected.isEmpty()) throw new SecurityException("ICE before selection");
        } else Wire.fields(data);
    }
    /** Stable hash independent of JSON object iteration order; duplicate fields rejected by Wire.parse. */
    public static String canonical(Object value) throws Exception {
        if(value instanceof JSONObject o) {
            List<String> keys=new ArrayList<>(); o.keys().forEachRemaining(keys::add); Collections.sort(keys);
            List<String> values=new ArrayList<>(); for(String k:keys) values.add(JSONObject.quote(k)+":"+canonical(o.get(k)));
            return "{"+String.join(",",values)+"}";
        }
        if(value instanceof JSONArray a) { List<String> values=new ArrayList<>(); for(int i=0;i<a.length();i++) values.add(canonical(a.get(i))); return "["+String.join(",",values)+"]"; }
        if(value instanceof String s) return JSONObject.quote(s);
        if(value instanceof Integer || value instanceof Long) return value.toString();
        throw new SecurityException("Unsupported call JSON value");
    }
}
