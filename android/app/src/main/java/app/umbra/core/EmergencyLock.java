package app.umbra.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Coordinates closure; authorization remains exclusively in AccessGate. No persistence or I/O. */
public final class EmergencyLock {
    public enum State { READY, INVALIDATED, CLOSING, CLOSED, INCOMPLETE }
    public enum Subsystem { VAULT, CONNECTIVITY, NEARBY, CALLS, MEDIA, LOCATION, DOCUMENTS }
    public enum Outcome { CLOSING, CLOSED, FAILED, TIMED_OUT }
    public record Result(Subsystem subsystem, Outcome outcome, long confirmedNanos) {}
    public record Status(State state, long requestedNanos, long invalidatedNanos, long finishedNanos, List<Result> results) {}
    public static final long CLOSE_LIMIT_MILLIS=5000;
    private final AccessGate gate;
    private final Set<Registration> resources=new LinkedHashSet<>();
    private State state=State.READY;
    private long requested,invalidated,finished;
    private Object generation=new Object();
    private final Map<Registration,Result> results=new LinkedHashMap<>();
    EmergencyLock(AccessGate gate) { this.gate=gate; }
    public final class Authentication {
        private final Object epoch=generation;
        private Authentication() {}
    }
    /** Call before starting NEW platform authentication; ticket is not authentication itself. */
    public synchronized Authentication prepareAuthentication() {
        if(state!=State.CLOSED)throw new AccessGate.LockedException();
        return new Authentication();
    }
    synchronized void authenticated(Authentication ticket,Runnable authenticatedUnlock) {
        if(state!=State.CLOSED || ticket==null || ticket.epoch!=generation)throw new AccessGate.LockedException();
        try { authenticatedUnlock.run(); }
        catch(RuntimeException failure) { gate.denyForEmergency(); state=State.INCOMPLETE; throw failure; }
        state=State.READY; generation=new Object(); results.clear(); requested=invalidated=finished=0;
    }
    public synchronized Status status() { return new Status(state,requested,invalidated,finished,List.copyOf(results.values())); }
    /** Register before starting activity. Supplier completion means actual local closure, not ACK. */
    public synchronized Registration register(Subsystem subsystem,Supplier<CompletionStage<Void>> close) {
        resources.removeIf(r->r.owner!=null && r.owner.get()==null);
        if(state!=State.READY || resources.size()>=64)throw new AccessGate.LockedException();
        Registration r=new Registration(Objects.requireNonNull(subsystem),Objects.requireNonNull(close));resources.add(r);return r;
    }
    /** Domain owners may be collected only when no live operation retains them. Physical
     * resources must use register() and explicitly confirm normal closure instead. */
    public synchronized <T> Registration registerOwner(Subsystem subsystem,T owner,
            java.util.function.Function<T,CompletionStage<Void>> close) {
        var reference=new java.lang.ref.WeakReference<>(Objects.requireNonNull(owner));
        Registration registration=register(subsystem,()->{
            T current=reference.get();
            return current==null?CompletableFuture.completedFuture(null):close.apply(current);
        });
        registration.owner=reference;return registration;
    }
    public final class Registration implements AutoCloseable {
        private java.lang.ref.WeakReference<?> owner;
        private final Subsystem subsystem;
        private volatile Supplier<CompletionStage<Void>> stop;
        private Registration(Subsystem subsystem,Supplier<CompletionStage<Void>> stop) { this.subsystem=subsystem;this.stop=stop; }
        /** Unregister only after normal resource completion, never on a mere cancellation request. */
        @Override public void close() { synchronized(EmergencyLock.this) {
            resources.remove(this);
            // Drop references to closed native resources/streams retained in the result map.
            stop=()->CompletableFuture.completedFuture(null);
        } }
    }
    public Status request() {
        List<Registration> snapshot; Object expected;
        synchronized(this) {
            if(state!=State.READY)return status();
            expected=generation; requested=System.nanoTime();gate.denyForEmergency();invalidated=System.nanoTime();state=State.INVALIDATED;
            snapshot=new ArrayList<>(resources);
            for(Registration r:snapshot)results.put(r,new Result(r.subsystem,Outcome.CLOSING,0));
        }
        // Gate invalidators can perform platform cleanup. Never call them on the request thread.
        var tasks=Executors.newFixedThreadPool(Math.max(1,snapshot.size()+1),r->{Thread t=new Thread(r,"umbra-emergency-close");t.setDaemon(true);return t;});
        var timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"umbra-emergency-deadline");t.setDaemon(true);return t;});
        Registration authority=new Registration(Subsystem.VAULT,()->{gate.lock();return CompletableFuture.completedFuture(null);});
        synchronized(this) { snapshot.add(authority);results.put(authority,new Result(Subsystem.VAULT,Outcome.CLOSING,0));state=State.CLOSING; }
        timer.schedule(()->{
            synchronized(this) {
                if(generation==expected && state==State.CLOSING) {
                    results.replaceAll((r,result)->result.outcome==Outcome.CLOSING?new Result(r.subsystem,Outcome.TIMED_OUT,0):result);
                    settle();
                }
            }
            tasks.shutdown();timer.shutdown();
        },CLOSE_LIMIT_MILLIS,TimeUnit.MILLISECONDS);
        for(Registration r:snapshot)tasks.execute(()->{
            try { Objects.requireNonNull(r.stop.get()).whenComplete((unused,error)->complete(r,error)); }
            catch(Throwable failure) { complete(r,failure); }
        });
        tasks.shutdown();
        return status();
    }
    private synchronized void complete(Registration r,Throwable failure) {
        Result old=results.get(r);
        if(old==null || old.outcome!=Outcome.CLOSING)return; // Timeout remains an explicit failure, not belated green.
        results.put(r,new Result(r.subsystem,failure==null?Outcome.CLOSED:Outcome.FAILED,System.nanoTime()));
        settle();
    }
    private void settle() {
        if(results.values().stream().anyMatch(r->r.outcome==Outcome.CLOSING))return;
        state=results.values().stream().allMatch(r->r.outcome==Outcome.CLOSED)?State.CLOSED:State.INCOMPLETE;
        finished=System.nanoTime();
    }
}
