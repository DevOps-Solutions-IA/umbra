package app.umbra.location;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import app.umbra.devices.DeviceService;
import org.json.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Local consent and persistent terminal states. Incoming messages can never create a capture grant. */
public final class LocationService {
    private final Records db;
    private final Engine engine;
    private final LongSupplier elapsed;
    private final String runtime=UUID.randomUUID().toString();
    private final Map<String,Grant> grants = new ConcurrentHashMap<>();
    public LocationService(Records db, Engine engine, LongSupplier elapsed) { this.db=db; this.engine=engine; this.elapsed=elapsed; }
    public final class Consent {
        private final LocationService owner=LocationService.this;
        private final String root; private final List<String> targets; private final LocationPayload.Mode mode;
        private final long duration, reviewed; private final boolean live; private final Runnable lease; private boolean used;
        private Consent(String root,List<String> targets,LocationPayload.Mode mode,long duration,boolean live) {
            this.root=root; this.targets=List.copyOf(targets); this.mode=mode; this.duration=duration; this.live=live;
            lease=db.authorization(); reviewed=elapsed.getAsLong();
        }
        public String recipient() { return root; }
        public List<String> devices() { return targets; }
        public LocationPayload.Mode precision() { return mode; }
        public long durationSeconds() { return duration; }
    }
    private static final class Grant {
        final Runnable lease; final long deadline, began; volatile boolean stopped; volatile Runnable capturePolicy;
        Grant(Runnable lease,long now,long duration) { this.lease=lease; began=now; deadline=now+duration*1000; }
    }
    /** Unforgeable one-transaction capability, bound to exact content and this Records instance. */
    public final class Permit {
        private final String bytes; private final Grant grant;
        private Permit(JSONObject payload,Grant grant) { bytes=payload.toString(); this.grant=grant; }
        public void check(Records records,JSONObject payload) throws Exception {
            if(records!=db || !bytes.equals(payload.toString())) throw new SecurityException("Location capability substitution");
            grant.lease.run();
            if(!payload.getString("type").equals("LOCATION_LIVE_STOP")) checkGrant(payload.getString("session"));
        }
    }
    private JSONObject get(String bucket,String key) throws Exception { return engine.get(bucket,key); }
    private void put(String bucket,String key,JSONObject row) { db.put(bucket,key,Bytes.utf8(row.toString())); }
    public Consent review(String root,LocationPayload.Mode mode,long duration,boolean live) throws Exception {
        return db.transaction(() -> {
            maintain();
            if(mode==null || duration<60 || duration>(live?LocationPayload.MAX_DURATION:LocationPayload.MAX_AGE) || (live && mode==LocationPayload.Mode.MANUAL))
                throw new SecurityException("Invalid location consent");
            if(get("meta","device-affiliation")==null) throw new SecurityException("Device migration required");
            List<String> targets=new ArrayList<>(new DeviceService(db).recipients(root)); Collections.sort(targets);
            for(String peer:targets) engine.authorizeTransport(peer);
            return new Consent(root,targets,mode,duration,live);
        });
    }
    public String start(Consent consent,boolean ownerConfirmed) throws Exception {
        return db.transaction(() -> {
            if(consent==null || !ownerConfirmed || consent.used || consent.owner!=this) throw new SecurityException("Explicit location consent required");
            // The captured lease and recipient list must still belong to this service.
            consent.lease.run(); long mono=elapsed.getAsLong();
            if(mono<consent.reviewed || mono-consent.reviewed>60000) throw new SecurityException("Location review expired");
            for(String peer:consent.targets) engine.authorizeTransport(peer);
            if(grants.size()>=4 || db.keys("location-out").size()>=256) throw new IllegalStateException("Location session capacity reached");
            JSONObject own=get("meta","device-affiliation");
            String id=UUID.randomUUID().toString(); long now=Bytes.now(); consent.used=true;
            JSONObject payload=new JSONObject().put("v",1).put("type",consent.live?"LOCATION_LIVE_START":"LOCATION_POINT")
                .put("session",id).put("owner",own.getString("root")).put("device",engine.id()).put("recipient",consent.root)
                .put("targets",new JSONArray(consent.targets)).put("seq",0).put("started",now).put("ends",now+consent.duration).put("mode",consent.mode.name());
            Grant grant=new Grant(consent.lease,mono,consent.duration); grants.put(id,grant);
            try {
                put("location-out",id,new JSONObject().put("payload",payload).put("state",consent.live?"ACTIVE":"PENDING")
                    .put("lastElapsed",mono-LocationPayload.INTERVAL_MS).put("retainUntil",now+consent.duration+Engine.MAX_TTL+300));
                if(consent.live) engine.enqueueLocation(new Permit(payload,grant),payload);
                return id;
            } catch(Exception e) { grants.remove(id); throw e; }
        });
    }
    private Grant checkGrant(String id) throws Exception {
        Grant grant=grants.get(id); if(grant==null || grant.stopped) throw new SecurityException("Location session interrupted");
        grant.lease.run(); capturePolicy(grant); long now=elapsed.getAsLong();
        JSONObject row=get("location-out",id);
        if(row==null || !Set.of("ACTIVE","PENDING").contains(row.getString("state")) || now<grant.began || now>=grant.deadline ||
            Bytes.now()>=row.getJSONObject("payload").getLong("ends") || Bytes.now()<row.getJSONObject("payload").getLong("started")-30)
            throw new SecurityException("Location session expired or clock changed");
        JSONObject p=row.getJSONObject("payload");
        engine.authorizeTransportSelf();
        JSONObject root=engine.contact(p.getString("recipient"));
        if(root==null || engine.trustState(p.getString("recipient"))!=Engine.TrustState.VERIFIED) throw new SecurityException("Location recipient trust suspended");
        return grant;
    }
    private static void capturePolicy(Grant grant) {
        if(grant.capturePolicy!=null) try { grant.capturePolicy.run(); }
        catch(SecurityException denied) { grant.stopped=true; throw denied; }
    }
    /** The Android provider binds its live permission/foreground check to every pending write. */
    public void bindCapturePolicy(String session,Runnable policy) throws Exception {
        if(policy==null) throw new SecurityException("Missing capture policy");
        db.transaction(() -> {
            Grant grant=checkGrant(session);
            if(grant.capturePolicy!=null) throw new SecurityException("Capture policy already bound");
            grant.capturePolicy=policy; capturePolicy(grant); return null;
        });
    }
    /** Called before obtaining each measurement, and again before processing its callback. */
    public void authorizeCapture(String session) throws Exception {
        db.transaction(() -> { checkGrant(session); if(eligible(get("location-out",session).getJSONObject("payload")).isEmpty()) throw new SecurityException("No remaining location recipients"); return null; });
    }
    private List<String> eligible(JSONObject payload) throws Exception {
        List<String> result=new ArrayList<>();
        for(String peer:LocationPayload.targets(payload)) {
            JSONObject index=get("device-index",peer);
            JSONObject roster=get("device-roster",payload.getString("recipient"));
            if(index==null || !index.getString("root").equals(payload.getString("recipient")) || roster==null) throw new SecurityException("Location membership missing");
            // An explicit revocation shrinks this consent. Additions can never enlarge it.
            if(!roster.getJSONObject("active").optBoolean(peer)) continue;
            engine.authorizeTransport(peer); result.add(peer);
        }
        return result;
    }
    public List<String> authorizedTargets(JSONObject payload) throws Exception { return eligible(payload); }
    public void publish(String session,double lat,double lon,double accuracy,long measured,String source) throws Exception {
        db.transaction(() -> {
            Grant grant=checkGrant(session); JSONObject row=get("location-out",session), p=new JSONObject(row.getJSONObject("payload").toString());
            long mono=elapsed.getAsLong();
            if(mono-row.getLong("lastElapsed")<LocationPayload.INTERVAL_MS) throw new SecurityException("Location update rate exceeded");
            if(measured<row.optLong("lastMeasured",0)) throw new SecurityException("Location measurement moved backwards");
            boolean live=row.getString("state").equals("ACTIVE");
            p.put("type",live?"LOCATION_LIVE_UPDATE":"LOCATION_POINT").put("seq",live?p.getLong("seq")+1:0);
            p.put("point",LocationPayload.point(lat,lon,accuracy,measured,LocationPayload.Mode.valueOf(p.getString("mode")),source));
            LocationPayload.validate(p,Bytes.now());
            if(eligible(p).isEmpty()) throw new SecurityException("No remaining location recipients");
            drop(session,false); // Never modify an encrypted retry or rewind Signal state.
            engine.enqueueLocation(new Permit(p,grant),p);
            JSONObject metadata=new JSONObject(p.toString()); metadata.remove("point");
            row.put("payload",metadata).put("lastMeasured",measured).put("lastElapsed",mono).put("state",live?"ACTIVE":"SENT"); put("location-out",session,row);
            return null;
        });
    }
    public String manual(Consent consent,boolean confirmed,double lat,double lon) throws Exception {
        if(consent==null || consent.mode!=LocationPayload.Mode.MANUAL) throw new SecurityException("Manual consent required");
        return db.transaction(() -> { String id=start(consent,confirmed); publish(id,lat,lon,-1,Bytes.now(),"MANUAL"); return id; });
    }
    private void drop(String session,boolean all) throws Exception {
        for(String id:db.keys("outbox")) {
            JSONObject row=get("outbox",id);
            if(session.equals(row.optString("locationSession")) && (all || row.optString("locationType").equals("LOCATION_LIVE_UPDATE") || row.optString("locationType").equals("LOCATION_POINT"))) db.remove("outbox",id);
        }
    }
    /** Immediate cancellation on UI thread; persistence and optional STOP run later on the worker. */
    public void cancelCapture(String session) { Grant grant=grants.get(session); if(grant!=null) grant.stopped=true; }
    public void stop(String session) throws Exception {
        Grant grant=grants.get(session); if(grant!=null) grant.stopped=true; // Fail closed even if disk fails.
        db.transaction(() -> {
            JSONObject row=get("location-out",session); if(row==null) throw new SecurityException("Unknown location session");
            if(!Set.of("ACTIVE","PENDING").contains(row.getString("state"))) return null;
            drop(session,true); JSONObject p=new JSONObject(row.getJSONObject("payload").toString());
            row.put("state","STOPPED"); put("location-out",session,row);
            if(grant!=null && p.getLong("ends")>Bytes.now()) {
                grant.lease.run(); p.put("type","LOCATION_LIVE_STOP").put("seq",p.getLong("seq")+1); p.remove("point");
                if(!p.getString("mode").equals("MANUAL")) engine.enqueueLocation(new Permit(p,grant),p);
            }
            return null;
        });
    }
    /** Safe before locking: no storage needed to invalidate callbacks and future writes. */
    public void cancelLocal() { for(Grant grant:grants.values()) grant.stopped=true; grants.clear(); }
    /** Persistent interruption survives even when another Engine instance applies a trust change. */
    public void suspendIdentity(String identity) throws Exception {
        for(String session:db.keys("location-out")) {
            JSONObject row=get("location-out",session), p=row.getJSONObject("payload");
            if(identity.equals(p.getString("recipient")) || identity.equals(p.getString("device")) || identity.equals(p.getString("owner"))) {
                grants.remove(session); drop(session,true);
                if(Set.of("ACTIVE","PENDING","SENT").contains(row.getString("state"))) {
                    row.put("state","INTERRUPTED"); put("location-out",session,row);
                }
            }
        }
    }
    public void interrupt(String session) throws Exception {
        Grant grant=grants.remove(session);
        db.transaction(() -> { JSONObject row=get("location-out",session); if(row!=null) {
            drop(session,true);
            if(Set.of("ACTIVE","PENDING","SENT").contains(row.getString("state"))) {
                boolean expired=Bytes.now()>=row.getJSONObject("payload").getLong("ends") || grant!=null && elapsed.getAsLong()>=grant.deadline;
                row.put("state",expired?"EXPIRED":"INTERRUPTED"); put("location-out",session,row);
            }
        } return null; });
    }
    /** Reopen never restores a grant; terminal records survive for the entire replay horizon. */
    public void maintain() throws Exception {
        for(String id:db.keys("location-out")) {
            JSONObject row=get("location-out",id); Grant grant=grants.get(id); long now=Bytes.now();
            boolean valid=grant!=null;
            if(valid) { try { grant.lease.run(); } catch(SecurityException e) { valid=false; } }
            boolean expired=now>=row.getJSONObject("payload").getLong("ends") || (grant!=null && elapsed.getAsLong()>=grant.deadline);
            boolean cancelled=grant!=null && grant.stopped && !row.getString("state").equals("STOPPED");
            if(!valid || expired || cancelled) {
                drop(id,true); grants.remove(id);
                if(Set.of("ACTIVE","PENDING","SENT").contains(row.getString("state"))) { row.put("state",expired?"EXPIRED":"INTERRUPTED"); put("location-out",id,row); }
            }
            if(grant!=null && row.getString("state").equals("STOPPED")) {
                boolean pending=false;
                for(String key:db.keys("outbox")) {
                    JSONObject queued=get("outbox",key);
                    if(id.equals(queued.optString("locationSession")) && queued.getJSONObject("envelope").getLong("expires")>now) pending=true;
                }
                if(!pending) { drop(id,true); grants.remove(id); }
            }
            if(row.getLong("retainUntil")<now) db.remove("location-out",id);
        }
        for(String key:db.keys("location-in")) {
            JSONObject row=get("location-in",key);
            if(row.getLong("retainUntil")<Bytes.now()) db.remove("location-in",key);
            else {
                if(row.getString("state").equals("ACTIVE")) {
                    if(!runtime.equals(row.getString("runtime"))) row.put("state","INTERRUPTED");
                    else if(row.getJSONObject("payload").getLong("ends")<=Bytes.now() || elapsed.getAsLong()>=row.getLong("deadline") ||
                        Bytes.now()<row.getLong("received")-30) row.put("state","EXPIRED");
                }
                if(row.getJSONObject("payload").getLong("ends")+120<Bytes.now()) {
                    row.remove("lastPoint"); row.getJSONObject("payload").remove("point");
                }
                put("location-in",key,row);
            }
        }
    }
    public void authorizeDelivery(JSONObject queued) throws Exception {
        if(!queued.has("locationSession")) return;
        String session=queued.getString("locationSession"); JSONObject row=get("location-out",session); Grant grant=grants.get(session);
        if(row==null || grant==null) throw new SecurityException("Location delivery interrupted");
        grant.lease.run(); capturePolicy(grant); long now=elapsed.getAsLong();
        if(now<grant.began || now>=grant.deadline || row.getJSONObject("payload").getLong("ends")<=Bytes.now() ||
            !eligible(row.getJSONObject("payload")).contains(queued.getString("peer"))) throw new SecurityException("Location delivery no longer authorized");
        boolean stop=queued.getString("locationType").equals("LOCATION_LIVE_STOP");
        if(stop ? !row.getString("state").equals("STOPPED") : grant.stopped || !Set.of("ACTIVE","SENT","PENDING").contains(row.getString("state"))) throw new SecurityException("Location delivery stopped");
        if(!stop && engine.trustState(row.getJSONObject("payload").getString("recipient"))!=Engine.TrustState.VERIFIED) throw new SecurityException("Location trust suspended");
    }
    /** Invoked only inside Engine's decrypt/state/receipt transaction. */
    public void receive(JSONObject content) throws Exception {
        JSONObject p=content.getJSONObject("location"); LocationPayload.validate(p,Bytes.now());
        if(!p.getString("device").equals(content.getString("from")) || !p.getString("owner").equals(content.getString("logicalFrom")) ||
            !p.getString("recipient").equals(content.getString("logicalTo")) || !LocationPayload.targets(p).contains(engine.id())) throw new SecurityException("Location authenticated context mismatch");
        String key=p.getString("device")+":"+p.getString("session"); JSONObject old=get("location-in",key);
        if(old!=null && old.getString("state").equals("ACTIVE") &&
            (!runtime.equals(old.getString("runtime")) || elapsed.getAsLong()>=old.getLong("deadline") || Bytes.now()>=old.getJSONObject("payload").getLong("ends"))) {
            old.put("state","EXPIRED"); put("location-in",key,old);
        }
        if(old!=null && !LocationPayload.context(old.getJSONObject("payload")).equals(LocationPayload.context(p))) throw new SecurityException("Location session substitution");
        if(old!=null && (!old.getString("state").equals("ACTIVE") || p.getLong("seq")<=old.getJSONObject("payload").getLong("seq"))) return;
        if(old!=null && old.has("lastPoint") && p.has("point") && p.getJSONObject("point").getLong("measured")<old.getJSONObject("lastPoint").getLong("measured")) return;
        if(old==null && db.keys("location-in").size()>=256) throw new IllegalStateException("Location inbox capacity reached");
        String type=p.getString("type"); String state=type.equals("LOCATION_LIVE_STOP")?"STOPPED":type.equals("LOCATION_POINT")?"POINT":p.getLong("ends")<=Bytes.now()?"EXPIRED":"ACTIVE";
        JSONObject row=new JSONObject().put("payload",new JSONObject(p.toString())).put("state",state).put("received",Bytes.now()).put("runtime",runtime)
            .put("deadline",old==null?elapsed.getAsLong()+Math.max(0,p.getLong("ends")-Bytes.now())*1000:old.getLong("deadline"))
            .put("retainUntil",p.getLong("ends")+Engine.MAX_TTL+300);
        if(p.has("point")) row.put("lastPoint",p.getJSONObject("point"));
        else if(old!=null && old.has("lastPoint")) row.put("lastPoint",old.getJSONObject("lastPoint"));
        put("location-in",key,row);
    }
    /** Clear visible coordinates while retaining terminal replay protection. Runs in the chat transaction. */
    public void clearPeer(String peer) throws Exception {
        for(String key:db.keys("location-in")) {
            JSONObject row=get("location-in",key);
            if(row.getJSONObject("payload").getString("device").equals(peer)) {
                row.remove("lastPoint"); row.getJSONObject("payload").remove("point");
                row.put("state","INTERRUPTED").put("hidden",true); put("location-in",key,row);
            }
        }
        for(String id:db.keys("location-out")) {
            JSONObject row=get("location-out",id);
            if(LocationPayload.targets(row.getJSONObject("payload")).contains(peer)) interrupt(id);
        }
    }
    public List<JSONObject> received(String peer) throws Exception {
        return db.transaction(() -> {
            maintain(); List<JSONObject> result=new ArrayList<>();
            for(String key:db.keys("location-in")) { JSONObject row=get("location-in",key); JSONObject p=row.getJSONObject("payload");
                if(row.optBoolean("hidden") || !p.getString("device").equals(peer)) continue;
                String display=row.getString("state");
                if(display.equals("ACTIVE")) display=row.has("lastPoint") && Bytes.now()-row.getJSONObject("lastPoint").getLong("measured")<=30?"RECENT":"LAST_KNOWN";
                row.put("display",display); result.add(row);
            } return result;
        });
    }
}
