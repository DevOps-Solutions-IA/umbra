package app.umbra.access;

import app.umbra.core.AccessGate;
import app.umbra.core.EmergencyLock;
import app.umbra.data.Vault;
import app.umbra.vault.PasswordEnvelope;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static app.umbra.access.AccessSnapshot.*;

/** Additive nonvisual access coordinator. Never connects transports, retains passwords, or persists grants.
 * Password/metadata methods run on a worker. snapshot(), background() and foreground() do no database I/O.
 * Android authentication success may be reported ONLY by the successful platform authentication callback;
 * Keystore remains the cryptographic boundary. Existing UI is not automatically integrated with this API. */
public final class AccessSession {
    private final Vault vault;
    private final AccessGate gate;
    private final AtomicLong sequence = new AtomicLong();
    private record Work(OperationResult result, long epoch) {}
    private final AtomicReference<Work> work = new AtomicReference<>(new Work(
        new OperationResult(0, Operation.NONE, OperationState.IDLE, Outcome.NONE), -1));
    private volatile boolean metadataKnown, passwordConfigured;
    private final AtomicReference<ExternalRequest> external = new AtomicReference<>();
    public AccessSession(Vault vault, AccessGate gate) {
        this.vault = java.util.Objects.requireNonNull(vault); this.gate = java.util.Objects.requireNonNull(gate);
    }
    /** Opaque process-local action context, NOT a lease, URI permission, or authorization to resume work. */
    public static final class ExternalRequest {
        private final AccessSession owner;
        private final long id, epoch;
        private final ExternalAction action;
        private final EmergencyLock.Authentication emergency;
        private ExternalRequest(AccessSession owner,long id,long epoch,ExternalAction action,EmergencyLock.Authentication emergency) {
            this.owner=owner;this.id=id;this.epoch=epoch;this.action=action;this.emergency=emergency;
        }
        @Override public String toString() { return "ExternalRequest[opaque]"; }
    }
    /** Reads structural metadata and key presence only after current Android authentication. */
    public void refresh() {
        AccessGate.Lease expected = gate.enter();
        boolean configured = vault.inspectAccessProtection(expected);
        synchronized (gate) { gate.check(expected); passwordConfigured=configured;metadataKnown=true; }
    }
    public AccessSnapshot snapshot() {
        synchronized (gate) {
            EmergencyLock.State emergency = gate.emergency().status().state();
            Vault.State v=vault.getVaultState(); // no Vault/SQLite monitor: do not invert the lock order
            AccessGate.Timing t=gate.timing();
            Work current=work.get(); OperationResult result=current.result();
            boolean active=result.state()==OperationState.WORKING;
            if(active && (!t.open() || t.epoch()<current.epoch() || t.epoch()>current.epoch()+1))
                result=new OperationResult(result.id(),result.operation(),expired(t)?OperationState.EXPIRED:OperationState.CANCELLED,Outcome.STALE);
            Phase phase;
            if(emergency==EmergencyLock.State.INCOMPLETE) phase=Phase.EMERGENCY_INCOMPLETE;
            else if(emergency==EmergencyLock.State.CLOSED) phase=Phase.EMERGENCY_CLOSED;
            else if(emergency!=EmergencyLock.State.READY || t.cause()==AccessGate.LockCause.EMERGENCY) phase=Phase.EMERGENCY_CLOSING;
            else if(v==Vault.State.CORRUPT) phase=Phase.CORRUPT;
            else if(v==Vault.State.KEY_UNAVAILABLE) phase=Phase.KEY_UNAVAILABLE;
            else if(!t.open()) {
                ExternalRequest e=external.get();
                phase=e!=null && e.action==ExternalAction.ANDROID_AUTHENTICATION && e.epoch==t.epoch()
                    ?Phase.ANDROID_AUTHENTICATING
                    : t.cause()==AccessGate.LockCause.PROCESS_RESTART?Phase.LOCKED:Phase.ANDROID_AUTH_REQUIRED;
            } else if(result.state()==OperationState.WORKING) phase=switch(result.operation()) {
                case CREATE_PASSWORD->Phase.PASSWORD_CREATE_WORKING;
                case UNLOCK->Phase.PASSWORD_UNLOCK_WORKING;
                case CHANGE_PASSWORD->Phase.PASSWORD_CHANGE_WORKING;
                default->Phase.LOCKING;
            };
            else if(v==Vault.State.UNLOCKED) phase=Phase.OPEN;
            else if(v==Vault.State.UNINITIALIZED) phase=Phase.PASSWORD_CREATE_REQUIRED;
            else if(!metadataKnown) phase=Phase.METADATA_REQUIRED;
            else if(!passwordConfigured) phase=Phase.LEGACY_ENROLLMENT_REQUIRED;
            else phase=Phase.PASSWORD_REQUIRED;
            ExternalRequest request=external.get();
            return new AccessSnapshot(phase,vault.getAutoLockPolicy(),t.effectiveRemainingNanos()/1_000_000,
                t.effectiveDeadlineNanos(),t.absoluteRemainingNanos()/1_000_000,t.epoch(),v==Vault.State.KEY_UNAVAILABLE?AccessGate.LockCause.KEY_INVALIDATED:
                v==Vault.State.CORRUPT?AccessGate.LockCause.VAULT_FAILURE:t.cause(),result,
                request==null?ExternalAction.NONE:request.action);
        }
    }
    private static boolean expired(AccessGate.Timing t) {
        return t.cause()==AccessGate.LockCause.AUTOLOCK || t.cause()==AccessGate.LockCause.ANDROID_AUTH_EXPIRED;
    }
    /** Takes ownership of the caller's byte arrays and erases them on every outcome. */
    public OperationResult createPassword(byte[] password) { return execute(Operation.CREATE_PASSWORD,password,null); }
    public OperationResult unlock(byte[] password) { return execute(Operation.UNLOCK,password,null); }
    public OperationResult changePassword(byte[] current,byte[] replacement) { return execute(Operation.CHANGE_PASSWORD,current,replacement); }
    private OperationResult execute(Operation kind,byte[] first,byte[] second) {
        Work accepted=null;
        Work observed=null;
        AccessGate.Lease lease=null;
        try {
            synchronized(gate) {
                Work before=work.get(); observed=before;
                if(before.result().state()==OperationState.WORKING)
                    return new OperationResult(sequence.incrementAndGet(),kind,OperationState.UNAVAILABLE,Outcome.BUSY);
                lease=gate.enter();
                external.set(null); // A new password operation supersedes any old external context.
                accepted=new Work(new OperationResult(sequence.incrementAndGet(),kind,OperationState.WORKING,Outcome.NONE),lease.epoch());
                work.set(accepted);
            }
            if(kind!=Operation.UNLOCK) vault.inspectAccessProtection(lease);
            switch(kind) {
                case CREATE_PASSWORD->vault.createPassword(first,PasswordEnvelope.DEFAULT,lease);
                case UNLOCK->vault.unlock(first,lease);
                case CHANGE_PASSWORD->vault.changePassword(first,second,lease);
                default->throw new IllegalStateException("Unsupported access operation");
            }
            passwordConfigured=true;metadataKnown=true;
            synchronized(gate) {
                AccessGate.Timing t=gate.timing();
                boolean opened=kind==Operation.UNLOCK && t.open() && t.epoch()==lease.epoch()+1
                    && vault.getVaultState()==Vault.State.UNLOCKED;
                OperationResult done=kind==Operation.UNLOCK && !opened
                    ?new OperationResult(accepted.result().id(),kind,expired(t)?OperationState.EXPIRED:OperationState.CANCELLED,Outcome.STALE)
                    :new OperationResult(accepted.result().id(),kind,OperationState.SUCCESS,opened?Outcome.OPENED:Outcome.COMPLETED_LOCKED);
                work.compareAndSet(accepted,new Work(done,t.epoch()));return done;
            }
        } catch(Exception failure) {
            if(lease!=null) {
                try { vault.reportAccessFailure(lease,failure); }
                catch(AccessGate.LockedException stale) { /* Never invalidate a newer authentication. */ }
                catch(RuntimeException cleanup) { /* Gate is denied; typed Vault state below remains authoritative. */ }
            }
            synchronized(gate) {
                AccessGate.Timing t=gate.timing(); Vault.State state=vault.getVaultState();
                boolean stale=accepted!=null && t.epoch()>accepted.epoch()+1;
                Outcome reason=failure instanceof Vault.PasswordCommitFailure?Outcome.COMMITTED_CLEANUP_FAILED:stale?Outcome.STALE:state==Vault.State.CORRUPT?Outcome.CORRUPT:
                    state==Vault.State.KEY_UNAVAILABLE?Outcome.KEY_UNAVAILABLE:
                    !t.open()?Outcome.AUTHENTICATION_REQUIRED:Outcome.GENERIC_FAILURE;
                OperationState status=expired(t)?OperationState.EXPIRED:
                    stale || failure instanceof AccessGate.LockedException?OperationState.CANCELLED:OperationState.FAILED;
                OperationResult done=new OperationResult(accepted==null?sequence.incrementAndGet():accepted.result().id(),kind,status,reason);
                if(accepted!=null) work.compareAndSet(accepted,new Work(done,t.epoch()));
                else if(observed!=null)work.compareAndSet(observed,new Work(done,t.epoch()));
                return done;
            }
        } finally { PasswordEnvelope.erase(first);PasswordEnvelope.erase(second); }
    }
    public void lock(AccessGate.LockCause cause) {
        synchronized(gate) {
            ExternalRequest cancelled=external.getAndSet(null);
            gate.lockWithCause(cause);
            if(cancelled!=null)work.set(new Work(new OperationResult(cancelled.id,Operation.EXTERNAL,OperationState.CANCELLED,Outcome.STALE),gate.timing().epoch()));
        }
    }
    public void background() {
        synchronized(gate) {
            ExternalRequest pending=external.get();
            // A picker/settings context is not authorization. Keep only its opaque ID while locked.
            if(pending!=null && pending.action!=ExternalAction.ANDROID_AUTHENTICATION) gate.lockWithCause(AccessGate.LockCause.BACKGROUND);
            else lock(AccessGate.LockCause.BACKGROUND);
        }
    }
    /** Observes only. Never unlocks, renews a deadline, reconnects, or resumes external work. */
    public AccessSnapshot foreground() { return snapshot(); }
    public ExternalRequest beginExternal(ExternalAction action) {
        if(action==null || action==ExternalAction.NONE)throw new IllegalArgumentException("External action required");
        EmergencyLock.Authentication emergency=null;
        if(action==ExternalAction.ANDROID_AUTHENTICATION && gate.emergency().status().state()!=EmergencyLock.State.READY)
            emergency=gate.emergency().prepareAuthentication();
        synchronized(gate) {
            if(work.get().result().state()==OperationState.WORKING)throw new IllegalStateException("Access operation busy");
            gate.lockWithCause(AccessGate.LockCause.BACKGROUND);
            long id=sequence.incrementAndGet();
            ExternalRequest request=new ExternalRequest(this,id,gate.timing().epoch(),action,emergency);
            external.set(request);
            work.set(new Work(new OperationResult(id,Operation.EXTERNAL,OperationState.REQUIRES_USER_ACTION,Outcome.EXTERNAL_ACTION_REQUIRED),request.epoch));
            return request;
        }
    }
    /** Consumes a context only. A returned picker/permission result never resumes sensitive work. */
    public OperationResult externalReturned(ExternalRequest request,boolean cancelled) {
        synchronized(gate) {
            if(request==null || request.owner!=this || work.get().result().id()!=request.id
                || work.get().result().operation()!=Operation.EXTERNAL || work.get().result().state()!=OperationState.REQUIRES_USER_ACTION
                || (request.action==ExternalAction.ANDROID_AUTHENTICATION && request.epoch!=gate.timing().epoch()) || !external.compareAndSet(request,null))
                return new OperationResult(0,Operation.EXTERNAL,OperationState.CANCELLED,Outcome.STALE);
            OperationResult result=new OperationResult(request.id,Operation.EXTERNAL,cancelled?OperationState.CANCELLED:OperationState.REQUIRES_USER_ACTION,
                cancelled?Outcome.EXTERNAL_CANCELLED:Outcome.AUTHENTICATION_REQUIRED);
            work.set(new Work(result,request.epoch));return result;
        }
    }
    /** Invoke only on platform authentication SUCCESS. Old/cancelled tickets cannot grant a new epoch.
     * This does NOT open the password vault or bypass AndroidKeyStore authentication. */
    public void androidAuthenticationSucceeded(ExternalRequest request) {
        synchronized(gate) {
            if(request==null || request.owner!=this || request.action!=ExternalAction.ANDROID_AUTHENTICATION
                || request.epoch!=gate.timing().epoch() || !external.compareAndSet(request,null))throw new AccessGate.LockedException();
            if(request.emergency==null)gate.unlock();else gate.unlock(request.emergency);
            work.set(new Work(new OperationResult(request.id,Operation.EXTERNAL,OperationState.SUCCESS,Outcome.COMPLETED_LOCKED),gate.timing().epoch()));
        }
        refresh();
    }
}
