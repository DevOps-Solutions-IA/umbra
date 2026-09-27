package app.umbra.connectivity;

import app.umbra.admission.AdmissionService;
import app.umbra.data.Records;
import app.umbra.transport.RelayClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local consent. No constructor I/O, persisted grants or automatic reconnection. */
public final class ConnectivityService {
    public enum Policy { PRIVATE_STARTUP_STRICT }
    public enum State { LOCKED_PRIVATE, UNLOCKED_OFFLINE, CONNECTING, CONNECTED, DISCONNECTING, OFFLINE_ERROR }
    private record Frame(State state, Object generation, Runnable vault, Runnable admission, String origin) {}
    private final Records records;
    private final AdmissionService admission;
    private final boolean onlineEdition;
    private final AtomicReference<Frame> online = new AtomicReference<>(new Frame(State.LOCKED_PRIVATE,new Object(),null,null,null));
    private final AtomicReference<Lease> nearby = new AtomicReference<>();
    private final ConcurrentHashMap<Lease,Boolean> resources = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> onlineStopped = new CopyOnWriteArrayList<>();
    private final Runnable locked = this::vaultLocked;
    private volatile boolean cleanupFailed;

    public ConnectivityService(Records records, AdmissionService admission, boolean onlineEdition) {
        this.records=java.util.Objects.requireNonNull(records); this.admission=java.util.Objects.requireNonNull(admission);
        this.onlineEdition=onlineEdition; records.onInvalidation(locked);
    }
    public Policy getPolicy() { return Policy.PRIVATE_STARTUP_STRICT; }
    public boolean cleanupFailed() { return cleanupFailed; }
    /** Call after actual vault authentication, never from an OS-unlock/network callback. */
    public void vaultUnlocked() {
        Frame before=online.get();
        if(before.state!=State.LOCKED_PRIVATE) throw denied();
        Runnable grant=records.authorization(); grant.run();
        if(!online.compareAndSet(before,new Frame(State.UNLOCKED_OFFLINE,new Object(),grant,null,null))) throw denied();
        try { grant.run(); } catch(RuntimeException failure) { invalidateVault(grant); throw failure; }
    }
    public State getConnectivityState() {
        Frame f=online.get();
        if(f.vault!=null) try { f.vault.run(); } catch(RuntimeException invalid) { invalidateVault(f.vault); }
        f=online.get();
        if(f.state==State.CONNECTED) try { f.admission.run(); } catch(RuntimeException invalid) { stop(f,State.OFFLINE_ERROR); }
        return online.get().state;
    }
    public boolean canConnect() {
        State s=getConnectivityState();
        if(cleanupFailed || !onlineEdition || s!=State.UNLOCKED_OFFLINE && s!=State.OFFLINE_ERROR) return false;
        try { admission.authorization().run(); return true; } catch(Exception unavailable) { return false; }
    }
    /** Enables on-demand I/O, not a promise of Internet reachability. No socket/DNS here. */
    public void connect(String origin, boolean confirmed) throws Exception {
        Frame before=online.get();
        if(cleanupFailed || !confirmed || !onlineEdition || before.vault==null ||
                before.state!=State.UNLOCKED_OFFLINE && before.state!=State.OFFLINE_ERROR) throw denied();
        before.vault.run(); String validated=RelayClient.validate(origin); var membership=admission.authorization();
        Runnable member=() -> { try { membership.run(); } catch(Exception invalid) { throw denied(); } }; member.run();
        Frame starting=new Frame(State.CONNECTING,new Object(),before.vault,member,validated);
        if(!online.compareAndSet(before,starting)) throw denied();
        try {
            starting.vault.run(); member.run();
            if(!online.compareAndSet(starting,new Frame(State.CONNECTED,starting.generation,starting.vault,member,validated))) throw denied();
            starting.vault.run(); member.run();
            long seconds=admission.requireAdmission().expiresAt()-app.umbra.core.Bytes.now();
            if(seconds<=0) throw denied();
            var timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(work -> {
                Thread thread=new Thread(work,"umbra-connectivity-expiry"); thread.setDaemon(true); return thread;
            });
            Lease lifetime=new Lease(starting,false);
            lifetime.attach(timer::shutdownNow);
            timer.schedule(() -> stop(starting,State.OFFLINE_ERROR),seconds,java.util.concurrent.TimeUnit.SECONDS);
        } catch(Exception failure) { stop(starting,State.OFFLINE_ERROR); throw failure; }
    }
    public void disconnect() {
        Frame f=online.get();
        if(f.vault==null) { vaultLocked(); return; }
        try { f.vault.run(); } catch(RuntimeException invalid) { invalidateVault(f.vault); return; }
        stop(f,State.UNLOCKED_OFFLINE);
    }
    public void networkLost() { stop(online.get(),State.OFFLINE_ERROR); }
    public void vaultLocked() { invalidateVault(null); }
    private void invalidateVault(Runnable expected) {
        while(true) {
            Frame current=online.get();
            if(expected!=null && current.vault!=expected) return;
            if(current.state==State.LOCKED_PRIVATE) { stopNearby(); return; }
            // A cleanup already in progress owns the transition barrier. Mark its
            // destination locked, without permitting another unlock ahead of cleanup.
            if(current.state==State.DISCONNECTING) {
                if(online.compareAndSet(current,new Frame(State.DISCONNECTING,current.generation,null,null,null))) {
                    stopNearby(); return;
                }
                continue;
            }
            Frame closing=new Frame(State.DISCONNECTING,new Object(),null,null,null);
            if(online.compareAndSet(current,closing)) {
                closeOnline(current); stopNearby(); finishClosing(closing,State.LOCKED_PRIVATE); return;
            }
        }
    }
    private void finishClosing(Frame closing,State result) {
        while(true) {
            Frame current=online.get();
            if(current.generation!=closing.generation || current.state!=State.DISCONNECTING) return;
            State next=current.vault==null?State.LOCKED_PRIVATE:cleanupFailed?State.OFFLINE_ERROR:result;
            if(online.compareAndSet(current,new Frame(next,new Object(),current.vault,null,null))) return;
        }
    }
    private void stop(Frame expected, State result) {
        while(true) {
            Frame current=online.get();
            if(current.generation!=expected.generation || current.state==State.LOCKED_PRIVATE || current.state==State.DISCONNECTING) return;
            Frame closing=new Frame(State.DISCONNECTING,new Object(),current.vault,null,null);
            if(online.compareAndSet(current,closing)) {
                closeOnline(current); finishClosing(closing,result); return;
            }
        }
    }
    /** Nonblocking invalidators must not enter Records/Vault transactions. */
    public void onOnlineStopped(Runnable cleanup) { onlineStopped.add(java.util.Objects.requireNonNull(cleanup)); }
    private void closeOnline(Frame previous) {
        for(Lease lease:resources.keySet()) if(!lease.nearby && lease.granted.generation==previous.generation) lease.close();
        for(Runnable callback:onlineStopped) runCleanup(callback);
    }
    private void runCleanup(Runnable callback) {
        try { callback.run(); } catch(RuntimeException failed) { cleanupFailed=true; }
    }
    public boolean isNetworkSessionAllowed() { return getConnectivityState()==State.CONNECTED; }
    public Lease networkLease(String origin) throws Exception {
        Frame f=online.get();
        if(f.state!=State.CONNECTED || !java.util.Objects.equals(f.origin,RelayClient.validate(origin))) throw denied();
        Lease lease=new Lease(f,false); lease.check(); return lease;
    }
    /** No disk I/O in capture callbacks. Full membership checks remain at transport/media operations. */
    public Runnable onlineEpochAuthorization() {
        Frame f=online.get(); if(f.state!=State.CONNECTED) throw denied();
        return () -> {
            f.vault.run(); Frame now=online.get();
            if(now.state!=State.CONNECTED || now.generation!=f.generation) throw denied();
        };
    }
    public Runnable onlineAuthorization() {
        Frame f=online.get(); if(f.state!=State.CONNECTED) throw denied();
        Lease lease=new Lease(f,false); lease.check(); return lease::check;
    }
    /** Nearby consent is separate; membership still has to pass the Signal/admission handshake. */
    public Lease startNearby(boolean confirmed) {
        Frame f=online.get(); if(cleanupFailed || !confirmed || f.vault==null || f.state==State.DISCONNECTING) throw denied(); f.vault.run();
        Lease lease=new Lease(f,true);
        if(!nearby.compareAndSet(null,lease)) throw new IllegalStateException("Nearby already requested");
        try { lease.check(); return lease; } catch(RuntimeException failed) { lease.close(); throw failed; }
    }
    public boolean isNearbySessionAllowed() {
        Lease lease=nearby.get(); if(lease==null) return false;
        try { lease.check(); return true; } catch(RuntimeException failed) { return false; }
    }
    public void stopNearby() { Lease lease=nearby.getAndSet(null); if(lease!=null) lease.close(); }
    public void admissionInvalidated() { networkLost(); stopNearby(); }
    private static SecurityException denied() { return new SecurityException("Explicit connectivity consent required"); }

    public final class Lease implements AutoCloseable {
        private final Frame granted;
        private final boolean nearby;
        private final AtomicReference<Runnable> cleanup=new AtomicReference<>();
        private volatile boolean closed;
        private final java.util.concurrent.atomic.AtomicBoolean bound=new java.util.concurrent.atomic.AtomicBoolean();
        private Lease(Frame granted,boolean nearby) { this.granted=granted; this.nearby=nearby; }
        public void checkNearby() { if(!nearby) throw denied(); check(); }
        /** Full membership validation at operation boundaries, in addition to epoch checks. */
        public void check() {
            checkEpoch();
            if(!nearby) try { granted.admission.run(); }
            catch(RuntimeException invalid) { stop(granted,State.OFFLINE_ERROR); throw invalid; }
            checkEpoch();
        }
        /** Cheap cancellation check for I/O chunks; does not replace operation admission validation. */
        public void checkEpoch() {
            if(closed) throw denied();
            try { granted.vault.run(); } catch(RuntimeException invalid) {
                invalidateVault(granted.vault);
                throw invalid;
            }
            if(nearby) { if(ConnectivityService.this.nearby.get()!=this) throw denied(); }
            else {
                Frame now=online.get();
                if(now.state!=State.CONNECTED || now.generation!=granted.generation) throw denied();
            }
            if(closed) throw denied();
        }
        /** Register before acquiring a resource; stale registration closes rather than reactivates it. */
        public void attach(Runnable cancel) {
            java.util.Objects.requireNonNull(cancel);
            if(!bound.compareAndSet(false,true)) throw new IllegalStateException("Cleanup already bound");
            cleanup.set(cancel);
            resources.put(this,Boolean.TRUE);
            try { check(); } catch(RuntimeException invalid) { close(); throw invalid; }
        }
        public void failed() { if(!nearby) stop(granted,State.OFFLINE_ERROR); close(); }
        @Override public void close() {
            closed=true; resources.remove(this); if(nearby) ConnectivityService.this.nearby.compareAndSet(this,null);
            Runnable callback=cleanup.getAndSet(null); if(callback!=null) runCleanup(callback);
        }
    }
}
