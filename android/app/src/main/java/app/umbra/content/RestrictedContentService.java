package app.umbra.content;

import app.umbra.core.*;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import app.umbra.protocol.Wire;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Bounded application access policy, not protection against a modified recipient or compromised OS. */
public final class RestrictedContentService {
    public static final int MAX_OBJECTS=128,MAX_TOMBSTONES=4096;
    private final Records db; private final Engine engine;
    // Resource accounting is shared across Engine/storage owners. It never grants
    // authorization and retains no Session, native resource, plaintext or Records.
    private static final Map<Object,ResourceSlots> RESOURCE_SCOPES=new WeakHashMap<>();
    private static synchronized ResourceSlots resourceSlots(Records db) {
        return RESOURCE_SCOPES.computeIfAbsent(Objects.requireNonNull(db.restrictedResourceScope()),ignored->new ResourceSlots());
    }
    private static final class ResourceSlots {
        private final Set<String> ids=new HashSet<>();
        synchronized Reservation reserve(String id) {
            if(ids.contains(id))throw new ContentException(ContentException.Code.BUSY);
            if(ids.size()>=4)throw new ContentException(ContentException.Code.CAPACITY);
            ids.add(id);return new Reservation(this,id);
        }
        synchronized void release(String id){ids.remove(id);}
    }
    private static final class Reservation {
        private final ResourceSlots slots;private final String id;
        private final java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
        Reservation(ResourceSlots slots,String id){this.slots=slots;this.id=id;}
        void release(){if(released.compareAndSet(false,true))slots.release(id);}
    }
    private final ResourceSlots resourceSlots;
    private final Set<Session> active=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<Prepared> pending=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore preparedSlots=new java.util.concurrent.Semaphore(4);
    private final java.util.concurrent.ScheduledThreadPoolExecutor cleanup=new java.util.concurrent.ScheduledThreadPoolExecutor(1,work->{
        Thread thread=new Thread(work,"umbra-restricted-cleanup");thread.setDaemon(true);return thread;
    });
    private final Runnable invalidated=this::closeAll;
    public RestrictedContentService(Records db,Engine engine) { this.db=db;this.engine=engine;this.resourceSlots=resourceSlots(db);cleanup.setRemoveOnCancelPolicy(true);cleanup.setKeepAliveTime(1,java.util.concurrent.TimeUnit.SECONDS);cleanup.allowCoreThreadTimeOut(true);db.onInvalidation(invalidated); }
    private JSONObject get(String bucket,String id) throws Exception { byte[] v=db.get(bucket,id);return v==null?null:Wire.parse(v,600000); }
    private void put(String bucket,String id,JSONObject value) { db.put(bucket,id,Bytes.utf8(value.toString())); }
    public final class Review {
        private final RestrictedContentService owner=RestrictedContentService.this;
        private final Runnable lease=db.authorization();
        private final long reviewedNanos=System.nanoTime();
        private final String target; private final RestrictedPayload.Mode mode;private final long ttl,seconds;
        private Review(String target,RestrictedPayload.Mode mode,long ttl,long seconds) {this.target=target;this.mode=mode;this.ttl=ttl;this.seconds=seconds;}
        private void check(boolean sending) {
            lease.run();long elapsed=System.nanoTime()-reviewedNanos;
            if(owner!=RestrictedContentService.this || sending!=(mode!=null) || elapsed<0 || elapsed>60_000_000_000L)
                throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
        }
        @Override public String toString(){return "RestrictedReview[redacted]";}
    }
    public Review reviewSend(String recipient,RestrictedPayload.Mode mode,long ttl,long sessionSeconds) throws Exception {
        return db.transaction(()->{
            db.authorization().run();engine.authorizeTransport(recipient);
            if(mode==null || ttl<60 || ttl>RestrictedPayload.MAX_TTL || sessionSeconds<1 || sessionSeconds>RestrictedPayload.MAX_SESSION)throw RestrictedPayload.invalid();
            return new Review(recipient,mode,ttl,sessionSeconds);
        });
    }
    /** Local preparation/capture consent for one originally reviewed recipient; never enables a transport. */
    public Records.Work<Void> preparationAuthorization(Review review,boolean confirmed) throws Exception {
        if(review==null || review.owner!=this || !confirmed)throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
        Records.Work<Void> check=()->db.transaction(()->{review.check(true);engine.authorizeTransport(review.target);review.check(true);return null;});
        check.run();return check;
    }
    public Review reviewOpen(String id) throws Exception {
        return db.transaction(()->{status(id);return new Review(id,null,0,0);});
    }
    /** Minted only by internal format preparation adapters; ownership transfers on send. */
    public static final class Prepared implements AutoCloseable {
        final RestrictedPayload.Format format; private byte[] bytes; private final Runnable authorization;
        Prepared(RestrictedPayload.Format format,byte[] bytes,Runnable authorization) {
            try {
                java.util.Objects.requireNonNull(authorization).run();
                if(format==null || bytes.length<1 || bytes.length>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
                this.format=format;this.bytes=bytes;this.authorization=authorization;
            } catch(RuntimeException | Error failure) {Arrays.fill(bytes,(byte)0);throw failure;}
        }
        private final java.util.concurrent.atomic.AtomicBoolean denied=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.CompletableFuture<Void> closed=new java.util.concurrent.CompletableFuture<>();
        private RestrictedContentService owner;private Review review;
        private volatile EmergencyLock.Registration registration;
        private volatile java.util.concurrent.ScheduledFuture<?> expiry;
        public java.util.concurrent.CompletionStage<Void> closure(){return closed.minimalCompletionStage();}
        private void check(Review sending) {
            if(denied.get())throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
            authorization.run();
            if(review!=null){review.check(true);if(sending!=review)throw new ContentException(ContentException.Code.CONSENT_REQUIRED);}
        }
        @Override public void close() {
            if(!denied.compareAndSet(false,true))return;
            var scheduled=expiry;if(scheduled!=null)scheduled.cancel(false);
            Runnable wipe=()->{
                synchronized(this){if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=null;}
                if(owner!=null){owner.pending.remove(this);owner.preparedSlots.release();}
                var registered=registration;if(registered!=null)registered.close();closed.complete(null);
            };
            if(owner==null)wipe.run();else owner.cleanup.execute(wipe);
        }
        @Override public String toString() { return "PreparedContent[redacted]"; }
    }
    /** Internal adapters transfer completed preparation into domain-owned, bounded cleanup. */
    Prepared retainPrepared(Review review,Prepared prepared)throws Exception {
        if(prepared==null)throw RestrictedPayload.invalid();
        try {
            preparationAuthorization(review,true).run();prepared.authorization.run();
            synchronized(prepared) {
                if(prepared.owner!=null || prepared.denied.get())throw RestrictedPayload.invalid();
                if(!preparedSlots.tryAcquire())throw new ContentException(ContentException.Code.CAPACITY);
                prepared.owner=this;prepared.review=review;pending.add(prepared);
            }
            if(db.emergency()!=null)prepared.registration=db.emergency().register(EmergencyLock.Subsystem.DOCUMENTS,()->{
                prepared.close();return prepared.closed;
            });
            if(prepared.closed.isDone() && prepared.registration!=null)prepared.registration.close();
            prepared.check(review);
            prepared.expiry=cleanup.scheduleWithFixedDelay(()->{
                try{prepared.check(review);}catch(Exception expired){prepared.close();}
            },250,250,java.util.concurrent.TimeUnit.MILLISECONDS);
            if(prepared.denied.get())prepared.expiry.cancel(false);
            return prepared;
        }catch(Exception | Error failure){prepared.close();throw failure;}
    }
    public final class Permit {
        private final String body; private final Runnable lease;
        private Permit(JSONObject payload,Runnable lease) {body=payload.toString();this.lease=lease;}
        public void check(Records records,JSONObject payload) {lease.run();if(records!=db || !body.equals(payload.toString()))throw RestrictedPayload.invalid();}
    }
    public String send(Review review,Prepared prepared,boolean confirmed) throws Exception {
        if(review==null || review.owner!=this)throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
        review.check(true);Runnable lease=()->review.check(true);
        String recipient=review.target;RestrictedPayload.Mode mode=review.mode;long ttl=review.ttl,sessionSeconds=review.seconds;
        if(!confirmed)throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
        if(prepared==null || mode==null || ttl<60 || ttl>RestrictedPayload.MAX_TTL || sessionSeconds<1 || sessionSeconds>RestrictedPayload.MAX_SESSION)throw RestrictedPayload.invalid();
        synchronized(prepared) {
            if(prepared.bytes==null)throw RestrictedPayload.invalid();
            try {
                return db.transaction(()->{
                    prepared.check(review);lease.run();engine.authorizeTransport(recipient);long now=Bytes.now();String id=UUID.randomUUID().toString();
                    JSONObject p=new JSONObject().put("v",1).put("id",id).put("from",engine.id()).put("to",recipient)
                        .put("format",prepared.format.name()).put("mode",mode.name()).put("created",now).put("expires",now+ttl).put("sessionSeconds",sessionSeconds);
                    byte[] key=Bytes.random(32);
                    try {
                        var sealed=VaultCodec.seal(new SecretKeySpec(key,"AES"),"restricted-content",RestrictedPayload.address(p),prepared.bytes);
                        p.put("key",Bytes.b64(key)).put("nonce",Bytes.b64(sealed.nonce())).put("ciphertext",Bytes.b64(sealed.ciphertext()));
                        Runnable original=()->{prepared.check(review);lease.run();};
                        original.run();engine.enqueueRestricted(new Permit(p,original),p);return id;
                    } finally {Arrays.fill(key,(byte)0);}
                });
            } finally {prepared.close();}
        }
    }
    private void expireObjects() throws Exception {
        for(String key:db.keys("restricted-object")) {
            JSONObject p=get("restricted-object",key);
            if(p.getLong("expires")<=Bytes.now())db.remove("restricted-object",key);
        }
    }
    /** Public metadata only; restricted payloads never enter Engine.messages/get/exportData. */
    public java.util.List<Status> received(String peer) throws Exception {
        return db.transaction(()->{
            db.authorization().run();engine.authorizeTransport(peer);expireObjects();
            java.util.List<Status> result=new java.util.ArrayList<>();
            for(String id:db.keys("restricted-state")) {
                JSONObject d=get("restricted-state",id).getJSONObject("descriptor");
                if(peer.equals(d.getString("from")))result.add(status(id));
            }
            return java.util.List.copyOf(result);
        });
    }
    /** Only Engine can construct authenticated ingress after real Signal decryption. */
    public void receive(Engine.RestrictedIngress ingress,JSONObject content) throws Exception {
        if(ingress==null)throw RestrictedPayload.invalid();ingress.check(db,content);
        JSONObject p=content.getJSONObject("restricted");JSONObject descriptor=RestrictedPayload.descriptor(p,Bytes.now());
        if(!p.getString("from").equals(content.getString("from")) || !p.getString("to").equals(engine.id()) || p.getLong("expires")!=content.getLong("expires"))throw RestrictedPayload.invalid();
        String id=p.getString("id"),digest=Bytes.sha256(Bytes.utf8(RestrictedPayload.address(descriptor)+"\n"+p.getString("key")+"\n"+p.getString("nonce")+"\n"+p.getString("ciphertext")));
        JSONObject prior=get("restricted-state",id);
        if(prior!=null) {if(!prior.getString("digest").equals(digest))throw RestrictedPayload.invalid();return;}
        expireObjects();
        if(db.keys("restricted-state").size()>=MAX_TOMBSTONES || db.keys("restricted-object").size()>=MAX_OBJECTS)throw new ContentException(ContentException.Code.CAPACITY);
        put("restricted-state",id,new JSONObject().put("digest",digest).put("descriptor",descriptor).put("consumed",false).put("busyUntil",0));
        put("restricted-object",id,p);
    }
    public record Status(String id,RestrictedPayload.Format format,RestrictedPayload.Mode mode,long expires,boolean consumed,boolean expired) {}
    public Status status(String id) throws Exception {
        return db.transaction(()->{
            db.authorization().run();JSONObject row=get("restricted-state",Wire.uuid(id));if(row==null)throw RestrictedPayload.invalid();
            JSONObject d=row.getJSONObject("descriptor");engine.authorizeTransport(d.getString("from"));
            return new Status(id,RestrictedPayload.Format.valueOf(d.getString("format")),RestrictedPayload.Mode.valueOf(d.getString("mode")),d.getLong("expires"),row.getBoolean("consumed"),Bytes.now()>=d.getLong("expires"));
        });
    }
    public Session open(Review review,boolean confirmed) throws Exception {
        if(review==null || review.owner!=this)throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
        review.check(false);String id=review.target;Runnable lease=review.lease;if(!confirmed)throw new ContentException(ContentException.Code.CONSENT_REQUIRED);
        final Session[] pending={null};
        final Reservation[] reservation={null};
        try {
            db.transaction(()->{
                review.check(false);JSONObject state=get("restricted-state",Wire.uuid(id));if(state==null)throw RestrictedPayload.invalid();
                JSONObject d=state.getJSONObject("descriptor");engine.authorizeTransport(d.getString("from"));
                long now=Bytes.now();if(now>=d.getLong("expires") || now<d.getLong("created"))throw new ContentException(ContentException.Code.EXPIRED);
                if(state.getBoolean("consumed"))throw new ContentException(ContentException.Code.CONSUMED);
                if(state.getLong("busyUntil")>now)throw new ContentException(ContentException.Code.BUSY);
                int busy=0;
                for(String key:db.keys("restricted-state"))if(get("restricted-state",key).getLong("busyUntil")>now)busy++;
                if(busy>=4)throw new ContentException(ContentException.Code.CAPACITY);
                reservation[0]=resourceSlots.reserve(id);
                JSONObject p=get("restricted-object",id);if(p==null)throw RestrictedPayload.invalid();
                RestrictedPayload.descriptor(p,now);
                if(!RestrictedPayload.address(p).equals(RestrictedPayload.address(d)))throw RestrictedPayload.invalid();
                byte[] key=Bytes.unb64(p.getString("key"));byte[] plain;
                try {plain=VaultCodec.open(new SecretKeySpec(key,"AES"),"restricted-content",RestrictedPayload.address(d),Bytes.unb64(p.getString("nonce")),Bytes.unb64(p.getString("ciphertext")));}
                finally {Arrays.fill(key,(byte)0);}
                long deadline=Math.min(d.getLong("expires"),now+d.getLong("sessionSeconds"));
                pending[0]=new Session(id,d,plain,lease,deadline,now,reservation[0]);
                if(d.getString("mode").equals("ONCE")) {state.put("consumed",true);db.remove("restricted-object",id);}
                state.put("busyUntil",deadline);put("restricted-state",id,state);lease.run();return null;
            });
            Session session=pending[0];session.check();active.add(session);
            if(db.emergency()!=null)session.registration=db.emergency().register(EmergencyLock.Subsystem.DOCUMENTS,()->{
                session.close();return session.closed;
            });
            if(session.closed.isDone() && session.registration!=null)session.registration.close();
            session.check();
            session.expiry=cleanup.scheduleWithFixedDelay(()->{
                try {session.check();}catch(Exception denied){session.close();}
            },250,250,java.util.concurrent.TimeUnit.MILLISECONDS);
            if(session.denied.get())session.expiry.cancel(false);
            return session;
        } catch(Exception | Error failure) {
            if(pending[0]!=null)pending[0].close();else if(reservation[0]!=null)reservation[0].release();
            throw failure;
        }
    }
    /** No public raw bytes, URI, export, seek or forwarding operation. */
    interface Decoder<T> { T decode(byte[] bytes) throws Exception; }
    interface NativeAction { void run() throws Exception; }
    public final class Session implements AutoCloseable {
        final String id;final JSONObject descriptor;private byte[] bytes;final Runnable lease;
        final long deadline,startedWall,startedNanos=System.nanoTime();
        private final java.util.concurrent.atomic.AtomicBoolean denied=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.List<AutoCloseable> resources=new java.util.ArrayList<>();
        private final java.util.concurrent.atomic.AtomicBoolean unconfirmedCleanup=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.CompletableFuture<Void> closed=new java.util.concurrent.CompletableFuture<>();
        private final Reservation reservation;
        private volatile java.util.concurrent.ScheduledFuture<?> expiry;
        private EmergencyLock.Registration registration;
        Session(String id,JSONObject descriptor,byte[] bytes,Runnable lease,long deadline,long startedWall,Reservation reservation) {this.id=id;this.descriptor=descriptor;this.bytes=bytes;this.lease=lease;this.deadline=deadline;this.startedWall=startedWall;this.reservation=reservation;}
        private void timeCheck() {
            long elapsed=System.nanoTime()-startedNanos;
            if(denied.get() || elapsed<0 || elapsed>=(deadline-startedWall)*1_000_000_000L || Bytes.now()<startedWall || Bytes.now()>=deadline)
                throw new ContentException(ContentException.Code.EXPIRED);
        }
        public void check() throws Exception {
            try {lease.run();engine.authorizeTransport(descriptor.getString("from"));timeCheck();}
            catch(Exception failure) {close();throw failure;}
        }
        <T> T decode(Decoder<T> decoder,java.util.function.Consumer<T> dispose) throws Exception {
            check(); T value;
            synchronized(this) {
                timeCheck();
                // A session owns one bounded native decoder; repeated construction must not
                // accumulate bitmaps or codec resources until expiry.
                if(!resources.isEmpty())throw new ContentException(ContentException.Code.CAPACITY);
                try {
                    value=decoder.decode(bytes);
                    resources.add(()->dispose.accept(value));
                } catch(Exception | Error failure) {
                    // A failed initialization is terminal too. Never leave plaintext/access
                    // alive until the timer merely because no decoder was registered.
                    close();throw failure;
                }
            }
            check();return value;
        }
        void use(NativeAction action) throws Exception {
            check();synchronized(this){timeCheck();action.run();}check();
        }
        public RestrictedPayload.Format format() throws org.json.JSONException {return RestrictedPayload.Format.valueOf(descriptor.getString("format"));}
        /** Internal adapters report failed cleanup even if initialization never registered a resource. */
        void cleanupFailed() {unconfirmedCleanup.set(true);close();}
        public java.util.concurrent.CompletionStage<Void> closure() {return closed.minimalCompletionStage();}
        /** Invalidation never waits for a decoder or a Records lock. Closure is confirmed separately. */
        @Override public void close() {
            if(!denied.compareAndSet(false,true))return;
            var scheduled=expiry;if(scheduled!=null)scheduled.cancel(false);
            cleanup.execute(()->{
                boolean failed=false;
                synchronized(this) {
                    for(AutoCloseable resource:resources)try{resource.close();}catch(Exception failure){failed=true;}
                    resources.clear();if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=null;
                }
                active.remove(this);
                if(failed || unconfirmedCleanup.get())closed.completeExceptionally(new IllegalStateException("Restricted resource closure failed"));
                else {reservation.release();if(registration!=null)registration.close();closed.complete(null);}
            });
        }
        @Override public String toString() {return "RestrictedSession[redacted]";}
    }
    public void closeAll() {for(Prepared prepared:pending)prepared.close();for(Session session:active)session.close();}
}
