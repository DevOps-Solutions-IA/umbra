package app.umbra.calls;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import app.umbra.devices.DeviceService;
import org.json.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import static app.umbra.calls.CallPayload.*;

/** Persistent signaling only. No capture, ICE agent, media socket or ACTIVE state exists here. */
public final class CallService {
    private final Records db; private final Engine engine; private final LongSupplier elapsed;
    private final String runtime=UUID.randomUUID().toString();
    private final Map<String,Runnable> leases=new ConcurrentHashMap<>();
    private final Set<String> cancelled=ConcurrentHashMap.newKeySet();
    private final Map<String,MediaLease> mediaLeases=new ConcurrentHashMap<>();
    public CallService(Records db,Engine engine,LongSupplier elapsed) { this.db=db; this.engine=engine; this.elapsed=elapsed; }
    private void enabled() { if(!CallPlatform.ENABLED) throw new SecurityException("Calls unavailable in offline edition"); }
    private JSONObject get(String id) throws Exception { return engine.get("calls",id); }
    private void save(JSONObject row) { db.put("calls",row.optJSONObject("context").optString("callId"),Bytes.utf8(row.toString())); }
    private static JSONObject copy(JSONObject o) throws Exception { return new JSONObject(o.toString()); }
    private static boolean terminal(JSONObject r) throws Exception { return TERMINAL.contains(r.getString("state")); }
    private String ownRoot() throws Exception {
        JSONObject own=engine.get("meta","device-affiliation");
        if(own==null) throw new SecurityException("Explicit device migration required"); return own.getString("root");
    }
    private long version(String root) throws Exception {
        JSONObject row=engine.get("device-roster",root); if(row==null) throw new SecurityException("Missing call roster"); return row.getLong("version");
    }
    public final class Consent {
        private final CallService owner=CallService.this; private final JSONObject context; private final String accepting;
        private final NetworkPolicy policy; private final Runnable lease; private final long reviewed; private boolean used;
        private Consent(JSONObject context,String accepting,NetworkPolicy policy) throws Exception {
            this.context=copy(context); this.accepting=accepting; this.policy=CallPayload.policy(Objects.requireNonNull(policy).name()); lease=db.authorization(); reviewed=elapsed.getAsLong();
        }
        public String recipient() throws Exception { return context.getString(accepting.isEmpty()?"callee":"caller"); }
        public List<String> devices() throws Exception { return targets(context); }
        public NetworkPolicy networkPolicy() { return policy; }
    }
    public Consent reviewInvite(String root,NetworkPolicy policy) throws Exception {
        enabled(); return db.transaction(() -> {
            maintain(); engine.authorizeTransportSelf();
            List<String> peers=new ArrayList<>(new DeviceService(db).recipients(root)); Collections.sort(peers);
            for(String peer:peers) engine.authorizeTransport(peer);
            long now=Bytes.now(); JSONObject c=new JSONObject().put("callId",UUID.randomUUID().toString()).put("caller",ownRoot())
                .put("callerDevice",engine.id()).put("callee",root).put("targets",new JSONArray(peers))
                .put("callerVersion",version(ownRoot())).put("calleeVersion",version(root))
                .put("created",now).put("inviteUntil",now+RING_SECONDS).put("ends",now+SESSION_SECONDS);
            return new Consent(c,"",policy);
        });
    }
    public Consent reviewAccept(String id,NetworkPolicy policy) throws Exception {
        enabled(); return db.transaction(() -> {
            JSONObject row=usable(id); requireState(row,"INCOMING"); ring(row);
            return new Consent(row.getJSONObject("context"),id,policy);
        });
    }
    private void consent(Consent c,boolean confirmed) throws Exception {
        enabled(); if(c==null || c.owner!=this || c.used || !confirmed) throw new SecurityException("Explicit current call consent required");
        c.lease.run(); long age=elapsed.getAsLong()-c.reviewed;
        if(age<0 || age>30000 || Bytes.now()>=c.context.getLong("inviteUntil")) throw new SecurityException("Call consent expired");
        authorize(c.context); c.used=true;
    }
    public String invite(Consent c,boolean confirmed) throws Exception {
        return db.transaction(() -> {
            maintain(); consent(c,confirmed); if(!c.accepting.isEmpty()) throw new SecurityException("Wrong consent purpose");
            if(hasLive()) throw new SecurityException("Call already pending"); capacity();
            JSONObject row=create(c.context,"OUTGOING",c.policy); String id=c.context.getString("callId");
            leases.put(id,c.lease);
            try { save(row); emit(row,"INVITE",targets(c.context),new JSONObject()); return id; }
            catch(Exception e) { leases.remove(id); throw e; }
        });
    }
    public void accept(Consent c,boolean confirmed) throws Exception {
        db.transaction(() -> {
            consent(c,confirmed); if(c.accepting.isEmpty()) throw new SecurityException("Wrong consent purpose");
            JSONObject row=usable(c.accepting); requireState(row,"INCOMING"); ring(row);
            if(!canonical(row.getJSONObject("context")).equals(canonical(c.context))) throw new SecurityException("Call changed during review");
            row.put("policy",effective(c.policy,policy(row.getString("remotePolicy"))).name()).put("state","ACCEPTING");
            save(row); emit(row,"ACCEPT",List.of(c.context.getString("callerDevice")),new JSONObject()); return null;
        });
    }
    private JSONObject create(JSONObject c,String state,NetworkPolicy policy) throws Exception {
        long now=elapsed.getAsLong();
        return new JSONObject().put("context",copy(c)).put("state",state).put("runtime",runtime).put("began",now)
            .put("deadline",now+Math.max(0,c.getLong("ends")-Bytes.now())*1000)
            .put("ringDeadline",now+Math.max(0,c.getLong("inviteUntil")-Bytes.now())*1000)
            .put("policy",policy.name()).put("remotePolicy",policy.name()).put("selected","").put("generation",0)
            .put("events",new JSONObject()).put("rejections",new JSONObject()).put("descriptions",new JSONObject())
            .put("fingerprints",new JSONObject()).put("ice",new JSONArray()).put("sent",0);
    }
    private void capacity() { if(db.keys("calls").size()>=128) throw new IllegalStateException("Call retention capacity reached"); }
    private boolean hasLive() throws Exception { for(String id:db.keys("calls")) if(!terminal(get(id))) return true; return false; }
    private void authorize(JSONObject c) throws Exception {
        enabled(); engine.authorizeTransportSelf(); String me=engine.id(),root=ownRoot();
        boolean caller=me.equals(c.getString("callerDevice"));
        if(!root.equals(c.getString(caller?"caller":"callee")) || (!caller&&!targets(c).contains(me))) throw new SecurityException("Wrong local call participant");
        if(version(c.getString("caller"))!=c.getLong("callerVersion") || version(c.getString("callee"))!=c.getLong("calleeVersion")) throw new SecurityException("Call membership changed");
        if(caller) for(String peer:targets(c)) engine.authorizeTransport(peer);
        else engine.authorizeTransport(c.getString("callerDevice"));
    }
    private void lease(JSONObject row) throws Exception {
        String id=row.getJSONObject("context").getString("callId"); Runnable lease=leases.get(id);
        if(!runtime.equals(row.getString("runtime")) || lease==null || cancelled.contains(id)) throw new SecurityException("Call interrupted"); lease.run();
        long now=elapsed.getAsLong(); JSONObject c=row.getJSONObject("context");
        if(now<row.getLong("began") || now>=row.getLong("deadline") || Bytes.now()>=c.getLong("ends") || Bytes.now()<c.getLong("created")-30) throw new SecurityException("Call expired");
        authorize(c);
    }
    private void ring(JSONObject row) throws Exception {
        if(Bytes.now()>=row.getJSONObject("context").getLong("inviteUntil") || elapsed.getAsLong()>=row.getLong("ringDeadline")) throw new SecurityException("Call invitation expired");
    }
    private static void requireState(JSONObject row,String... allowed) throws Exception {
        if(!Arrays.asList(allowed).contains(row.getString("state"))) throw new SecurityException("Incompatible call state");
    }
    private JSONObject usable(String id) throws Exception {
        enabled(); maintain(); JSONObject row=get(id); if(row==null || terminal(row)) throw new SecurityException("Call unavailable"); lease(row); return row;
    }
    public JSONObject session(String id) throws Exception {
        enabled(); return db.transaction(() -> { maintain(); JSONObject row=get(id); if(row==null) throw new SecurityException("Unknown call"); if(!terminal(row)) lease(row); return copy(row); });
    }
    public List<JSONObject> sessions() throws Exception {
        enabled(); return db.transaction(() -> { maintain(); List<JSONObject> rows=new ArrayList<>(); for(String id:db.keys("calls")) rows.add(copy(get(id))); return rows; });
    }
    /** Cancels synchronously before any disk I/O, including callbacks waiting on the worker. */
    public void cancelLocal() { cancelled.addAll(leases.keySet()); leases.clear(); for(MediaLease media:mediaLeases.values()) media.invalidate(); mediaLeases.clear(); }
    public void cancelPending(String id) { cancelled.add(id); invalidateMedia(id); }
    private void invalidateMedia(String id) { MediaLease media=mediaLeases.remove(id); if(media!=null) media.invalidate(); }
    private void drop(String id) throws Exception {
        for(String key:db.keys("outbox")) { JSONObject q=engine.get("outbox",key); if(id.equals(q.optString("callSession"))) db.remove("outbox",key); }
    }
    private void finish(JSONObject row,String state) throws Exception {
        invalidateMedia(row.getJSONObject("context").getString("callId"));
        drop(row.getJSONObject("context").getString("callId")); row.put("state",state).put("descriptions",new JSONObject()).put("ice",new JSONArray()); save(row);
    }
    public void suspendIdentity(String identity) throws Exception {
        for(String id:db.keys("calls")) { JSONObject row=get(id),c=row.getJSONObject("context");
            if(identity.equals(c.getString("caller")) || identity.equals(c.getString("callee")) || identity.equals(c.getString("callerDevice")) || targets(c).contains(identity)) {
                cancelled.add(id); leases.remove(id); if(!terminal(row)) finish(row,"FAILED"); else drop(id);
            }
        }
    }
    public void maintain() throws Exception {
        leases.keySet().removeIf(id -> !db.keys("calls").contains(id));
        for(String id:db.keys("calls")) {
            JSONObject row=get(id); JSONObject c=row.getJSONObject("context");
            if(Bytes.now()>c.getLong("ends")+Engine.MAX_TTL+300) { drop(id); db.remove("calls",id); leases.remove(id); cancelled.remove(id); continue; }
            if(!terminal(row)) {
                try { lease(row); if(Set.of("OUTGOING","INCOMING","ACCEPTING").contains(row.getString("state"))) ring(row); }
                catch(SecurityException invalid) { finish(row,Bytes.now()>=c.getLong("inviteUntil") || (runtime.equals(row.getString("runtime")) && elapsed.getAsLong()>=row.getLong("ringDeadline"))?"EXPIRED":"FAILED"); leases.remove(id); }
            }
        }
    }
    /** Only this service can authorize exact content to be encrypted by Engine. */
    public final class Permit {
        private final String payload; private final Runnable lease;
        private Permit(JSONObject p) { payload=p.toString(); lease=db.authorization(); }
        public void check(Records records,JSONObject p) throws Exception {
            if(records!=db || !payload.equals(p.toString())) throw new SecurityException("Call capability substitution"); lease.run(); enabled();
            authorize(p.getJSONObject("context"));
            if(!Set.of("CANCEL","END","REJECT","BUSY").contains(p.getString("type"))) {
                JSONObject row=get(p.getJSONObject("context").getString("callId"));
                if(row==null || terminal(row)) throw new SecurityException("Call no longer live");
                CallService.this.lease(row);
            }
        }
    }
    private void emit(JSONObject row,String type,List<String> recipients,JSONObject data) throws Exception {
        if(row.getInt("sent")>=128) throw new SecurityException("Call control limit");
        JSONObject p=new JSONObject().put("v",1).put("purpose","UMBRA-CALL-SIGNALING").put("context",copy(row.getJSONObject("context")))
            .put("type",type).put("event",UUID.randomUUID().toString()).put("device",engine.id()).put("selected",row.getString("selected"))
            .put("generation",row.getInt("generation")).put("policy",row.getString("policy")).put("data",copy(data));
        CallPayload.validate(p,Bytes.now());
        row.put("sent",row.getInt("sent")+1); save(row);
        engine.enqueueCall(new Permit(p),p,recipients);
    }
    public void authorizeDelivery(JSONObject queued) throws Exception {
        if(!queued.has("callSession")) return; enabled(); JSONObject row=get(queued.getString("callSession"));
        if(row==null) throw new SecurityException("Call missing");
        String type=queued.getString("callType");
        // Explicit end/reject is allowed with the original live lease, never after lock/reopen.
        lease(row);
        if(terminal(row) && !Set.of("CANCEL","END","REJECT","BUSY").contains(type)) throw new SecurityException("Call delivery cancelled");
        if(Set.of("INVITE","ACCEPT").contains(type)) ring(row);
        if(type.equals("INVITE") && !row.getString("state").equals("OUTGOING")) throw new SecurityException("Call invite superseded");
        if(Set.of("DESCRIPTION","ICE").contains(type) && queued.getInt("callGeneration")!=row.getInt("generation")) throw new SecurityException("Old negotiation delivery");
    }
    public void end(String id) throws Exception {
        // UI may already have cancelledPending. Preserve cancellation on failure, allow only this synchronous terminal send.
        Runnable old=leases.get(id); cancelPending(id);
        db.transaction(() -> {
            JSONObject row=get(id); if(row==null || terminal(row)) return null;
            if(old==null) { finish(row,"FAILED"); return null; }
            old.run(); authorize(row.getJSONObject("context"));
            String type=row.getString("selected").isEmpty()? (engine.id().equals(row.getJSONObject("context").getString("callerDevice"))?"CANCEL":"REJECT") : "END";
            finish(row,type.equals("CANCEL")?"CANCELLED":type.equals("REJECT")?"REJECTED":"ENDED");
            if(Bytes.now()<row.getJSONObject("context").getLong("ends")) emit(row,type,destinations(row),new JSONObject());
            cancelled.remove(id); return null;
        });
    }
    private List<String> destinations(JSONObject row) throws Exception {
        JSONObject c=row.getJSONObject("context");
        return engine.id().equals(c.getString("callerDevice")) ? (row.getString("selected").isEmpty()?targets(c):List.of(row.getString("selected"))) : List.of(c.getString("callerDevice"));
    }
    /** Authenticated ingress token is minted only by Engine after decrypt + logical-context verification. */
    public void receive(Engine.CallIngress ingress,JSONObject content) throws Exception {
        enabled(); if(ingress==null) throw new SecurityException("Authenticated call ingress required"); ingress.check(db,content);
        JSONObject p=content.getJSONObject("call"),c=p.getJSONObject("context"); CallPayload.validate(p,Bytes.now());
        String peer=content.getString("from"),id=c.getString("callId"),type=p.getString("type"); boolean fromCaller=peer.equals(c.getString("callerDevice"));
        if(!peer.equals(p.getString("device")) || !content.getString("logicalFrom").equals(c.getString(fromCaller?"caller":"callee")) ||
            !content.getString("logicalTo").equals(c.getString(fromCaller?"callee":"caller")) || (!fromCaller&&!targets(c).contains(peer))) throw new SecurityException("Call authenticated context mismatch");
        authorize(c); maintain(); JSONObject row=get(id);
        if(row==null) {
            if(!fromCaller || !Set.of("INVITE","CANCEL").contains(type)) throw new SecurityException("Call context missing");
            capacity(); boolean busy=hasLive(); row=create(c,"INCOMING",policy(p.getString("policy"))); leases.put(id,db.authorization());
            row.put("remotePolicy",p.getString("policy"));
            if(type.equals("INVITE")) {
                ring(row);
                if(busy) { finish(row,"BUSY"); emit(row,"BUSY",List.of(peer),new JSONObject()); }
            } else finish(row,"CANCELLED");
        } else if(!canonical(row.getJSONObject("context")).equals(canonical(c))) throw new SecurityException("Call context substitution");
        JSONObject events=row.getJSONObject("events"); String event=p.getString("event"),digest=Bytes.sha256(Bytes.utf8(canonical(p)));
        if(events.has(event)) { if(!digest.equals(events.getString(event))) throw new SecurityException("Call event collision"); return; }
        if(events.length()>=128) throw new SecurityException("Call event capacity");
        if(terminal(row)) { events.put(event,digest); save(row); return; }
        lease(row);
        switch(type) {
            case "INVITE" -> { if(!fromCaller) throw new SecurityException("Only caller invites"); requireState(row,"INCOMING","ACCEPTING"); ring(row); }
            case "ACCEPT" -> {
                if(fromCaller) throw new SecurityException("Caller cannot accept"); ring(row);
                if(row.getString("selected").isEmpty()) {
                    requireState(row,"OUTGOING");
                    if(row.getJSONObject("rejections").has(peer)) throw new SecurityException("Device already rejected");
                    row.put("selected",peer).put("state","SELECTED").put("policy",effective(policy(row.getString("policy")),policy(p.getString("policy"))).name());
                    drop(id); save(row); emit(row,"SELECT",targets(c),new JSONObject());
                } // Late accepts never replace selection. Existing SELECT retries are immutable.
            }
            case "SELECT" -> {
                if(!fromCaller || p.getString("selected").isEmpty()) throw new SecurityException("Only caller selects");
                ring(row);
                if(!row.getString("selected").isEmpty() && !row.getString("selected").equals(p.getString("selected"))) throw new SecurityException("Selection replacement");
                if(p.getString("selected").equals(engine.id())) {
                    requireState(row,"ACCEPTING","SELECTED","NEGOTIATING");
                    if(effective(policy(row.getString("policy")),policy(p.getString("policy")))!=policy(p.getString("policy"))) throw new SecurityException("Network policy downgrade");
                    row.put("selected",engine.id()).put("policy",p.getString("policy"));
                    if(row.getString("state").equals("ACCEPTING")) row.put("state","SELECTED");
                } else finish(row,"NOT_SELECTED");
            }
            case "REJECT","BUSY" -> {
                if(fromCaller) throw new SecurityException("Only invitee rejects");
                row.getJSONObject("rejections").put(peer,type);
                if(row.getString("selected").equals(peer)) finish(row,"ENDED");
                else if(row.getString("selected").isEmpty() && row.getJSONObject("rejections").length()==targets(c).size()) finish(row,type.equals("BUSY")?"BUSY":"REJECTED");
            }
            case "CANCEL" -> { if(!fromCaller) throw new SecurityException("Only caller cancels"); finish(row,"CANCELLED"); }
            case "END" -> { selected(row,peer,p); finish(row,"ENDED"); }
            case "DESCRIPTION","ICE" -> { selected(row,peer,p); negotiation(row,p,peer); }
            default -> throw new SecurityException("Unknown call event");
        }
        events.put(event,digest); save(row);
    }
    private void selected(JSONObject row,String peer,JSONObject p) throws Exception {
        requireState(row,"SELECTED","NEGOTIATING"); String s=row.getString("selected");
        if(s.isEmpty() || !s.equals(p.getString("selected")) || (!peer.equals(s)&&!peer.equals(row.getJSONObject("context").getString("callerDevice"))) || !p.getString("policy").equals(row.getString("policy"))) throw new SecurityException("Wrong selected call participant or policy");
    }
    private void negotiation(JSONObject row,JSONObject p,String author) throws Exception {
        JSONObject d=p.getJSONObject("data"),descriptions=row.getJSONObject("descriptions"); int gen=p.getInt("generation");
        String caller=row.getJSONObject("context").getString("callerDevice");
        if(p.getString("type").equals("DESCRIPTION")) {
            boolean offer=d.getString("role").equals("offer");
            if(offer!=author.equals(caller)) throw new SecurityException("Wrong negotiation role");
            if(offer) {
                if(gen!=row.getInt("generation")+1 || (row.getInt("generation")>0 && descriptions.length()!=2)) throw new SecurityException("Invalid offer generation");
                row.put("generation",gen).put("descriptions",new JSONObject()).put("ice",new JSONArray()); descriptions=row.getJSONObject("descriptions");
            } else if(gen!=row.getInt("generation") || !descriptions.has(caller)) throw new SecurityException("Answer without current offer");
            JSONObject fingerprints=row.getJSONObject("fingerprints");
            if(fingerprints.has(author) && !fingerprints.getString(author).equals(d.getString("fingerprint"))) throw new SecurityException("Unexpected DTLS fingerprint change");
            if(descriptions.has(author)) throw new SecurityException("Description already committed");
            fingerprints.put(author,d.getString("fingerprint")); descriptions.put(author,copy(d)); row.put("state","NEGOTIATING");
        } else {
            if(gen!=row.getInt("generation") || !descriptions.has(author) || !descriptions.getJSONObject(author).getString("digest").equals(d.getString("description"))) throw new SecurityException("ICE description/generation mismatch");
            JSONArray ice=row.getJSONArray("ice"); if(ice.length()>=32) throw new SecurityException("ICE capacity");
            ice.put(new JSONObject().put("device",author).put("data",copy(d)));
        }
    }
    public void description(String id,int generation,String role,String sdp,String fingerprint) throws Exception {
        control(id,generation,"DESCRIPTION",new JSONObject().put("role",role).put("sdp",sdp).put("digest",Bytes.sha256(Bytes.utf8(sdp))).put("fingerprint",fingerprint));
    }
    public void ice(String id,int generation,String description,String mid,String candidate) throws Exception {
        control(id,generation,"ICE",new JSONObject().put("description",description).put("mid",mid).put("candidate",candidate));
    }
    private void control(String id,int generation,String type,JSONObject data) throws Exception {
        db.transaction(() -> {
            JSONObject row=usable(id); requireState(row,"SELECTED","NEGOTIATING");
            JSONObject p=new JSONObject().put("v",1).put("purpose","UMBRA-CALL-SIGNALING").put("context",row.getJSONObject("context"))
                .put("type",type).put("event",UUID.randomUUID().toString()).put("device",engine.id()).put("selected",row.getString("selected"))
                .put("generation",generation).put("policy",row.getString("policy")).put("data",data);
            CallPayload.validate(p,Bytes.now()); negotiation(row,p,engine.id()); save(row); emit(row,type,destinations(row),data); return null;
        });
    }
    /** Separate explicit consent for local audio, never granted by a received message. */
    public final class MediaConsent {
        private final CallService owner=CallService.this;
        private final String id, revision, selected, context;
        private final Runnable authorization;
        private final long reviewed;
        private boolean consumed;
        private MediaConsent(JSONObject row,String revision) throws Exception {
            id=row.getJSONObject("context").getString("callId");
            selected=row.getString("selected"); context=CallPayload.canonical(row.getJSONObject("context"));
            this.revision=revision; authorization=db.authorization(); reviewed=elapsed.getAsLong();
        }
    }
    private JSONObject mediaRow(String id) throws Exception {
        JSONObject row=usable(id); requireState(row,"SELECTED","NEGOTIATING");
        String self=engine.id(), caller=row.getJSONObject("context").getString("callerDevice");
        if(row.getString("selected").isEmpty() || (!self.equals(caller)&&!self.equals(row.getString("selected"))))
            throw new SecurityException("Device not selected for media");
        return row;
    }
    public MediaConsent reviewMedia(String id,String localConfigurationRevision) throws Exception {
        if(localConfigurationRevision==null || !localConfigurationRevision.matches("[a-f0-9]{64}")) throw new SecurityException("Invalid local TURN revision");
        return db.transaction(() -> new MediaConsent(mediaRow(id),localConfigurationRevision));
    }
    /** Grants a revocable lease, NOT an ACTIVE transition or evidence of native media. */
    public MediaLease prepareMedia(MediaConsent consent,boolean confirmed) throws Exception {
        return db.transaction(() -> {
            if(consent==null || consent.owner!=this || !confirmed || consent.consumed) throw new SecurityException("Fresh media consent required");
            consent.authorization.run();
            long age=elapsed.getAsLong()-consent.reviewed;
            if(age<0 || age>30_000) throw new SecurityException("Media consent expired");
            JSONObject row=mediaRow(consent.id);
            if(!consent.selected.equals(row.getString("selected")) || !consent.context.equals(CallPayload.canonical(row.getJSONObject("context"))))
                throw new SecurityException("Media consent context changed");
            MediaLease media=new MediaLease(consent);
            if(mediaLeases.putIfAbsent(consent.id,media)!=null) throw new SecurityException("Media already attached");
            consent.consumed=true;
            return media;
        });
    }
    public final class MediaLease implements AutoCloseable {
        private final String id,revision;
        private final Runnable authorization;
        private final java.util.concurrent.atomic.AtomicBoolean invalid=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicReference<Runnable> cancellation=new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean attached=new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean cancellationFailed;
        public boolean cancellationFailed() { return cancellationFailed; }
        private MediaLease(MediaConsent consent) { id=consent.id; revision=consent.revision; authorization=consent.authorization; }
        public String callId() { return id; }
        public String localDevice() throws Exception { snapshot(); return engine.id(); }
        public void end() throws Exception { CallService.this.end(id); }
        public String configurationRevision() { return revision; }
        public JSONObject snapshot() throws Exception {
            if(invalid.get()) throw new SecurityException("Media lease cancelled");
            return db.transaction(() -> {
                authorization.run();
                if(invalid.get() || mediaLeases.get(id)!=this) throw new SecurityException("Media lease cancelled");
                return copy(mediaRow(id));
            });
        }
        /** Callback must only mute/invalidate locally and schedule disposal; never wait for disk/network. */
        public void attach(Runnable onCancel) throws Exception {
            Objects.requireNonNull(onCancel);
            if(!attached.compareAndSet(false,true)) throw new SecurityException("Media lease already claimed");
            cancellation.set(onCancel);
            try { snapshot(); } catch(Exception failure) { invalidate(); throw failure; }
            if(invalid.get()) invalidateCallback();
        }
        private void invalidateCallback() {
            Runnable callback=cancellation.getAndSet(null);
            if(callback!=null) try { callback.run(); } catch(RuntimeException failure) {
                cancellationFailed=true; // Never let one adapter prevent the vault from locking.
            }
        }
        private void invalidate() { invalid.set(true); invalidateCallback(); }
        public void description(int generation,String role,String sdp,String fingerprint) throws Exception {
            snapshot(); CallService.this.description(id,generation,role,sdp,fingerprint);
        }
        public void verifyRemote(int generation,String digest,String certificateFingerprint) throws Exception {
            snapshot(); verifyRemoteBinding(id,generation,digest,certificateFingerprint);
        }
        @Override public void close() { cancelPending(id); }
    }
    /** Legacy request without fresh consent/adapter ownership remains fail-closed. */
    public void prepareMedia(String id,RelayOnlyContract localConfiguration) throws Exception {
        db.transaction(() -> {
            JSONObject row=usable(id); requireState(row,"SELECTED","NEGOTIATING");
            if(localConfiguration!=null) localConfiguration.failure(RelayOnlyContract.Failure.ADAPTER_UNAVAILABLE);
            finish(row,"FAILED"); emit(row,"END",destinations(row),new JSONObject()); return null;
        });
        throw new SecurityException(RelayOnlyContract.FAILURE);
    }
    /** Future native adapter must ALSO parse SDP and verify the actual DTLS certificate before use. */
    public void verifyRemoteBinding(String id,int generation,String digest,String actualCertificateSha256) throws Exception {
        db.transaction(() -> {
            JSONObject row=usable(id); requireState(row,"NEGOTIATING"); String peer=destinations(row).get(0);
            JSONObject d=row.getJSONObject("descriptions").optJSONObject(peer);
            if(generation!=row.getInt("generation") || d==null || !digest.equals(d.getString("digest")) || !actualCertificateSha256.equals(d.getString("fingerprint"))) throw new SecurityException("Remote media binding mismatch");
            return null; // Deliberately no ACTIVE transition or media permission.
        });
    }
}
