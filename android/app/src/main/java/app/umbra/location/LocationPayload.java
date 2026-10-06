package app.umbra.location;

import app.umbra.protocol.Wire;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Versioned, integer-only location protocol inside the authenticated Signal plaintext. */
public final class LocationPayload {
    private LocationPayload() {}
    public enum Mode { MANUAL, PRECISE, APPROXIMATE, ZONE }
    public static final long MAX_DURATION = 28800, MAX_AGE = 120, INTERVAL_MS = 15000;
    public static final Set<String> TYPES = Set.of("LOCATION_POINT", "LOCATION_LIVE_START", "LOCATION_LIVE_UPDATE", "LOCATION_LIVE_STOP");
    public static JSONObject point(double latitude, double longitude, double accuracyMeters, long measured,
                                   Mode mode, String source) throws Exception {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 ||
            !Double.isFinite(accuracyMeters) || accuracyMeters < -1 || accuracyMeters > 100000)
            throw new SecurityException("Invalid location estimate");
        long lat = Math.round(latitude * 10000000), lon = Math.round(longitude * 10000000);
        long cell = cell(mode);
        if (cell != 0) {
            lat = Math.min(900000000L-cell/2, Math.floorDiv(lat+900000000L,cell)*cell-900000000L+cell/2);
            if (lon == 1800000000L) lon = -1800000000L;
            lon = Math.floorDiv(lon+1800000000L,cell)*cell-1800000000L+cell/2;
        }
        return new JSONObject().put("latE7",lat).put("lonE7",lon).put("sensorAccuracyMm",accuracyMeters < 0 ? -1 : Math.round(accuracyMeters*1000))
            .put("measured",measured).put("cellE7",cell).put("source",source);
    }
    public static long cell(Mode mode) { return mode==Mode.APPROXIMATE ? 100000 : mode==Mode.ZONE ? 1000000 : 0; }
    public static List<String> targets(JSONObject p) throws Exception {
        if (!(p.get("targets") instanceof JSONArray a) || a.length()<1 || a.length()>8) throw new SecurityException("Invalid location recipients");
        List<String> result = new ArrayList<>(); String previous = "";
        for(int i=0;i<a.length();i++) {
            if (!(a.get(i) instanceof String s) || s.compareTo(previous)<=0) throw new SecurityException("Noncanonical location recipients");
            Wire.identity(s); result.add(s); previous=s;
        }
        return List.copyOf(result);
    }
    public static void validate(JSONObject p, long now) throws Exception {
        if(p.toString().length()>4096) throw new SecurityException("Location payload too large");
        String type=Wire.string(p,"type",24); if(!TYPES.contains(type)) throw new SecurityException("Unknown location type");
        boolean position=type.equals("LOCATION_POINT")||type.equals("LOCATION_LIVE_UPDATE");
        List<String> fields=new ArrayList<>(List.of("v","type","session","owner","device","recipient","targets","seq","started","ends","mode"));
        if(position) fields.add("point"); Wire.fields(p,fields.toArray(new String[0]));
        if(Wire.integer(p,"v")!=1) throw new SecurityException("Unsupported location version");
        Wire.uuid(Wire.string(p,"session",36));
        for(String f:List.of("owner","device","recipient")) Wire.identity(Wire.string(p,f,64));
        targets(p);
        long seq=Wire.integer(p,"seq"), start=Wire.integer(p,"started"), end=Wire.integer(p,"ends");
        if(seq<0||seq>1921 || start<1 || start>now+30 || end<=start || end-start>MAX_DURATION || now>end+120 ||
            (type.equals("LOCATION_LIVE_START") && seq!=0) || (type.equals("LOCATION_POINT") && (seq!=0 || end-start>MAX_AGE)) ||
            (type.equals("LOCATION_LIVE_UPDATE") && seq==0) || (type.equals("LOCATION_LIVE_STOP") && seq==0))
            throw new SecurityException("Invalid location lifetime or sequence");
        Mode mode;
        try { mode=Mode.valueOf(Wire.string(p,"mode",16)); } catch(IllegalArgumentException e) { throw new SecurityException("Unknown precision"); }
        if(mode==Mode.MANUAL && !type.equals("LOCATION_POINT")) throw new SecurityException("Manual location cannot start capture");
        if(position) {
            if(!(p.get("point") instanceof JSONObject q)) throw new SecurityException("Invalid point");
            Wire.fields(q,"latE7","lonE7","sensorAccuracyMm","measured","cellE7","source");
            long lat=Wire.integer(q,"latE7"),lon=Wire.integer(q,"lonE7"),accuracy=Wire.integer(q,"sensorAccuracyMm"),time=Wire.integer(q,"measured"),grid=Wire.integer(q,"cellE7");
            String source=Wire.string(q,"source",16);
            if(lat< -900000000L || lat>900000000L || lon< -1800000000L || lon>1800000000L || accuracy< -1 || accuracy>100000000L ||
                time<start-MAX_AGE || time>now+30 || time>end || now-time>MAX_AGE || grid!=cell(mode) ||
                !Set.of("MANUAL","ANDROID_FINE","ANDROID_COARSE").contains(source) ||
                (mode==Mode.MANUAL)!=source.equals("MANUAL") || (source.equals("MANUAL") ? accuracy!=-1 : accuracy<0) ||
                (source.equals("ANDROID_COARSE") && mode==Mode.PRECISE)) throw new SecurityException("Invalid location point");
            if(grid!=0 && ((lat+900000000L)%grid!=grid/2 || (lon+1800000000L)%grid!=grid/2 || lon==1800000000L || lat==900000000L))
                throw new SecurityException("Unreduced location point");
        }
    }
    public static String context(JSONObject p) throws Exception {
        return p.getString("owner")+":"+p.getString("device")+":"+p.getString("recipient")+":"+targets(p)+":"+
            p.getLong("started")+":"+p.getLong("ends")+":"+p.getString("mode");
    }
}
