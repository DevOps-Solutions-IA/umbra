package app.umbra.ui;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import app.umbra.core.Bytes;
import app.umbra.core.AccessGate;
import app.umbra.core.DocumentIO;
import app.umbra.protocol.Wire;
import app.umbra.BuildConfig;
import app.umbra.crypto.Engine;
import app.umbra.pairing.PairingService;
import app.umbra.data.Vault;
import app.umbra.transport.*;
import app.umbra.admission.AdmissionService;
import app.umbra.connectivity.AndroidConnectivity;
import app.umbra.ui.design.*;
import app.umbra.ui.flow.AdmissionFlow;
import app.umbra.ui.flow.VaultFlow;
import app.umbra.ui.model.*;
import app.umbra.ui.screens.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Native Android UI host. No WebView, advertising SDK, analytics SDK, phone number or address-book access.
 *
 * <p>This Activity owns the lifecycle, the lock/unlock gate and every call into the Engine and
 * services (on the worker thread). Rendering is delegated to {@code ui.screens}, which only receive
 * presentation state built here from engine-reported values; navigation rules live in {@link Navigator}.
 */
public final class MainActivity extends Activity {
    private static final int PICK_CONTACT = 201, EXPORT_CONTACT = 202, PICK_FILE = 203, EXPORT_FILE = 204,
        PICK_ADMISSION = 205, PICK_ADMIN_REVIEW = 206, PICK_ADMIN_REVOKE = 207, EXPORT_ADMISSION = 208;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean syncBusy = new AtomicBoolean(false);
    private final Map<String,String> drafts = new HashMap<>();
    private final AccessGate gate = new AccessGate();
    private volatile RelayClient activeRelay;
    private final Set<Dialog> dialogs = new HashSet<>();
    private final app.umbra.media.VoiceControls voiceControls=new app.umbra.media.VoiceControls();
    private final FeatureAvailability features = FeatureAvailability.forBuild(BuildConfig.ALLOW_RELAY, app.umbra.calls.CallPlatform.ENABLED);
    private final Navigator nav = new Navigator(features);
    private Ui ui;
    private boolean networkStateLoaded;
    private PendingResult pendingResult;
    private String pendingExportKey;
    private long pendingAttachmentTtl;
    private record PendingResult(int request, Uri uri) {}
    private Vault vault;
    private Engine engine;
    private volatile app.umbra.location.AndroidLocationCapture locationCapture;
    private String locationStatus="Sin captura de ubicación";
    private volatile BluetoothLink bluetooth;
    private volatile boolean unlocked;
    private volatile boolean networkPaused = true;
    private boolean authenticating, externalUi, destroyed, initialised, resumed, authenticationGranted, deviceSecure = true;
    private long grantedAt;
    private long authAt;
    private volatile int generation;
    private String transportStatus = "Sin conexión activa", lockProblem;
    private volatile boolean relayUnreachable;
    private JSONObject profile;
    private List<JSONObject> contacts = List.of(), messages = List.of(), locations = List.of(), callSessions = List.of();
    /** Peer whose messages/locations are currently loaded; content is never shown under another peer. */
    private String loadedPeer;
    private Map<String, TrustLevel> trust = Map.of();
    private Map<String, Integer> contactDevices = Map.of();
    private DeviceScreens.DevicesState devicesState;
    private LinearLayout root;
    private long ttl = 86400;
    private String pendingAttachmentPeer;
    private CancellationSignal authCancellation;
    // Presentation-only state (never security decisions).
    private HomeScreens.Filter chatsFilter = HomeScreens.Filter.ALL;
    private int onboardingStep;
    private SecurityScreens.Method verifyMethod = SecurityScreens.Method.CODE;
    private boolean verifyTechnical, modulatorOpen;
    private final Set<String> groupSelection = new LinkedHashSet<>(); private String groupName = ""; private int groupStep;
    private boolean locationLive; private LocationShareDraft.Precision locationPrecision = LocationShareDraft.Precision.APPROXIMATE; private int locationDuration;
    private LinearLayout locationSheetContent;
    private final Set<String> videoIntent = new HashSet<>();
    private String activeSinceCall; private long activeSince;
    private final CallScreens.Handles callHandles = new CallScreens.Handles();
    private final Map<String, Bitmap> qrCache = new HashMap<>();
    // Entry flow after Android authentication. Presentation only: the domain re-checks every operation.
    private AccessStep accessStep;
    private boolean accessBusy, legacyDeferred, passwordConfigured;
    private String accessProblem, lockNotice;
    private int autoLockIndex = PasswordPolicy.DEFAULT_AUTO_LOCK_INDEX;
    private boolean changeBusy; private String changeProblem;
    // Last domain snapshots (refreshed on the worker); never persisted or restored as authorization.
    private AdmissionFlow.Snapshot admission;
    private boolean admissionUnreadable;
    private String connectivityState = "LOCKED_PRIVATE";
    private boolean canConnect, nearbyActive, connectBusy, adminBusy;
    private volatile boolean relayResponded;
    /** Authority review held only in memory between the review sheet and the decision; cleared on lock. */
    private record PendingReview(AdmissionService.Review review, String oldCredential, AdmissionScreens.ReviewInfo info, boolean own) {}
    private PendingReview pendingReview;
    /** Posts a UI lock when the domain closes the gate (vault auto-lock, create/change password, key loss). */
    private final Runnable gateInvalidated = () -> main.post(this::gateCheck);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setHideOverlayWindows(true);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        ui = new Ui(this);
        gate.onInvalidation(gateInvalidated);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, this::back);
        showLocked(); main.postDelayed(tick, 4000);
    }
    @Override public void onResume() {
        super.onResume(); resumed = true;
        if (authenticationGranted) {
            authenticationGranted = false;
            if (SystemClock.elapsedRealtime() - grantedAt < 15_000) { completeAuthentication(); return; }
        }
        boolean wasExternal = externalUi; externalUi = false;
        if (!unlocked && !authenticating) authenticate();
        else if (unlocked && wasExternal) refresh();
    }
    @Override public void onPause() {
        super.onPause(); resumed = false;
        if (!authenticating || unlocked) lock();
    }
    @Override public void onDestroy() {
        stopLocationLocally(); destroyed = true; unlocked = false; generation++; gate.lock(); cancelRelay(); main.removeCallbacksAndMessages(null);
        if (authCancellation != null) authCancellation.cancel();
        BluetoothLink link = bluetooth; if (link != null) link.close();
        // Close after the running transaction; stale queued actions reject the locked gate.
        // A non-cooperative document provider can delay this cleanup, never the UI thread.
        worker.submit(() -> { if (vault != null) { vault.close(); vault = null; engine = null; } });
        worker.shutdown(); super.onDestroy();
    }
    private void completeAuthentication() {
        if (!resumed || destroyed || isFinishing()) return;
        authenticationGranted = false; externalUi = false; gate.unlock(); unlocked = true; authAt = SystemClock.elapsedRealtime();
        lockNotice = null; accessProblem = null; accessBusy = false; accessStep = null; engine = null; initialised = false;
        int ticket = generation;
        mount(Screen.of(null, ui.skeleton(3), null));
        worker.submit(() -> {
            Engine opened = null; boolean ready = false; AccessStep step; Exception failure = null;
            try {
                if (!unlocked || ticket != generation) return;
                gate.requireUnlocked();
                if (vault == null) vault = new Vault(getApplicationContext(), gate);
                step = VaultFlow.step(vault);
                if (step == AccessStep.LEGACY_ENROLLMENT) {
                    // Not enrolled yet: Android authentication alone opens it, exactly as before v1.
                    opened = VaultFlow.openLegacy(vault, SystemClock::elapsedRealtime); ready = opened.initialized();
                    if (ready) { vault.get("meta", "identity"); opened.expire(); }
                }
            } catch (Exception e) { failure = e; step = null; }
            final Engine engineResult = opened; final boolean readyResult = ready; final AccessStep stepResult = step; final Exception problem = failure;
            main.post(() -> {
                if (destroyed || !unlocked || ticket != generation) return;
                if (problem != null) {
                    // An invalidated/unrecoverable Android key is explained, never looped or regenerated.
                    if (keyUnavailable(problem)) { accessStep = AccessStep.KEY_UNAVAILABLE; render(); return; }
                    if (hasVaultFailure(problem)) { lock(); return; }
                    accessStep = AccessStep.CORRUPT; render(); return;
                }
                accessStep = stepResult;
                if (engineResult != null) { engine = engineResult; initialised = readyResult; passwordConfigured = false; }
                if (accessStep == AccessStep.LEGACY_ENROLLMENT && legacyDeferred) vaultOpened(); else render();
            });
        });
    }
    /** Vault open (password or legacy): continue to onboarding or home. Connectivity is UNLOCKED_OFFLINE. */
    private void vaultOpened() {
        accessStep = AccessStep.OPEN; accessBusy = false; accessProblem = null;
        if (initialised) { nav.home(); refresh(); resumeExternalResult(); } else onboarding();
    }
    // ------------------------------------------------------------------ personal password
    private void renderAccess() {
        AccessStep step = accessStep;
        if (step == null) { mount(Screen.of(null, ui.skeleton(3), null)); return; }
        switch (step) {
            case CREATE_PASSWORD, LEGACY_ENROLLMENT -> mount(AccessScreens.create(ui, new AccessScreens.CreateState(step == AccessStep.LEGACY_ENROLLMENT, accessBusy, accessProblem), new AccessScreens.CreateActions() {
                @Override public void submit(EditText password, EditText confirmation) { submitCreate(password, confirmation); }
                @Override public void later() { if (accessBusy || engine == null) return; legacyDeferred = true; vaultOpened(); }
                @Override public void lockNow() { lock(); }
            }));
            case PASSWORD_UNLOCK, OPEN -> mount(AccessScreens.unlock(ui, new AccessScreens.UnlockState(accessBusy, accessProblem, autoLockIndex, !BuildConfig.ALLOW_RELAY), new AccessScreens.UnlockActions() {
                @Override public void submit(EditText password) { submitUnlock(password); }
                @Override public void autoLock(int index) { if (!accessBusy) { autoLockIndex = index; render(); } }
                @Override public void lockNow() { lock(); }
            }));
            case CORRUPT, KEY_UNAVAILABLE -> mount(AccessScreens.failure(ui, step, this::lock));
        }
    }
    /** Reads the typed characters on the main thread into caller-owned UTF-8 bytes and clears the fields. */
    private byte[] secret(EditText field) throws java.nio.charset.CharacterCodingException {
        try { return PasswordPolicy.encode(field.getText()); } finally { field.getText().clear(); }
    }
    private static void clear(EditText... fields) { for (EditText f : fields) f.getText().clear(); }
    private void submitCreate(EditText password, EditText confirmation) {
        if (accessBusy || !unlocked) return;
        PasswordPolicy.Problem problem = PasswordPolicy.checkNew(password.getText(), confirmation.getText());
        if (problem != PasswordPolicy.Problem.OK) { clear(password, confirmation); accessProblem = problem.message; render(); return; }
        byte[] secret;
        try { secret = secret(password); } catch (Exception invalid) { accessProblem = PasswordPolicy.Problem.INVALID_CHARACTER.message; render(); return; }
        finally { clear(password, confirmation); }
        accessBusy = true; accessProblem = null; render();
        final Vault target = vault; final int ticket = generation;
        worker.submit(() -> {
            Exception failure = null;
            try {
                if (!unlocked || ticket != generation) { PasswordPolicy.erase(secret); return; }
                // Legacy engine leases end with enrollment: the domain migrates and finishes locked.
                VaultFlow.createPassword(target, secret);
            } catch (Exception e) { failure = e; }
            finally { PasswordPolicy.erase(secret); }
            final Exception problemResult = failure;
            main.post(() -> passwordOperationFinished(ticket, problemResult, AccessStep.LOCKED_AFTER_CREATE,
                "No se pudo crear la contraseña. La bóveda no cambió."));
        });
    }
    /**
     * Create/change end with the gate locked by the domain. The result may arrive after that lock: it only
     * adds the notice to the lock screen and never reopens anything. Failures keep the current session.
     */
    private void passwordOperationFinished(int ticket, Exception failure, String lockedNotice, String failedText) {
        if (destroyed) return;
        boolean ours = ticket == generation || (!unlocked && generation == ticket + 1);
        if (!ours) return;
        if (failure == null) {
            lockNotice = lockedNotice;
            if (unlocked) lock(); else showLocked();
            return;
        }
        if (!unlocked) return;
        if (hasVaultFailure(failure)) { lock(); return; }
        accessBusy = false; changeBusy = false;
        if (onRoute(Route.Kind.CHANGE_PASSWORD)) changeProblem = failedText; else accessProblem = failedText;
        render();
    }
    private void submitUnlock(EditText password) {
        if (accessBusy || !unlocked) return;
        PasswordPolicy.Problem problem = PasswordPolicy.check(password.getText());
        if (problem != PasswordPolicy.Problem.OK) { password.getText().clear(); accessProblem = problem.message; render(); return; }
        byte[] secret;
        try { secret = secret(password); } catch (Exception invalid) { accessProblem = PasswordPolicy.Problem.INVALID_CHARACTER.message; render(); return; }
        long autoLock = PasswordPolicy.AUTO_LOCK_MILLIS[autoLockIndex];
        accessBusy = true; accessProblem = null; render();
        final Vault target = vault; final int ticket = generation;
        worker.submit(() -> {
            Engine opened = null; boolean ready = false; Exception failure = null; AccessStep after = null;
            try {
                if (!unlocked || ticket != generation) return;
                opened = VaultFlow.unlock(target, secret, autoLock, SystemClock::elapsedRealtime);
                try {
                    ready = opened.initialized();
                    if (ready) { target.get("meta", "identity"); opened.expire(); }
                } catch (Exception unreadable) {
                    if (hasVaultFailure(unreadable)) throw unreadable;
                    target.lock(); // never leave the data key held without a session
                    throw new VaultFlow.RecordsUnreadable(unreadable);
                }
            } catch (Exception e) {
                failure = e;
                if (!(e instanceof VaultFlow.RecordsUnreadable))
                    try { after = VaultFlow.step(target); } catch (RuntimeException locked) { after = null; }
            } finally { PasswordPolicy.erase(secret); }
            final Engine engineResult = opened; final boolean readyResult = ready; final Exception problemResult = failure; final AccessStep afterResult = after;
            main.post(() -> {
                if (problemResult instanceof VaultFlow.RecordsUnreadable) {
                    // The vault lock above already locked the UI (gate listener); only add the explanation.
                    boolean ours = ticket == generation || (!unlocked && generation == ticket + 1);
                    if (destroyed || !ours) return;
                    lockProblem = AccessStep.RECORDS_UNREADABLE;
                    if (unlocked) lock(); else showLocked();
                    return;
                }
                // A late result after lock/destroy is discarded; the lock already invalidated its lease.
                if (destroyed || !unlocked || ticket != generation) return;
                accessBusy = false;
                if (problemResult != null) {
                    if (hasVaultFailure(problemResult) || afterResult == null) { lock(); return; }
                    accessStep = afterResult == AccessStep.OPEN ? AccessStep.PASSWORD_UNLOCK : afterResult;
                    accessProblem = AccessStep.UNLOCK_FAILED; render(); return;
                }
                engine = engineResult; initialised = readyResult; passwordConfigured = true;
                vaultOpened();
            });
        });
    }
    private void renderChangePassword() {
        mount(AccessScreens.change(ui, new AccessScreens.ChangeState(changeBusy, changeProblem), new AccessScreens.ChangeActions() {
            @Override public void back() { if (!changeBusy) { changeProblem = null; MainActivity.this.back(); } }
            @Override public void submit(EditText current, EditText replacement, EditText confirmation) { submitChange(current, replacement, confirmation); }
        }));
    }
    private void submitChange(EditText current, EditText replacement, EditText confirmation) {
        if (changeBusy || !unlocked || vault == null) return;
        PasswordPolicy.Problem problem = PasswordPolicy.check(current.getText());
        if (problem == PasswordPolicy.Problem.OK) problem = PasswordPolicy.checkNew(replacement.getText(), confirmation.getText());
        if (problem != PasswordPolicy.Problem.OK) { clear(current, replacement, confirmation); changeProblem = problem.message; render(); return; }
        byte[] old, next;
        try { old = secret(current); } catch (Exception invalid) { clear(current, replacement, confirmation); changeProblem = PasswordPolicy.Problem.INVALID_CHARACTER.message; render(); return; }
        try { next = secret(replacement); } catch (Exception invalid) { PasswordPolicy.erase(old); clear(replacement, confirmation); changeProblem = PasswordPolicy.Problem.INVALID_CHARACTER.message; render(); return; }
        finally { clear(confirmation); }
        changeBusy = true; changeProblem = null; render();
        final Vault target = vault; final int ticket = generation;
        worker.submit(() -> {
            Exception failure = null;
            try {
                if (!unlocked || ticket != generation) return;
                VaultFlow.changePassword(target, old, next);
            } catch (Exception e) { failure = e; }
            finally { PasswordPolicy.erase(old); PasswordPolicy.erase(next); }
            final Exception problemResult = failure;
            main.post(() -> passwordOperationFinished(ticket, problemResult, AccessStep.LOCKED_AFTER_CHANGE,
                "No se cambió la contraseña. Revisa la contraseña actual; la bóveda sigue igual."));
        });
    }

    /** Runs on the main thread after a gate invalidation; stale authorizations never reopen content. */
    private void gateCheck() {
        if (!unlocked || destroyed) return;
        try { gate.requireUnlocked(); } catch (AccessGate.LockedException closed) { lock(); }
    }
    private void lock() {
        stopLocationLocally(); authenticationGranted = false; gate.lock(); unlocked = false; networkPaused = true; networkStateLoaded = false; generation++; cancelRelay();
        engine = null; initialised = false; accessStep = null; accessBusy = false; accessProblem = null; changeBusy = false; changeProblem = null;
        admission = null; admissionUnreadable = false; connectivityState = "LOCKED_PRIVATE"; canConnect = false; nearbyActive = false; connectBusy = false; adminBusy = false;
        pendingReview = null; relayResponded = false; relayUnreachable = false;
        for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); dialogs.clear();
        nav.lock(); drafts.clear(); messages = List.of(); locations = List.of(); contacts = List.of(); callSessions = List.of(); trust = Map.of(); contactDevices = Map.of();
        devicesState = null; profile = null; loadedPeer = null; groupSelection.clear(); groupName = ""; qrCache.clear(); videoIntent.clear(); modulatorOpen = false; verifyTechnical = false;
        BluetoothLink link = bluetooth; bluetooth = null; if (link != null) link.close();
        transportStatus = "Bloqueado · conexiones pausadas"; showLocked();
    }
    private void authenticate() {
        if (authenticating || destroyed) return;
        KeyguardManager manager = getSystemService(KeyguardManager.class);
        if (manager == null || !manager.isDeviceSecure()) { deviceSecure = false; showLocked(); return; }
        deviceSecure = true;
        authenticating = true;
        int ticket = generation;
        worker.submit(() -> {
            Exception failure = null;
            try { Vault.prepareKey(getApplicationContext()); } catch (Exception e) { failure = e; }
            final Exception problem = failure;
            main.post(() -> {
                if (destroyed || isFinishing()) return;
                if (!resumed || ticket != generation) { authenticating = false; return; }
                if (problem != null) {
                    authenticating = false; lockProblem = "No se pudo preparar Android Keystore. No se creará una identidad sin protección."; showLocked(); return;
                }
                showAuthenticationPrompt();
            });
        });
    }
    private void showAuthenticationPrompt() {
        authCancellation = new CancellationSignal();
        BiometricPrompt prompt = new BiometricPrompt.Builder(this).setTitle("Desbloquear UMBRA")
            .setSubtitle("Protege tu identidad y tus conversaciones")
            .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG |
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build();
        prompt.authenticate(authCancellation, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                if (destroyed || isFinishing()) return;
                authenticating = false; grantedAt = SystemClock.elapsedRealtime(); lockProblem = null;
                // Device credentials can return while this Activity is paused. Never unlock in the background.
                if (!resumed) { authenticationGranted = true; return; }
                completeAuthentication();
            }
            @Override public void onAuthenticationError(int code, CharSequence text) { authenticating = false; gate.lock(); unlocked = false; showLocked(); }
        });
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (unlocked && SystemClock.elapsedRealtime() - authAt > 240_000) lock();
            else if (unlocked && initialised) syncNow();
            main.postDelayed(this, 8000);
        }
    };
    /** Updates only the call duration text once per second; never rebuilds the screen. */
    private final Runnable durationTick = new Runnable() {
        @Override public void run() {
            if (destroyed || !unlocked || !onRoute(Route.Kind.CALL)) return;
            TextView view = callHandles.duration;
            if (view != null && activeSinceCall != null) {
                String text = CallPresentation.duration((SystemClock.elapsedRealtime() - activeSince) / 1000);
                view.setText(text); view.setContentDescription("Duración " + text);
            }
            main.postDelayed(this, 1000);
        }
    };
    private <T> void action(Callable<T> operation, Consumer<T> success) { action(operation, success, e -> notice(safeError(e))); }
    /**
     * Worker operation bound to the current authentication generation. Results and failures that arrive after
     * a lock are dropped, so a late callback can never repopulate a screen or restore a session.
     */
    private <T> void action(Callable<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        int ticket = generation;
        if (worker.isShutdown()) return;
        worker.submit(() -> {
            try {
                if (!unlocked || ticket != generation) return;
                gate.requireUnlocked();
                T result = operation.call();
                main.post(() -> { if (!destroyed && unlocked && ticket == generation) success.accept(result); });
            } catch (Exception e) {
                main.post(() -> {
                    if (!destroyed && unlocked && ticket == generation) {
                        if (hasVaultFailure(e)) { lock(); notice("La bóveda necesita desbloqueo o su clave fue invalidada. No se borraron tus datos."); }
                        else failure.accept(e);
                    }
                });
            }
        });
    }
    private static boolean keyUnavailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof android.security.keystore.KeyPermanentlyInvalidatedException || t instanceof java.security.UnrecoverableKeyException) return true;
        return false;
    }
    private static boolean hasVaultFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof AccessGate.LockedException || t instanceof android.security.keystore.UserNotAuthenticatedException || t instanceof android.security.keystore.KeyPermanentlyInvalidatedException)
                return true;
        return false;
    }
    /** Human text only: known engine messages pass through; everything else maps to a category. */
    private static String safeError(Exception e) {
        String message = e.getMessage();
        ErrorKind kind = ErrorPresentation.classify(message);
        if (kind != ErrorKind.GENERIC) { ErrorPresentation p = ErrorPresentation.of(kind); return p.title() + ". " + p.body(); }
        if ((e instanceof IllegalArgumentException || e instanceof SecurityException || e instanceof IllegalStateException)
            && message != null && message.length() < 180 && !message.contains("\n")) return message;
        return ErrorPresentation.of(ErrorKind.GENERIC).body();
    }
    private void syncNow() {
        if (!unlocked || !initialised || !syncBusy.compareAndSet(false, true)) return;
        int ticket = generation;
        worker.submit(() -> {
            boolean changed = false; RelayClient relay = null;
            try {
                if (!unlocked || ticket != generation) return;
                gate.requireUnlocked(); engine.expire();
                JSONObject me = engine.profile();
                boolean online = BuildConfig.ALLOW_RELAY && me.optBoolean("registered") && engine.connectivity().isNetworkSessionAllowed();
                relay = online ? openRelay(me.getString("relay"), ticket, true) : null;
                // Do not let an unreachable internet relay prevent offline Bluetooth delivery.
                for (JSONObject queued : engine.outbox()) {
                    if (!unlocked) break;
                    String peer = queued.getString("peer");
                    JSONObject envelope = queued.getJSONObject("envelope");
                    JSONObject contact = engine.contact(peer);
                    if (contact == null || contact.optBoolean("blocked") || !contact.optBoolean("verified")) continue;
                    BluetoothLink link = bluetooth;
                    if (link != null && peer.equals(link.connectedPeer()) && queued.optLong("lastBluetooth") < Bytes.now() - 10) {
                        try {
                            String sentId = envelope.getString("id");
                            link.sendAsync(peer, envelope).whenComplete((nothing, error) -> {
                                if (error == null && unlocked && ticket == generation && !worker.isShutdown()) {
                                    worker.submit(() -> {
                                        try { if (unlocked && ticket == generation) engine.transported(sentId, false); }
                                        catch (Exception ignored) { /* A retry is safe: ciphertext is unchanged. */ }
                                    });
                                    main.post(() -> { if (unlocked && ticket == generation) refresh(); });
                                }
                            });
                        } catch (Exception ignored) { /* Bounded queue full: retry the same ciphertext next time. */ }
                    }
                }
                if (relay != null && unlocked) {
                    app.umbra.devices.DeviceService devices = new app.umbra.devices.DeviceService(vault);
                    for (JSONObject revocation : devices.pendingRelayRevocations()) {
                        relay.revokeDevice(revocation.getString("box"), revocation.getString("token"));
                        devices.relayRevoked(revocation.getString("device"), revocation.getString("box"));
                    }
                    engine.authorizeTransportSelf();
                    long cursor = 0;
                    for (int pageNumber = 0; pageNumber < 26 && unlocked && !networkPaused; pageNumber++) {
                        JSONObject batch = relay.poll(me, cursor);
                        JSONArray inbox = batch.getJSONArray("messages");
                        for (int i = 0; i < inbox.length() && unlocked; i++) {
                            JSONObject envelope = inbox.getJSONObject(i);
                            JSONObject contact = engine.contact(envelope.optString("from"));
                            if (contact == null || contact.optBoolean("blocked")) { relay.acknowledge(me, envelope.getString("id")); continue; }
                            if (!contact.optBoolean("verified")) continue;
                            try {
                                engine.receive(envelope); relay.acknowledge(me, envelope.getString("id")); changed = true;
                            } catch (Exception rejected) {
                                if (hasVaultFailure(rejected)) throw rejected;
                                // Never delete a message merely because local storage or the process failed.
                                if (rejected instanceof SecurityException || rejected instanceof IllegalArgumentException ||
                                    rejected instanceof JSONException || rejected instanceof org.signal.libsignal.protocol.InvalidMessageException ||
                                    rejected instanceof org.signal.libsignal.protocol.InvalidKeyException ||
                                    rejected instanceof org.signal.libsignal.protocol.InvalidVersionException ||
                                    rejected instanceof org.signal.libsignal.protocol.DuplicateMessageException) {
                                    relay.acknowledge(me, envelope.getString("id"));
                                } else if (!(rejected instanceof org.signal.libsignal.protocol.NoSessionException) &&
                                           !(rejected instanceof org.signal.libsignal.protocol.InvalidKeyIdException)) throw rejected;
                            }
                        }
                        cursor = batch.getLong("next_cursor"); if (!batch.getBoolean("more")) break;
                    }
                }
                for (JSONObject queued : engine.outbox()) {
                    if (!unlocked) break;
                    String peer = queued.getString("peer"); JSONObject envelope = queued.getJSONObject("envelope");
                    JSONObject contact = engine.contact(peer);
                    if (contact == null || contact.optBoolean("blocked") || !contact.optBoolean("verified")) continue;
                    if (relay != null && queued.optLong("nextRelay", 0) <= Bytes.now() && unlocked && !networkPaused && ticket == generation) {
                        try {
                            relay.sendAuthorized(engine, contact.getJSONObject("card"), envelope); engine.transported(envelope.getString("id"), true); changed = true;
                        } catch (java.io.IOException transportFailure) {
                            if (unlocked && ticket == generation) engine.relayFailed(envelope.getString("id"));
                            break; // Backoff; do not flood an unavailable server or starve local work.
                        }
                    }
                }
                // A real poll/send round trip: the only basis for saying the server responded.
                if (relay != null && ticket == generation) { relayUnreachable = false; relayResponded = true; }
            } catch (Exception e) {
                if (hasVaultFailure(e)) main.post(() -> { if (ticket == generation) lock(); });
                else if (relay != null && ticket == generation) { relayUnreachable = true; relayResponded = false; }
            } finally {
                if (relay != null) { relay.close(); if (activeRelay == relay) activeRelay = null; }
                syncBusy.set(false);
                boolean update = changed;
                main.post(() -> { if (unlocked && ticket == generation && (update || onTab(HomeTab.NEARBY) || onTab(HomeTab.CALLS) || onRoute(Route.Kind.CALL))) refresh(); });
            }
        });
    }
    private void cancelRelay() {
        RelayClient relay = activeRelay; activeRelay = null; if (relay != null) relay.close();
    }
    private RelayClient openRelay(String address, int ticket, boolean requireOnline) throws Exception {
        if (!BuildConfig.ALLOW_RELAY) throw new SecurityException("Esta edición no tiene acceso a internet");
        RelayClient relay = new RelayClient(address, () -> unlocked && ticket == generation && (!requireOnline || !networkPaused), engine.admission());
        activeRelay = relay;
        if (!unlocked || ticket != generation) { relay.close(); throw new AccessGate.LockedException(); }
        return relay;
    }
    private <T> T nearbyOperation(int ticket, Callable<T> operation) throws Exception {
        if (!unlocked || ticket != generation || worker.isShutdown()) throw new AccessGate.LockedException();
        Future<T> result = worker.submit(() -> {
            if (!unlocked || ticket != generation) throw new AccessGate.LockedException();
            gate.requireUnlocked(); return operation.call();
        });
        try { return result.get(12, TimeUnit.SECONDS); }
        catch (TimeoutException | InterruptedException e) { result.cancel(false); if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw e; }
    }
    /** The active Nearby link. Radio actions never create consent implicitly: Nearby must be started first. */
    private BluetoothLink link() {
        BluetoothLink active = bluetooth;
        if (active == null) throw new SecurityException("Activa Nearby primero");
        return active;
    }
    /** Explicit user action only (never from onCreate/onResume, render, network callbacks or restored state). */
    private void startNearby() {
        if (!unlocked || engine == null) return;
        BluetoothLink stale = bluetooth;
        if (stale != null) {
            if (engine.connectivity().isNearbySessionAllowed()) return;
            bluetooth = null; stale.close(); // The domain already ended that consent (lock, revocation).
        }
        try { bluetooth = newLink(engine.connectivity().startNearby(true)); transportStatus = "Nearby activo · sin enlace"; }
        catch (Exception refused) { notice("No se pudo activar Nearby. Requiere la bóveda abierta y una admisión vigente."); }
        refresh();
    }
    private void stopNearby() {
        BluetoothLink active = bluetooth; bluetooth = null;
        if (active != null) active.close();
        if (engine != null) engine.connectivity().stopNearby();
        transportStatus = "Nearby detenido"; refresh();
    }
    private BluetoothLink newLink(app.umbra.connectivity.ConnectivityService.Lease consent) {
        final int ticket = generation;
        return new BluetoothLink(this, new BluetoothLink.Listener() {
            @Override public String ownId() throws Exception { return nearbyOperation(ticket, () -> engine.id()); }
            @Override public JSONObject ownCard() throws Exception { return nearbyOperation(ticket, () -> engine.createCard()); }
            @Override public String acceptCard(JSONObject card) throws Exception {
                String peer = nearbyOperation(ticket, () -> engine.importCard(card));
                main.post(() -> { if (unlocked && ticket == generation) refresh(); }); return peer;
            }
            @Override public byte[] prove(boolean dialer, String peer, byte[] a, byte[] b) throws Exception {
                return nearbyOperation(ticket, () -> engine.proveNearby(dialer, peer, a, b));
            }
            @Override public void verify(boolean peerDialer, String peer, byte[] a, byte[] b, byte[] proof, boolean enrolling) throws Exception {
                nearbyOperation(ticket, () -> { engine.verifyNearby(peerDialer, peer, a, b, proof, enrolling); return null; });
            }
            @Override public void authorizeSend(String peer) throws Exception {
                nearbyOperation(ticket, () -> { engine.authorizeTransport(peer); return null; });
            }
            @Override public void authorizeEnvelope(String peer, JSONObject envelope) throws Exception {
                nearbyOperation(ticket, () -> { engine.authorizeEnvelope(envelope); return null; });
            }
            @Override public void receive(String peer, JSONObject envelope) throws Exception {
                nearbyOperation(ticket, () -> { engine.receive(envelope); return null; });
                main.post(() -> { if (unlocked && ticket == generation) { refresh(); syncNow(); } });
            }
            @Override public void status(String text) {
                main.post(() -> { if (unlocked && ticket == generation) { transportStatus = text; if (onTab(HomeTab.NEARBY)) refresh(); } });
            }
        }, consent);
    }
    private boolean bluetoothPermission() {
        String[] permissions = {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE};
        for (String permission : permissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            externalUi = true; requestPermissions(permissions, 301); return false;
        }
        return true;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results); externalUi = false;
        if (!unlocked) return;
        boolean granted = results.length > 0; for (int r : results) granted &= r == PackageManager.PERMISSION_GRANTED;
        render();
        if (!granted) {
            ErrorKind kind = switch (request) { case 301 -> ErrorKind.BLUETOOTH_DENIED; case 302 -> ErrorKind.LOCATION_DENIED; case 303 -> ErrorKind.MICROPHONE_DENIED; case 304 -> ErrorKind.CAMERA_DENIED; default -> ErrorKind.GENERIC; };
            ErrorPresentation p = ErrorPresentation.of(kind); notice(p.title() + ". " + p.body());
        } else notice("Permiso concedido. Vuelve a pulsar la acción y confirma para continuar.");
    }

    // ================================================================== state snapshot
    private record Snapshot(JSONObject profile, List<JSONObject> contacts, Map<String, TrustLevel> trust, List<JSONObject> messages,
                            List<JSONObject> locations, List<JSONObject> calls, Map<String, Integer> devices, DeviceScreens.DevicesState own,
                            String connectivity, boolean canConnect, boolean nearby, AdmissionFlow.Snapshot admission, boolean admissionUnreadable,
                            boolean passwordConfigured) {}

    private String currentPeer() {
        Route r = nav.current();
        return switch (r.kind()) { case CHAT, CONTACT, VERIFY -> r.arg(); default -> null; };
    }
    private boolean onRoute(Route.Kind kind) { return nav.current().kind() == kind; }
    private boolean onTab(HomeTab tab) { return onRoute(Route.Kind.HOME) && nav.tab() == tab; }

    private void refresh() {
        if (!unlocked || engine == null) return;
        String selected = currentPeer();
        boolean wantDevices = onRoute(Route.Kind.DEVICES);
        action(() -> {
            // Domain-owned connectivity snapshot; persisted profile preferences never restore consent.
            var conn = engine.connectivity();
            networkPaused = !conn.isNetworkSessionAllowed();
            String connState = conn.getConnectivityState().name(); boolean connectable = conn.canConnect(), near = conn.isNearbySessionAllowed();
            AdmissionFlow.Snapshot adm; boolean admUnreadable = false;
            try { adm = AdmissionFlow.read(engine.admission(), Bytes.now()); }
            catch (Exception e) { if (hasVaultFailure(e)) throw e; adm = null; admUnreadable = true; } // never blocks the rest of the snapshot
            boolean enrolled = vault.isPasswordConfigured();
            JSONObject me = engine.profile();
            List<JSONObject> all = engine.contacts();
            Map<String, TrustLevel> levels = new HashMap<>(); Map<String, Integer> counts = new HashMap<>();
            for (JSONObject c : all) {
                String id = c.optString("id");
                levels.put(id, TrustLevel.fromEngine(engine.trustState(id).name()));
                counts.put(id, rosterSize(id));
            }
            List<JSONObject> calls = BuildConfig.ALLOW_RELAY && app.umbra.calls.CallPlatform.ENABLED ? engine.calls().sessions() : List.of();
            return new Snapshot(me, all, levels, selected == null ? List.of() : engine.messages(selected),
                selected == null ? List.of() : engine.locations().received(selected), calls, counts, wantDevices ? ownDevices(all, counts) : null,
                connState, connectable, near, adm, admUnreadable, enrolled);
        }, s -> {
            profile = s.profile();
            connectivityState = s.connectivity(); canConnect = s.canConnect(); nearbyActive = s.nearby();
            admission = s.admission(); admissionUnreadable = s.admissionUnreadable(); passwordConfigured = s.passwordConfigured();
            networkStateLoaded = true;
            contacts = s.contacts(); trust = s.trust(); contactDevices = s.devices(); callSessions = s.calls();
            if (s.own() != null) devicesState = s.own();
            if (Objects.equals(currentPeer(), selected)) { messages = s.messages(); locations = s.locations(); loadedPeer = selected; }
            // Never rebuild a screen that holds typed secrets; its own actions re-render it.
            if (accessStep == AccessStep.OPEN && !onRoute(Route.Kind.CHANGE_PASSWORD)) render();
        });
    }
    /** Active members in the contact's signed roster; -1 if no roster was approved or it is stale. Worker thread. */
    private int rosterSize(String peer) {
        try {
            JSONObject index = engine.get("device-index", peer);
            if (index == null) return -1;
            JSONObject row = engine.get("device-roster", index.getString("root"));
            if (row == null) return -1;
            app.umbra.devices.DeviceRoster roster = app.umbra.devices.DeviceRoster.parse(row.getString("transcript"));
            int active = 0; for (String id : roster.members.keySet()) if (roster.active(id)) active++;
            return active;
        } catch (Exception unreadable) { return -1; }
    }
    /** Own signed roster as reported by the stored record. Worker thread. */
    private DeviceScreens.DevicesState ownDevices(List<JSONObject> all, Map<String, Integer> counts) throws Exception {
        String self = engine.id(); List<DeviceItem> items = new ArrayList<>(); String problem = null; boolean configured = false;
        JSONObject affiliation = engine.get("meta", "device-affiliation");
        if (affiliation != null) {
            configured = true;
            try {
                String rootId = affiliation.getString("root");
                JSONObject row = engine.get("device-roster", rootId);
                app.umbra.devices.DeviceRoster roster = app.umbra.devices.DeviceRoster.parse(row.getString("transcript"));
                boolean admin = rootId.equals(self);
                items.add(DeviceItem.of(self, true, roster.active(self), admin));
                for (String id : roster.members.keySet()) if (!id.equals(self)) items.add(DeviceItem.of(id, false, roster.active(id), admin));
            } catch (Exception stale) {
                problem = "No se pudo leer la lista firmada de dispositivos (puede haber caducado). No se asumió ningún estado.";
                items.add(DeviceItem.of(self, true, true, false));
            }
        } else items.add(DeviceItem.of(self, true, true, false));
        List<DeviceScreens.ContactDevices> others = new ArrayList<>();
        for (JSONObject c : all) others.add(new DeviceScreens.ContactDevices(alias(c), counts.getOrDefault(c.optString("id"), -1)));
        return new DeviceScreens.DevicesState(items, configured, problem, others, features);
    }

    // ================================================================== rendering
    /** Clears typed secrets of the screen being replaced so they do not linger in detached views. */
    private static void clearSecrets(View view) {
        if (view instanceof EditText e && (e.getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) e.getText().clear();
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) clearSecrets(g.getChildAt(i));
    }
    private void mount(Screen screen) {
        if (root != null) clearSecrets(root);
        root = screen.compose(ui);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(ui.dp(16) + system.left, ui.dp(6) + system.top, ui.dp(16) + system.right, ui.dp(6) + Math.max(system.bottom, keyboard.bottom));
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
    }
    private void showLocked() {
        nav.lock();
        mount(EntryScreens.lock(ui, new EntryScreens.LockState(deviceSecure, !BuildConfig.ALLOW_RELAY, lockProblem, lockNotice), new EntryScreens.LockActions() {
            @Override public void unlock() { authenticate(); }
            @Override public void openSecuritySettings() { external(new Intent(Settings.ACTION_SECURITY_SETTINGS), 0); }
        }));
    }
    private void onboarding() { nav.onboarding(); onboardingStep = 0; render(); }

    private void render() {
        if (!unlocked) { showLocked(); return; }
        callHandles.duration = null;
        if (accessStep != AccessStep.OPEN) { renderAccess(); return; }
        Route route = nav.current();
        if (route.kind() == Route.Kind.ONBOARDING) { renderOnboarding(); return; }
        if (profile == null) return;
        switch (route.kind()) {
            case HOME -> renderHome();
            case CHAT -> renderChat(route.arg());
            case CONTACT -> renderContact(route.arg());
            case VERIFY -> renderVerify(route.arg());
            case NEW_CHAT -> renderNewChat();
            case NEW_GROUP -> renderNewGroup();
            case DEVICES -> renderDevices();
            case SETTINGS_SECTION -> renderSettings(SettingsSection.valueOf(route.arg()));
            case CALL -> renderCall(route.arg());
            case INCOMING_CALL -> renderIncoming(route.arg());
            case ADMISSION -> renderAdmission();
            case ADMISSION_ADMIN -> renderAdmin();
            case CHANGE_PASSWORD -> renderChangePassword();
            default -> { nav.home(); renderHome(); }
        }
    }
    private void go(Route route) { if (nav.push(route)) render(); else notice(ErrorPresentation.of(ErrorKind.FEATURE_PENDING).title()); }

    private void renderOnboarding() {
        mount(EntryScreens.onboarding(ui, onboardingStep, !BuildConfig.ALLOW_RELAY, new EntryScreens.OnboardingActions() {
            @Override public void step(int next) { onboardingStep = next; renderOnboarding(); }
            @Override public void create(String alias) {
                // Identity creation never admits or connects; continue to this device's admission.
                action(() -> { engine.initialize(alias); initialised = true; return true; }, ok -> { nav.home(); nav.push(Route.of(Route.Kind.ADMISSION)); refresh(); });
            }
        }));
    }

    private View navBar() { return HomeScreens.bottomNav(ui, features, nav.tab(), tab -> { if (nav.selectTab(tab)) refresh(); }); }

    private void renderHome() {
        View bar = navBar();
        switch (nav.tab()) {
            case CALLS -> mount(HomeScreens.calls(ui, callRows(), voiceControls.hasSession(), new HomeScreens.CallsActions() {
                @Override public void open(HomeScreens.CallRow row) { go(Route.of(row.state().incoming() && "INCOMING".equals(sessionState(row.callId())) ? Route.Kind.INCOMING_CALL : Route.Kind.CALL, row.callId())); }
                @Override public void startFromChats() { nav.selectTab(HomeTab.CHATS); render(); }
            }, bar));
            case NEARBY -> mount(HomeScreens.nearby(ui, new HomeScreens.NearbyState(transportStatus, !BuildConfig.ALLOW_RELAY, connectivity(),
                nearbyActive && bluetooth != null, admissionPresentation().admitted()), nearbyActions(), bar));
            case SETTINGS -> mount(HomeScreens.settings(ui, profile.optString("alias"), connectivity(), new HomeScreens.SettingsActions() {
                @Override public void open(SettingsSection section) {
                    Route target = switch (section) {
                        case DEVICES -> Route.of(Route.Kind.DEVICES);
                        case ADMISSION -> Route.of(Route.Kind.ADMISSION);
                        default -> Route.of(Route.Kind.SETTINGS_SECTION, section.name());
                    };
                    go(target); refresh();
                }
                @Override public void lockNow() { lock(); }
            }, bar));
            default -> {
                List<ConversationItem> items = new ArrayList<>();
                for (JSONObject c : contacts) items.add(ConversationItem.direct(c.optString("id"), alias(c), trust.getOrDefault(c.optString("id"), TrustLevel.UNVERIFIED), ""));
                HomeScreens.IncomingCall incoming = null;
                for (JSONObject s : callSessions) if ("INCOMING".equals(s.optString("state"))) { incoming = new HomeScreens.IncomingCall(callId(s), aliasFor(otherParty(s))); break; }
                String notice = relayUnreachable && BuildConfig.ALLOW_RELAY && !networkPaused ? ErrorPresentation.of(ErrorKind.RELAY_UNAVAILABLE).title() : null;
                mount(HomeScreens.chats(ui, new HomeScreens.ChatsState(items, false, chatsFilter, features, !BuildConfig.ALLOW_RELAY, connectivity(),
                    admission == null ? null : admissionPresentation(), notice, incoming), new HomeScreens.ChatsActions() {
                    @Override public void open(ConversationItem item) { go(Route.of(item.group() ? Route.Kind.GROUP_CHAT : Route.Kind.CHAT, item.id())); refresh(); }
                    @Override public void newMessage() { go(Route.of(Route.Kind.NEW_CHAT)); }
                    @Override public void newGroup() { groupStep = 0; go(Route.of(Route.Kind.NEW_GROUP)); }
                    @Override public void addContact() { addContactSheet(); }
                    @Override public void filter(HomeScreens.Filter f) { chatsFilter = f; render(); }
                    @Override public void openIncoming(String id) { go(Route.of(Route.Kind.INCOMING_CALL, id)); }
                    @Override public void networkDetails() { go(Route.of(Route.Kind.SETTINGS_SECTION, SettingsSection.NETWORK.name())); }
                    @Override public void admission() { go(Route.of(Route.Kind.ADMISSION)); refresh(); }
                }, bar));
            }
        }
    }

    private HomeScreens.NearbyActions nearbyActions() {
        return new HomeScreens.NearbyActions() {
            @Override public void startNearby() { MainActivity.this.startNearby(); }
            @Override public void stopNearby() { MainActivity.this.stopNearby(); }
            @Override public void networkSettings() { go(Route.of(Route.Kind.SETTINGS_SECTION, SettingsSection.NETWORK.name())); }
            @Override public void listen() { if (bluetooth == null) { notice("Activa Nearby primero"); return; } if (!bluetoothPermission()) return; try { link().listen(); render(); } catch (Exception e) { notice(safeError(e)); } }
            @Override public void makeVisible() { if (bluetooth == null) { notice("Activa Nearby primero"); return; } if (!bluetoothPermission()) return; external(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 0); }
            @Override public void connectVerified() { chooseBluetooth(false); }
            @Override public void enrollNew() {
                confirm("Vinculación explícita", "Esta acción intercambia una tarjeta con identidad pública, alias y permiso de escritura al buzón. Verifica después el código completo en persona.", "Continuar", false, () ->
                    new SecureDialogBuilder().setTitle("En ambos teléfonos elige vinculación nueva")
                        .setItems(new String[]{"Esperar al nuevo contacto", "Conectar al nuevo contacto"}, (d, item) -> {
                            if (!bluetoothPermission()) return;
                            try { if (item == 0) { link().listen(true); render(); } else chooseBluetooth(true); }
                            catch (Exception e) { notice(safeError(e)); }
                        }).setNegativeButton("Cancelar", null).show());
            }
            @Override public void systemSettings() { external(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0); }
        };
    }

    // ------------------------------------------------------------------ explicit network consent
    private ConnectivityPresentation connectivity() {
        ConnectivityPresentation.Service service = relayUnreachable ? ConnectivityPresentation.Service.UNREACHABLE
            : relayResponded ? ConnectivityPresentation.Service.RESPONDED : ConnectivityPresentation.Service.NOT_OBSERVED;
        boolean relayKnown = profile != null && !profile.optString("relay").isEmpty();
        return ConnectivityPresentation.of(connectivityState, !BuildConfig.ALLOW_RELAY, canConnect, nearbyActive && bluetooth != null, relayKnown, service);
    }
    private AdmissionPresentation admissionPresentation() {
        AdmissionFlow.Snapshot a = admission;
        if (a == null) return admissionUnreadable ? AdmissionPresentation.unreadable() : AdmissionPresentation.notRead();
        return AdmissionPresentation.of(a.state(), a.request() != null, a.request() != null && a.request().expired());
    }
    /** The person pressed "Conectar": the only place that requests an online session (confirmed=true). */
    private void connectNetwork() {
        if (!BuildConfig.ALLOW_RELAY || connectBusy || !unlocked || engine == null) return;
        connectBusy = true; relayUnreachable = false; relayResponded = false;
        action(() -> { AndroidConnectivity.connect(this, engine.connectivity(), engine.profile().getString("relay"), true); return engine.connectivity().isNetworkSessionAllowed(); },
            allowed -> { connectBusy = false; networkPaused = !allowed; refresh(); if (allowed) syncNow(); },
            failure -> { connectBusy = false; notice("No se habilitó la red. Requiere admisión vigente, una red disponible y el servidor configurado."); refresh(); });
    }
    /** Revokes the online session without locking the vault or stopping Nearby. */
    private void disconnectNetwork() {
        if (engine == null) return;
        networkPaused = true; engine.connectivity().disconnect(); cancelRelay(); relayResponded = false; relayUnreachable = false;
        refresh();
    }
    private void chooseBluetooth(boolean enroll) {
        if (bluetooth == null) { notice("Activa Nearby primero"); return; }
        if (!bluetoothPermission()) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            notice("Se necesita permiso para consultar los dispositivos cercanos."); return;
        }
        try {
            BluetoothAdapter adapter = link().adapter();
            if (adapter == null || !adapter.isEnabled()) { ErrorPresentation p = ErrorPresentation.of(ErrorKind.BLUETOOTH_UNAVAILABLE); notice(p.title() + ". " + p.body()); return; }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            if (devices.isEmpty()) { notice("Primero empareja ambos teléfonos en los ajustes Bluetooth de Android."); return; }
            String[] names = new String[devices.size()];
            for (int i = 0; i < names.length; i++) names[i] = Objects.toString(devices.get(i).getName(), "Dispositivo") + " · " + devices.get(i).getAddress();
            new SecureDialogBuilder().setTitle("Teléfono que está esperando").setItems(names, (d, index) -> {
                try { link().connect(devices.get(index), enroll); } catch (Exception e) { notice(safeError(e)); }
            }).setNegativeButton("Cancelar", null).show();
        } catch (SecurityException e) {
            notice("El permiso Bluetooth fue revocado. Revisa los permisos de dispositivos cercanos.");
        } catch (Exception e) { notice("No se pudo abrir Bluetooth. Revisa los permisos de dispositivos cercanos."); }
    }

    private void addContactSheet() {
        LinearLayout box = ui.column();
        Dialog[] sheet = new Dialog[1];
        box.addView(ui.heading(UmbraType.TITLE, "Agregar contacto"));
        box.addView(ui.text(UmbraType.CAPTION, "Tener un archivo o estar cerca no verifica a la persona: después comparen el código de seguridad."), ui.margins(Ui.match(), 2, 8));
        box.addView(ui.listRow(ui.iconTile(Glyph.PERSON_ADD, Tone.ACCENT), "Crear invitación", "Archivo de un solo uso que vence en 1 hora", ui.chevron(), () -> { sheet[0].dismiss(); createInvitation(); }));
        box.addView(ui.listRow(ui.iconTile(Glyph.FILE, Tone.ACCENT), "Importar invitación", "Invitación, solicitud o confirmación recibida", ui.chevron(), () -> { sheet[0].dismiss(); pickContact(); }));
        box.addView(ui.listRow(ui.iconTile(Glyph.BLUETOOTH, Tone.OFFLINE), "Conectar por Bluetooth", "Con un teléfono cercano", ui.chevron(), () -> { sheet[0].dismiss(); nav.selectTab(HomeTab.NEARBY); render(); }));
        sheet[0] = SecureDialogs.sheet(this, this::track, box);
    }
    private void createInvitation() {
        action(() -> { String invite = new PairingService(vault).createInvitation(3600); return engine.stageExport(Bytes.utf8(invite)); }, this::exportPairing);
    }

    // ------------------------------------------------------------------ chat
    private JSONObject uiContact(String id) { for (JSONObject c : contacts) if (id.equals(c.optString("id"))) return c; return null; }
    private static String alias(JSONObject contact) { JSONObject card = contact.optJSONObject("card"); String a = card == null ? "" : card.optString("alias"); return a.isBlank() ? "Contacto " + Fingerprints.shortId(contact.optString("id")) : a; }
    private String aliasFor(String id) { if (id == null) return "Contacto"; JSONObject c = uiContact(id); return c == null ? "Contacto " + Fingerprints.shortId(id) : alias(c); }
    private TrustPresentation trustOf(String peer) { return TrustPresentation.of(trust.getOrDefault(peer, TrustLevel.UNVERIFIED)); }
    private static String time(long epochSeconds) { return android.text.format.DateFormat.format("HH:mm", new Date(epochSeconds * 1000)).toString(); }

    private void renderChat(String peer) {
        JSONObject contact = uiContact(peer); if (contact == null) { nav.back(); render(); return; }
        TrustPresentation t = trustOf(peer);
        Map<String, JSONObject> byId = new HashMap<>();
        List<ChatScreens.Entry> entries = new ArrayList<>();
        boolean loaded = peer.equals(loadedPeer);
        for (JSONObject m : loaded ? messages : List.<JSONObject>of()) {
            String id = m.optString("id", String.valueOf(entries.size()));
            byId.put(id, m);
            boolean out = m.optBoolean("outgoing"); String when = time(m.optLong("created"));
            MessageItem item = "file".equals(m.optString("kind"))
                ? MessageItem.file(id, out, m.optString("name"), Math.max(0, m.optString("data").length() * 3 / 4), when, out ? m.optString("status") : null, null)
                : MessageItem.text(id, out, m.optString("text"), when, out ? m.optString("status") : null, null);
            entries.add(new ChatScreens.Entry(item, null));
        }
        for (JSONObject l : loaded ? locations : List.<JSONObject>of()) {
            JSONObject payload = l.optJSONObject("payload"), point = l.optJSONObject("lastPoint");
            String display = l.optString("display");
            String title = switch (display) { case "RECENT" -> "Ubicación reciente de " + alias(contact); case "LAST_KNOWN" -> "Última ubicación conocida de " + alias(contact); default -> "Ubicación de " + alias(contact) + " · " + display.toLowerCase(Locale.ROOT); };
            String detail = (payload == null ? "" : precisionLabel(payload.optString("mode"))) + (point == null ? " · sin punto recibido" :
                String.format(Locale.ROOT, " · %.5f, %.5f · medida %s", point.optLong("latE7") / 1e7, point.optLong("lonE7") / 1e7, time(point.optLong("measured"))));
            entries.add(new ChatScreens.Entry(null, new ChatScreens.LocationEntry(title, detail, "RECENT".equals(display), false)));
        }
        var capture = locationCapture; boolean sharing = capture != null && capture.activeSession() != null;
        VoiceSnapshotView voice = voice();
        mount(ChatScreens.direct(ui, new ChatScreens.ChatState(peer, alias(contact), t, entries, ttlName(), drafts.get(peer), sharing, locationStatus, features, !BuildConfig.ALLOW_RELAY, voice != null), new ChatScreens.ChatActions() {
            @Override public void back() { MainActivity.this.back(); }
            @Override public void contact() { go(Route.of(Route.Kind.CONTACT, peer)); }
            @Override public void verify() { verifyMethod = SecurityScreens.Method.CODE; go(Route.of(Route.Kind.VERIFY, peer)); }
            @Override public void unblock() { setBlocked(peer, false); }
            @Override public void voiceCall() { startCall(peer, false); }
            @Override public void videoCall() { startCall(peer, true); }
            @Override public void openCall() { VoiceSnapshotView v = voice(); if (v != null && v.callId() != null) go(Route.of(Route.Kind.CALL, v.callId())); }
            @Override public void attach() { attachSheet(peer, t); }
            @Override public void send(String text) { action(() -> engine.sendText(peer, text, ttl), id -> { drafts.remove(peer); refresh(); syncNow(); }); }
            @Override public void draft(String text) { drafts.put(peer, text); }
            @Override public void message(MessageItem item) { exportFile(byId.get(item.id())); }
            @Override public void stopLocation() { stopLocationSharing(); }
            @Override public void retry() { syncNow(); }
        }));
    }
    private static String precisionLabel(String mode) {
        try { return LocationShareDraft.Precision.valueOf(mode).label; } catch (Exception unknown) { return "Ubicación"; }
    }
    private void exportFile(JSONObject message) {
        if (message == null) return;
        confirm("Exportar archivo", "La copia exportada ya no estará protegida por la bóveda ni por el vencimiento del mensaje.", "Exportar", false, () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, message.optString("name").replaceAll("[\\p{Cntrl}/\\\\]", "_"));
            action(() -> engine.stageExport(Bytes.unb64(message.optString("data"))), key -> { pendingExportKey = key; external(intent, EXPORT_FILE); });
        });
    }
    private void attachSheet(String peer, TrustPresentation t) {
        Dialog[] sheet = new Dialog[1];
        sheet[0] = SecureDialogs.sheet(this, this::track, ChatScreens.attachSheet(ui, features, t.allowsLocation(), new ChatScreens.AttachActions() {
            @Override public void file() {
                sheet[0].dismiss(); pendingAttachmentPeer = peer; pendingAttachmentTtl = ttl;
                external(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_FILE);
            }
            @Override public void photo() { notice(ErrorPresentation.of(ErrorKind.FEATURE_PENDING).body()); }
            @Override public void location() { sheet[0].dismiss(); locationSheet(peer); }
        }));
    }
    private void stopLocationSharing() {
        var capture=locationCapture; String session=capture==null?null:capture.activeSession();
        if(session!=null) engine.locations().cancelCapture(session);
        if(capture!=null) capture.close();
        if(session!=null) action(() -> { engine.locations().stop(session); return true; },ok -> { locationStatus="Ubicación detenida"; locationCapture=null; refresh(); syncNow(); });
        else { engine.locations().cancelLocal(); action(() -> { engine.expire(); return true; },ok -> { locationStatus="Entregas de ubicación canceladas"; refresh(); }); }
    }
    private void setBlocked(String peer, boolean block) {
        Runnable apply = () -> action(() -> { engine.block(peer, block); return true; }, ok -> {
            BluetoothLink active = bluetooth;
            if (active != null && peer.equals(active.connectedPeer())) { active.close(); bluetooth = null; }
            cancelRelay(); refresh();
        });
        if (block) confirm("Bloquear contacto", "No recibirás ni enviarás mensajes, ubicación ni llamadas con " + aliasFor(peer) + ". Los mensajes en cola hacia este contacto se descartan.", "Bloquear", true, apply);
        else apply.run();
    }

    // ------------------------------------------------------------------ contact & verification
    private void renderContact(String peer) {
        JSONObject contact = uiContact(peer); if (contact == null) { nav.back(); render(); return; }
        int files = 0; if (peer.equals(loadedPeer)) for (JSONObject m : messages) if ("file".equals(m.optString("kind"))) files++;
        mount(SecurityScreens.contact(ui, new SecurityScreens.ContactState(peer, alias(contact), trustOf(peer), contactDevices.getOrDefault(peer, -1), files,
            features.visible(Feature.VOICE_CALLS), features), new SecurityScreens.ContactActions() {
            @Override public void back() { MainActivity.this.back(); }
            @Override public void verify() { verifyMethod = SecurityScreens.Method.CODE; go(Route.of(Route.Kind.VERIFY, peer)); }
            @Override public void block(boolean block) { setBlocked(peer, block); }
            @Override public void clear() { confirm("Vaciar conversación", "Borra el historial local y la cola pendiente. No borra las copias del otro teléfono ni revoca lo ya enviado al servidor.", "Vaciar", true,
                () -> action(() -> { engine.clearConversation(peer); return true; }, ok -> refresh())); }
            @Override public void call() { startCall(peer, false); }
            @Override public void message() {
                Route chat = Route.of(Route.Kind.CHAT, peer);
                if (chat.equals(nav.previous())) nav.back(); else nav.replace(chat);
                refresh();
            }
        }));
    }
    private Bitmap qr(String peer) {
        return qrCache.computeIfAbsent(peer, p -> {
            try {
                BitMatrix matrix = new MultiFormatWriter().encode(app.umbra.verification.Verification.qr(profile.optString("id"), p), BarcodeFormat.QR_CODE, 320, 320);
                Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
                int[] pixels = new int[320 * 320];
                for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) pixels[y * 320 + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                bitmap.setPixels(pixels, 0, 320, 0, 0, 320, 320);
                return bitmap;
            } catch (Exception unavailable) { return null; } // The complete textual code is the primary path.
        });
    }
    private void renderVerify(String peer) {
        JSONObject contact = uiContact(peer); if (contact == null) { nav.back(); render(); return; }
        String code;
        try { code = Bytes.safetyCode(profile.optString("id"), peer); } catch (Exception invalid) { notice(safeError(invalid)); nav.back(); render(); return; }
        mount(SecurityScreens.verify(ui, new SecurityScreens.VerifyState(alias(contact), trustOf(peer), code, verifyMethod == SecurityScreens.Method.QR ? qr(peer) : null,
            verifyMethod, verifyTechnical, Fingerprints.shortId(profile.optString("id")), Fingerprints.shortId(peer), features), new SecurityScreens.VerifyActions() {
            @Override public void back() { verifyTechnical = false; MainActivity.this.back(); }
            @Override public void method(SecurityScreens.Method m) { verifyMethod = m; render(); }
            @Override public void compare(String typed) {
                action(() -> { engine.verify(peer, typed); return true; }, ok -> { notice("Contacto verificado. El código coincide."); verifyTechnical = false; nav.back(); refresh(); syncNow(); });
            }
            @Override public void technical(boolean show) { verifyTechnical = show; render(); }
        }));
    }

    // ------------------------------------------------------------------ new chat / new group
    private List<ChatScreens.Contact> pickable() {
        List<ChatScreens.Contact> list = new ArrayList<>();
        for (JSONObject c : contacts) list.add(new ChatScreens.Contact(c.optString("id"), alias(c), trust.getOrDefault(c.optString("id"), TrustLevel.UNVERIFIED)));
        return list;
    }
    private void renderNewChat() {
        mount(ChatScreens.newMessage(ui, pickable(), new ChatScreens.PickActions() {
            @Override public void back() { MainActivity.this.back(); }
            @Override public void pick(ChatScreens.Contact c) { nav.replace(Route.of(Route.Kind.CHAT, c.id())); refresh(); }
            @Override public void addContact() { addContactSheet(); }
        }));
    }
    private void renderNewGroup() {
        mount(ChatScreens.newGroup(ui, new ChatScreens.NewGroupState(groupStep, pickable(), groupSelection, groupName, features), new ChatScreens.NewGroupActions() {
            @Override public void back() { if (groupStep > 0) { groupStep--; render(); } else { groupSelection.clear(); groupName = ""; MainActivity.this.back(); } }
            @Override public void toggle(String id) { if (!groupSelection.remove(id)) groupSelection.add(id); render(); }
            @Override public void step(int step) { groupStep = step; render(); }
            @Override public void name(String name) { groupName = name; }
            @Override public void create() { notice(ErrorPresentation.of(ErrorKind.FEATURE_PENDING).body() + " No se creó ningún grupo."); }
        }));
    }

    // ------------------------------------------------------------------ devices
    private void renderDevices() {
        DeviceScreens.DevicesState s = devicesState;
        if (s == null) { mount(Screen.of(ui.topBar(this::back, ui.titleBlock("Tus dispositivos", null)), ui.skeleton(3), null)); return; }
        mount(DeviceScreens.devices(ui, s, new DeviceScreens.DevicesActions() {
            @Override public void back() { MainActivity.this.back(); }
            @Override public void revoke(DeviceItem d) {
                if (!features.available(Feature.DEVICE_REVOCATION)) { notice(ErrorPresentation.of(ErrorKind.FEATURE_PENDING).body()); return; }
                confirm("Revocar " + d.title(), "El dispositivo dejará de estar autorizado para tu identidad. Es definitivo.", "Revocar", true,
                    () -> action(() -> new app.umbra.devices.DeviceService(vault).revoke(d.id()), ok -> refresh()));
            }
            @Override public void add() { notice(ErrorPresentation.of(ErrorKind.FEATURE_PENDING).body()); }
        }));
    }

    // ------------------------------------------------------------------ settings
    private void renderSettings(SettingsSection section) {
        int expiryIndex = ttl == 3600 ? 0 : ttl == 604800 ? 2 : 1;
        mount(SettingsScreens.section(ui, new SettingsScreens.SettingsState(section, profile.optString("alias"), profile.optString("id"), features,
            !BuildConfig.ALLOW_RELAY, connectivity(), profile.optBoolean("registered"), profile.optString("relay"), ttlName(), expiryIndex, BuildConfig.VERSION_NAME,
            passwordConfigured, PasswordPolicy.AUTO_LOCK_LABELS[autoLockIndex], admissionPresentation()), new SettingsScreens.SettingsActions() {
            @Override public void back() { MainActivity.this.back(); }
            @Override public void createInvitation() { MainActivity.this.createInvitation(); }
            @Override public void importInvitation() { pickContact(); }
            @Override public void revokeInvitations() { confirm("Revocar invitaciones", "Los archivos ya compartidos dejarán de permitir nuevos vínculos. Los contactos existentes no se eliminan.", "Revocar", true,
                () -> action(() -> new PairingService(vault).revokeUnused(), count -> notice("Invitaciones revocadas: " + count))); }
            @Override public void lockNow() { lock(); }
            @Override public void destroyIdentity() {
                confirm("Acción irreversible", "Se destruirá la clave de esta identidad. No existe recuperación. Esta acción no borra copias externas ni asegura borrado físico de la memoria flash.", "Destruir", true, () -> {
                    BluetoothLink b = bluetooth; if (b != null) b.close(); bluetooth = null;
                    action(() -> { vault.close(); Vault.destroyKey(); deleteDatabase("umbra.db"); vault = null; engine = null; initialised = false; return true; }, ok -> { lock(); authenticate(); });
                });
            }
            @Override public void expiry(int index) { ttl = new long[]{3600, 86400, 604800}[index]; render(); }
            @Override public void register(String address, String invite) {
                if (!BuildConfig.ALLOW_RELAY) return;
                action(() -> {
                    String base = RelayClient.validate(address);
                    // Explicit consent for this origin, requested by the person's "Conectar y registrar" action.
                    var conn = engine.connectivity();
                    boolean sameOrigin = false;
                    if (conn.isNetworkSessionAllowed()) try { conn.networkLease(base); sameOrigin = true; } catch (SecurityException otherOrigin) { sameOrigin = false; }
                    // The person asked to register at this address: end any session for another origin, then consent to this one.
                    if (!sameOrigin) { if (conn.isNetworkSessionAllowed()) conn.disconnect(); AndroidConnectivity.connect(MainActivity.this, conn, base, true); }
                    try (RelayClient relay = openRelay(base, generation, false)) { relay.register(engine.profile(), invite); }
                    engine.updateRelay(base, true); return true;
                }, ok -> { networkPaused = !engine.connectivity().isNetworkSessionAllowed(); notice("Buzón registrado."); refresh(); syncNow(); },
                    failure -> { notice("No se registró el buzón. Revisa la dirección, la invitación y que este dispositivo esté admitido."); refresh(); });
            }
            @Override public void syncNow() { MainActivity.this.syncNow(); }
            @Override public void unregister() {
                confirm("Eliminar buzón remoto", "Se eliminan los mensajes pendientes de ese buzón. La identidad y el historial local permanecen.", "Eliminar", true, () -> action(() -> {
                    JSONObject me = engine.profile();
                    try (RelayClient relay = openRelay(me.getString("relay"), generation, false)) { relay.unregister(me); }
                    engine.updateRelay(me.getString("relay"), false); return true;
                }, ok -> refresh()));
            }
            @Override public void connect() { connectNetwork(); }
            @Override public void disconnect() { disconnectNetwork(); }
            @Override public void nearby() { nav.home(); nav.selectTab(HomeTab.NEARBY); render(); }
            @Override public void changePassword() { changeProblem = null; go(Route.of(Route.Kind.CHANGE_PASSWORD)); }
            @Override public void enrollPassword() {
                confirm("Añadir contraseña personal", "La bóveda se volverá a cifrar en este teléfono y quedará bloqueada. Después necesitarás Android y la contraseña para abrirla. No existe recuperación.",
                    "Continuar", false, () -> { if (unlocked && engine != null && !passwordConfigured) { accessStep = AccessStep.LEGACY_ENROLLMENT; legacyDeferred = false; accessProblem = null; render(); } });
            }
            @Override public void admission() { go(Route.of(Route.Kind.ADMISSION)); refresh(); }
            @Override public void devices() { go(Route.of(Route.Kind.DEVICES)); refresh(); }
        }));
    }

    // ------------------------------------------------------------------ private admission
    private static String when(long epochSeconds) { return android.text.format.DateFormat.format("dd/MM HH:mm", new Date(epochSeconds * 1000)).toString(); }
    private static String fp(String hex) { return hex == null ? null : Fingerprints.lines(hex, 4); }
    private void renderAdmission() {
        AdmissionFlow.Snapshot a = admission;
        if (a == null) { mount(Screen.of(ui.topBar(this::back, ui.titleBlock("Admisión del dispositivo", null)), ui.skeleton(3), null)); return; }
        AdmissionFlow.Request r = a.request();
        AdmissionScreens.RequestInfo request = r == null ? null
            : new AdmissionScreens.RequestInfo(fp(r.deviceFingerprint()), fp(r.identityFingerprint()), r.realmId(), when(r.expiresAt()), r.expired());
        mount(AdmissionScreens.status(ui, new AdmissionScreens.AdmissionState(admissionPresentation(), a.realmId(), fp(a.authorityKeyId()),
            fp(profile.optString("id")), request, a.credentialExpiresAt() == null ? null : when(a.credentialExpiresAt()), !BuildConfig.ALLOW_RELAY, adminBusy),
            new AdmissionScreens.AdmissionActions() {
                @Override public void back() { MainActivity.this.back(); }
                @Override public void importFile() { external(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_ADMISSION); }
                @Override public void createRequest() {
                    if (adminBusy) return; adminBusy = true;
                    action(() -> { engine.admission().createAdmissionRequest(); return true; },
                        ok -> { adminBusy = false; notice("Solicitud generada en este teléfono. Todavía no se envió a nadie: expórtala y compártela."); refresh(); },
                        failure -> { adminBusy = false; notice("No se generó la solicitud. Si hay una vigente, espera a que venza o importa la respuesta."); refresh(); });
                }
                @Override public void exportRequest() {
                    AdmissionFlow.Snapshot current = admission;
                    if (current == null || current.request() == null) return;
                    // A renewal request carries the current public credential so the authority can renew atomically.
                    String text = current.credentialWire() != null ? AdmissionImport.file(current.request().wire(), current.credentialWire()) : AdmissionImport.file(current.request().wire());
                    exportAdmission(text, current.credentialWire() != null ? "umbra-solicitud-renovacion.txt" : "umbra-solicitud-admision.txt");
                }
                @Override public void admin() { go(Route.of(Route.Kind.ADMISSION_ADMIN)); }
            }));
    }
    private void exportAdmission(String text, String name) {
        action(() -> engine.stageExport(Bytes.utf8(text)), key -> {
            pendingExportKey = key;
            external(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), EXPORT_ADMISSION);
        });
    }
    private void renderAdmin() {
        AdmissionFlow.Snapshot a = admission;
        boolean configured = a != null && a.realmId() != null;
        mount(AdmissionScreens.admin(ui, new AdmissionScreens.AdminState(configured, a == null ? null : a.realmId(), a == null ? null : fp(a.authorityKeyId()), adminBusy),
            new AdmissionScreens.AdminActions() {
                @Override public void back() { MainActivity.this.back(); }
                @Override public void createRealm() {
                    confirm("Crear entorno", "Este teléfono será la única autoridad del entorno. No hay recuperación ni rotación: si pierdes este teléfono o su bóveda, no podrás admitir más dispositivos.",
                        "Crear y ser autoridad", true, () -> {
                            if (adminBusy) return; adminBusy = true;
                            action(() -> engine.admission().createAdmissionRealm(true),
                                realm -> { adminBusy = false; notice("Entorno creado. Este teléfono es su autoridad, pero aún no está admitido: genera y revisa su propia solicitud."); refresh(); },
                                failure -> { adminBusy = false; notice("No se creó el entorno. Ya existe una configuración de admisión en este teléfono."); refresh(); });
                        });
                }
                @Override public void exportRealm() {
                    action(() -> AdmissionImport.file(engine.admission().getRealmInfo().encode()), text -> exportAdmission(text, "umbra-entorno.txt"));
                }
                @Override public void reviewRequest() {
                    AdmissionFlow.Snapshot own = admission;
                    if (own != null && own.request() != null && !own.request().expired() && own.credentialWire() == null) {
                        new SecureDialogBuilder().setTitle("Solicitud a revisar")
                            .setItems(new String[]{"La de este teléfono", "Otra, desde un archivo"}, (d, which) -> {
                                if (which == 0) reviewWire(AdmissionImport.classify(AdmissionImport.file(own.request().wire())), true);
                                else external(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_ADMIN_REVIEW);
                            }).setNegativeButton("Cancelar", null).show();
                    } else external(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_ADMIN_REVIEW);
                }
                @Override public void revokeCredential() { external(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_ADMIN_REVOKE); }
            }));
    }
    private static final String REVIEW_REJECTED = "No se puede revisar: la solicitud venció, ya fue decidida, pertenece a otro entorno o este teléfono no es la autoridad.";
    /** Authority review: the domain validates signature, realm, expiry, consumption and authority before showing anything. */
    private void reviewWire(AdmissionImport.Parsed parsed, boolean own) {
        if (parsed.kind() != AdmissionImport.Kind.REQUEST && parsed.kind() != AdmissionImport.Kind.RENEWAL_REQUEST) { notice(AdmissionPresentation.IMPORT_REJECTED); return; }
        action(() -> {
            AdmissionService service = engine.admission();
            AdmissionService.Review review = service.reviewAdmissionRequest(parsed.parts().get(0));
            String old = parsed.kind() == AdmissionImport.Kind.RENEWAL_REQUEST ? parsed.parts().get(1) : null;
            return new PendingReview(review, old, new AdmissionScreens.ReviewInfo(fp(review.deviceFingerprint()), fp(review.identityFingerprint()),
                review.realmId(), when(review.expiresAt()), old != null), own);
        }, holder -> { pendingReview = holder; showReviewSheet(holder); }, failure -> notice(REVIEW_REJECTED));
    }
    private void showReviewSheet(PendingReview holder) {
        Dialog[] sheet = new Dialog[1];
        sheet[0] = SecureDialogs.sheet(this, this::track, AdmissionScreens.reviewSheet(ui, holder.info(), new AdmissionScreens.ReviewActions() {
            @Override public void approve(long ttl) {
                sheet[0].dismiss();
                confirm(holder.info().renewal() ? "Aprobar renovación" : "Aprobar admisión",
                    "¿Comparaste las huellas con la persona por un canal confiable? Se firmará una credencial de " + (ttl >= 604_800 ? "7 días" : "24 horas") + ". Estar admitido no verifica a ningún contacto.",
                    "Aprobar", false, () -> decide(holder, true, ttl));
            }
            @Override public void reject() {
                sheet[0].dismiss();
                confirm("Rechazar solicitud", "Se firmará un rechazo para esta solicitud. El dispositivo no quedará admitido.", "Rechazar", true, () -> decide(holder, false, 0));
            }
            @Override public void cancel() { pendingReview = null; sheet[0].dismiss(); }
        }));
    }
    private record Decision(String exportText, String fileName, String notice) {}
    private void decide(PendingReview holder, boolean approve, long ttl) {
        if (adminBusy || pendingReview != holder) return;
        adminBusy = true; pendingReview = null;
        action(() -> {
            AdmissionService service = engine.admission();
            if (!approve) return new Decision(AdmissionImport.file(service.rejectAdmission(holder.review(), true).wire()), "umbra-rechazo.txt",
                "Rechazo firmado. Entrégalo al dispositivo para que lo importe.");
            if (holder.oldCredential() != null) {
                AdmissionService.Renewal renewal = service.renewAdmission(holder.review(), holder.oldCredential(), true, ttl);
                return new Decision(AdmissionImport.file(renewal.credential().wire(), renewal.revocation().wire()), "umbra-renovacion.txt",
                    "Renovación firmada: credencial nueva y revocación de la anterior. Entrégala al dispositivo; los demás teléfonos conocen la revocación solo al recibirla.");
            }
            if (holder.own()) {
                // Approve and install in one vault transaction: either this phone is admitted or nothing changed.
                vault.transaction(() -> { service.installAdmissionCredential(service.approveAdmission(holder.review(), true, ttl).wire()); return null; });
                return new Decision(null, null, "Este teléfono quedó admitido con la credencial que acabas de firmar.");
            }
            var credential = service.approveAdmission(holder.review(), true, ttl);
            return new Decision(AdmissionImport.file(credential.wire()), "umbra-credencial.txt", "Credencial firmada. Entrégala al dispositivo para que la importe.");
        }, decision -> {
            adminBusy = false; notice(decision.notice()); refresh();
            if (decision.exportText() != null) exportAdmission(decision.exportText(), decision.fileName());
        }, failure -> { adminBusy = false; notice("No se firmó la decisión. La solicitud pudo vencer o ya estaba decidida; no se cambió nada."); refresh(); });
    }
    private void revokeWire(AdmissionImport.Parsed parsed) {
        if (parsed.kind() != AdmissionImport.Kind.CREDENTIAL) { notice(AdmissionPresentation.IMPORT_REJECTED); return; }
        String wire = parsed.parts().get(0);
        action(() -> AdmissionFlow.describeCredential(engine.admission(), wire), info -> {
            Dialog[] sheet = new Dialog[1];
            sheet[0] = SecureDialogs.sheet(this, this::track, AdmissionScreens.revokeSheet(ui, new AdmissionScreens.RevokeInfo(fp(info.identityFingerprint()), when(info.expiresAt())),
                new AdmissionScreens.RevokeActions() {
                    @Override public void revoke(String reason) {
                        sheet[0].dismiss();
                        confirm("Revocar credencial", "Se firmará una revocación. No borra datos de ese dispositivo y los teléfonos sin conexión solo la conocerán al recibir el archivo.", "Revocar", true, () -> {
                            if (adminBusy) return; adminBusy = true;
                            action(() -> AdmissionImport.file(engine.admission().revokeAdmission(wire, true, reason).wire()),
                                text -> { adminBusy = false; notice("Revocación firmada. Distribúyela a los demás teléfonos del entorno."); refresh(); exportAdmission(text, "umbra-revocacion.txt"); },
                                failure -> { adminBusy = false; notice("No se firmó la revocación: la credencial no pertenece a este entorno, ya estaba revocada o este teléfono no es la autoridad."); });
                        });
                    }
                    @Override public void cancel() { sheet[0].dismiss(); }
                }));
        }, failure -> notice(AdmissionPresentation.IMPORT_REJECTED));
    }
    /** Member import: the person confirms after seeing what the file claims to be; the domain decides. */
    private record ImportPreview(AdmissionImport.Parsed parsed, String detail) {}
    private void importAdmission(Uri uri) {
        action(() -> {
            byte[] bytes = readBounded(uri, AdmissionImport.MAX_WIRE * AdmissionImport.MAX_LINES + 2);
            try {
                AdmissionImport.Parsed parsed = AdmissionImport.classify(Bytes.text(bytes));
                String detail = null;
                if (parsed.kind() == AdmissionImport.Kind.REALM) {
                    var realm = app.umbra.admission.RealmConfig.decode(parsed.parts().get(0)); // public parse for display only
                    detail = "Entorno: " + realm.realmId() + "\nHuella de la autoridad: " + Fingerprints.lines(realm.authorityKeyId(), 4);
                }
                return new ImportPreview(parsed, detail);
            } finally { Arrays.fill(bytes, (byte) 0); }
        }, preview -> {
            AdmissionImport.Kind kind = preview.parsed().kind();
            boolean member = kind == AdmissionImport.Kind.REALM || kind == AdmissionImport.Kind.CREDENTIAL || kind == AdmissionImport.Kind.REJECTION
                || kind == AdmissionImport.Kind.REVOCATION || kind == AdmissionImport.Kind.RENEWAL_RESULT;
            if (!member) { notice(kind == AdmissionImport.Kind.REQUEST || kind == AdmissionImport.Kind.RENEWAL_REQUEST
                ? "Es una solicitud de otro dispositivo: revísala en Herramientas del administrador." : AdmissionPresentation.IMPORT_REJECTED); return; }
            String body = kind == AdmissionImport.Kind.REALM
                ? preview.detail() + "\n\nCompara la huella con la que te dio el administrador. Configurar el entorno no admite este dispositivo."
                : "UMBRA comprobará la firma y que corresponda a este dispositivo antes de aplicarlo.";
            confirm("Importar: " + AdmissionImport.describe(kind), body, "Importar", false, () -> action(() -> {
                AdmissionFlow.applyMember(vault, engine.admission(), preview.parsed(), true);
                return AdmissionFlow.read(engine.admission(), Bytes.now());
            }, after -> {
                admission = after;
                notice(kind == AdmissionImport.Kind.REALM ? "Entorno UMBRA configurado. Este dispositivo todavía no está admitido." : admissionPresentation().title());
                refresh();
            }, failure -> notice(kind == AdmissionImport.Kind.REALM ? AdmissionPresentation.REALM_IMPORT_REJECTED : AdmissionPresentation.IMPORT_REJECTED)));
        }, failure -> notice(AdmissionPresentation.IMPORT_REJECTED));
    }
    /** Admin file picks: read the bounded file, then hand it to the domain-validated review or revocation flow. */
    private void importAdmin(Uri uri, boolean review) {
        action(() -> {
            byte[] bytes = readBounded(uri, AdmissionImport.MAX_WIRE * AdmissionImport.MAX_LINES + 2);
            try { return AdmissionImport.classify(Bytes.text(bytes)); } finally { Arrays.fill(bytes, (byte) 0); }
        }, parsed -> { if (review) reviewWire(parsed, false); else revokeWire(parsed); }, failure -> notice(AdmissionPresentation.IMPORT_REJECTED));
    }

    // ------------------------------------------------------------------ calls
    private static String callId(JSONObject session) { JSONObject c = session.optJSONObject("context"); return c == null ? "" : c.optString("callId"); }
    private String otherParty(JSONObject session) {
        JSONObject c = session.optJSONObject("context"); if (c == null || profile == null) return null;
        String me = profile.optString("id");
        return me.equals(c.optString("caller")) ? c.optString("callee") : c.optString("caller");
    }
    private JSONObject session(String id) { for (JSONObject s : callSessions) if (id.equals(callId(s))) return s; return null; }
    private String sessionState(String id) { JSONObject s = session(id); return s == null ? null : s.optString("state"); }
    /** Flavor-neutral view of the connected VoiceControls snapshot. */
    private record VoiceSnapshotView(String callId, String state, String modulation, boolean muted, String video, long lastCapture, long closed) {}
    private VoiceSnapshotView voice() {
        var s = voiceControls.snapshot();
        return s == null ? null : new VoiceSnapshotView(s.callId(), s.state(), s.modulationStatus(), s.muted(), s.videoStatus(), s.lastCaptureNanos(), s.closedNanos());
    }
    private List<HomeScreens.CallRow> callRows() {
        List<HomeScreens.CallRow> rows = new ArrayList<>(); VoiceSnapshotView v = voice();
        for (JSONObject s : callSessions) {
            String id = callId(s); JSONObject c = s.optJSONObject("context");
            String media = v != null && id.equals(v.callId()) ? v.state() : null;
            rows.add(new HomeScreens.CallRow(id, aliasFor(otherParty(s)), CallPresentation.of(s.optString("state"), media), c == null ? null : time(c.optLong("created")), videoIntent.contains(id)));
        }
        Collections.reverse(rows);
        return rows;
    }
    /** Starts signaling only; microphone and camera each need their own later consent. */
    private void startCall(String peer, boolean video) {
        if (!features.available(Feature.VOICE_CALLS) || !unlocked) { notice(ErrorPresentation.of(ErrorKind.OFFLINE_EDITION).body()); return; }
        if (!trustOf(peer).allowsCalls()) { notice(trustOf(peer).blockedReason()); return; }
        action(() -> {
            JSONObject index=engine.get("device-index",peer);
            if(index==null) throw new SecurityException("Primero aprueba la lista de dispositivos del contacto");
            return engine.calls().reviewInvite(index.getString("root"),app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY);
        }, consent -> confirm(video ? "Iniciar videollamada" : "Iniciar llamada",
            "Se avisará a " + aliasFor(peer) + ". Todavía no se enciende el micrófono" + (video ? " ni la cámara" : "") + ": cada uno requiere tu confirmación. El audio usa tu retransmisor autorizado.",
            "Llamar", false, () -> action(() -> engine.calls().invite(consent,true), id -> { if (video) videoIntent.add(id); syncNow(); if (nav.push(Route.of(Route.Kind.CALL, id))) refresh(); })));
    }
    private void renderIncoming(String id) {
        JSONObject s = session(id);
        if (s == null || !"INCOMING".equals(s.optString("state"))) { nav.back(); render(); return; }
        String peer = otherParty(s);
        mount(CallScreens.incoming(ui, new CallScreens.IncomingState(id, aliasFor(peer), trustOf(peer), false), new CallScreens.IncomingActions() {
            @Override public void reject() { endCall(id); nav.back(); render(); }
            @Override public void answer() {
                action(() -> engine.calls().reviewAccept(id,app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY),
                    consent -> confirm("Responder a " + aliasFor(peer), "Se confirma la llamada con este dispositivo verificado. El micrófono se autoriza en el siguiente paso.", "Responder", false,
                        () -> action(() -> { engine.calls().accept(consent,true); return true; }, ok -> { nav.replace(Route.of(Route.Kind.CALL, id)); syncNow(); refresh(); })));
            }
            @Override public void later() { MainActivity.this.back(); }
        }));
    }
    private void endCall(String id) {
        VoiceSnapshotView v = voice();
        if (v != null && id.equals(v.callId())) voiceControls.close();
        engine.calls().cancelPending(id);
        action(() -> { engine.calls().end(id); return true; }, ok -> { syncNow(); refresh(); });
    }
    private void renderCall(String id) {
        JSONObject s = session(id);
        VoiceSnapshotView v = voice(); boolean media = v != null && id.equals(v.callId());
        if (s == null && !media) { nav.back(); render(); return; }
        String peer = s == null ? null : otherParty(s);
        CallPresentation call = CallPresentation.of(s == null ? null : s.optString("state"), media ? v.state() : null);
        if (media && "ACTIVE".equals(v.state())) { if (!id.equals(activeSinceCall)) { activeSinceCall = id; activeSince = SystemClock.elapsedRealtime(); } }
        else if (id.equals(activeSinceCall) && call.terminal()) activeSinceCall = null;
        String duration = id.equals(activeSinceCall) ? CallPresentation.duration((SystemClock.elapsedRealtime() - activeSince) / 1000) : null;
        ModulatorPresentation mod = media ? ModulatorPresentation.of(v.modulation(), v.muted()) : null;
        VideoPresentation video = media && (videoIntent.contains(id) || !"OFF".equals(v.video())) ? VideoPresentation.of(v.video(), SystemClock.elapsedRealtimeNanos(), v.lastCapture(), v.closed()) : null;
        JSONObject row = s == null ? null : s.optJSONObject("video");
        boolean request = media && row != null && "REVIEW".equals(row.optString("state"));
        boolean videoMode = videoIntent.contains(id) || (media && !"OFF".equals(v.video()));
        mount(CallScreens.call(ui, new CallScreens.CallState(id, aliasFor(peer), call, duration, media, media && v.muted(), mod, modulatorOpen || (mod != null && mod.state() == ModulatorPresentation.EngineState.ERROR_MUTED),
            video, request, videoMode, features), callActions(id), callHandles));
        main.removeCallbacks(durationTick); main.post(durationTick);
    }
    private CallScreens.CallActions callActions(String id) {
        return new CallScreens.CallActions() {
            @Override public void minimize() { MainActivity.this.back(); }
            @Override public void authorizeMicrophone() {
                try { voiceControls.show(MainActivity.this, engine, id, worker, MainActivity.this::track, MainActivity.this::refresh); }
                catch (Exception e) { notice(safeError(e)); }
            }
            @Override public void mute(boolean value) { mediaCall(() -> voiceControls.setMuted(value)); }
            @Override public void audioOutput() { mediaCall(() -> voiceControls.chooseAudioOutput(MainActivity.this, MainActivity.this::track)); }
            @Override public void toggleModulator() { modulatorOpen = !modulatorOpen; render(); }
            @Override public void modulated() { mediaCall(voiceControls::requestModulation); }
            @Override public void retryModulation() { mediaCall(voiceControls::requestModulation); }
            @Override public void natural() {
                long reviewed = voiceControls.modeEpoch();
                confirm(ModulatorPresentation.NATURAL_CONFIRM_TITLE, ModulatorPresentation.NATURAL_CONFIRM_BODY, ModulatorPresentation.NATURAL_CONFIRM_ACTION, false,
                    () -> mediaCall(() -> voiceControls.useNaturalVoice(reviewed)));
            }
            @Override public void video() {
                videoIntent.add(id);
                new SecureDialogBuilder().setTitle("Solicitar video a " + aliasFor(otherPartyOf(id)))
                    .setItems(new String[]{"Solo recibir su video", "Enviar mi cámara y recibir"}, (d, which) -> voiceControls.answerVideo(MainActivity.this, worker, false, which, MainActivity.this::refresh))
                    .setNegativeButton("Cancelar", null).show();
            }
            @Override public void stopVideo() { mediaCall(voiceControls::stopVideo); videoIntent.remove(id); }
            @Override public void switchCamera() { mediaCall(() -> voiceControls.switchCamera(MainActivity.this, worker)); }
            @Override public void showRemoteVideo() { mediaCall(() -> voiceControls.showRemoteVideo(MainActivity.this, MainActivity.this::track)); }
            @Override public void answerVideo(int choice) { voiceControls.answerVideo(MainActivity.this, worker, true, choice, MainActivity.this::refresh); }
            @Override public void hangUp() {
                if (CallPresentation.of(sessionState(id), null).terminal() && voice() == null) { MainActivity.this.back(); return; }
                confirm("Colgar", "Se detienen el audio y el video de esta llamada.", "Colgar", true, () -> { endCall(id); videoIntent.remove(id); modulatorOpen = false; });
            }
        };
    }
    private String otherPartyOf(String callId) { JSONObject s = session(callId); return s == null ? null : otherParty(s); }
    private interface MediaOperation { void run() throws Exception; }
    private void mediaCall(MediaOperation operation) {
        try { operation.run(); render(); }
        catch (Exception e) { notice("No se pudo aplicar el cambio. El estado mostrado es el que informa el motor."); render(); }
    }

    // ------------------------------------------------------------------ location
    private void locationSheet(String peer) {
        locationSheetContent = ui.column();
        Dialog sheet = SecureDialogs.sheet(this, this::track, locationSheetContent);
        fillLocationSheet(peer, sheet);
    }
    private void fillLocationSheet(String peer, Dialog sheet) {
        if (locationSheetContent == null) return;
        if (locationLive && locationPrecision == LocationShareDraft.Precision.MANUAL) locationPrecision = LocationShareDraft.Precision.APPROXIMATE;
        locationSheetContent.removeAllViews();
        locationSheetContent.addView(DeviceScreens.locationSheet(ui, new DeviceScreens.LocationSheetState(aliasFor(peer), locationLive, locationPrecision, locationDuration), new DeviceScreens.LocationActions() {
            @Override public void live(boolean live) { locationLive = live; fillLocationSheet(peer, sheet); }
            @Override public void precision(LocationShareDraft.Precision p) { locationPrecision = p; fillLocationSheet(peer, sheet); }
            @Override public void duration(int index) { locationDuration = index; fillLocationSheet(peer, sheet); }
            @Override public void stopAll() { sheet.dismiss(); stopLocationSharing(); }
            @Override public void review(String lat, String lon) {
                LocationShareDraft.Precision p = locationPrecision;
                var mode = app.umbra.location.LocationPayload.Mode.valueOf(p.engineMode());
                if (p == LocationShareDraft.Precision.MANUAL) {
                    try { double la = Double.parseDouble(lat.replace(',', '.')), lo = Double.parseDouble(lon.replace(',', '.')); sheet.dismiss(); reviewLocation(peer, mode, LocationShareDraft.SINGLE_POINT_SECONDS, false, la, lo); }
                    catch (Exception invalid) { notice("Coordenadas inválidas"); }
                    return;
                }
                long duration = locationLive ? LocationShareDraft.LIVE_DURATIONS[locationDuration] : LocationShareDraft.SINGLE_POINT_SECONDS;
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (!fine && (!coarse || p == LocationShareDraft.Precision.PRECISE)) {
                    sheet.dismiss(); externalUi = true;
                    requestPermissions(p == LocationShareDraft.Precision.PRECISE ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION} : new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 302);
                    return; // Permission result never starts capture. Require a new action/consent.
                }
                sheet.dismiss(); reviewLocation(peer, mode, duration, locationLive, 0, 0);
            }
        }));
    }
    private void reviewLocation(String peer, app.umbra.location.LocationPayload.Mode mode,long duration,boolean live,double lat,double lon) {
        action(() -> {
            JSONObject index=engine.get("device-index",peer);
            if(index==null) throw new SecurityException("Primero aprueba la lista autenticada de dispositivos del contacto");
            return engine.locations().review(index.getString("root"),mode,duration,live);
        },consent -> {
            LocationShareDraft draft = new LocationShareDraft(LocationShareDraft.Precision.valueOf(mode.name()), live, duration);
            StringBuilder text = new StringBuilder();
            for (String[] line : draft.summary(aliasFor(peer), consent.devices().size())) text.append(line[0]).append(": ").append(line[1]).append("\n");
            text.append("Dispositivos: "); for (String d : consent.devices()) text.append(Fingerprints.shortId(d)).append(' ');
            text.append("\nSolo este teléfono captura. Bloquear o salir la detiene; no se reanuda sola.");
            confirm("Confirmar ubicación", text.toString(), live ? "Compartir en vivo" : "Compartir", false, () -> action(() -> {
                if(mode==app.umbra.location.LocationPayload.Mode.MANUAL) return engine.locations().manual(consent,true,lat,lon);
                if(locationCapture!=null && locationCapture.activeSession()!=null) throw new SecurityException("Detén primero la ubicación actual");
                String session=engine.locations().start(consent,true);
                locationCapture=new app.umbra.location.AndroidLocationCapture(this,worker,() -> resumed && unlocked && !destroyed,engine.locations(),status -> main.post(() -> { if(unlocked) { locationStatus=status; refresh(); syncNow(); } }));
                locationCapture.start(session,mode,live); return session;
            },session -> { locationStatus = (live ? "En vivo · " : "Un punto · ") + draft.precision().label + " · hasta " + LocationShareDraft.durationLabel(duration); refresh(); syncNow(); }));
        });
    }

    // ================================================================== pairing & documents
    private void exportPairing(String key) {
        pendingExportKey = key;
        external(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "umbra-vinculacion.txt"), EXPORT_CONTACT);
    }
    private record PairingResult(String peer, String exportKey) {}
    private PairingResult importPairing(Uri uri) throws Exception {
        byte[] bytes = readBounded(uri, 24000);
        try {
            String value = Bytes.text(bytes); PairingService pairing = new PairingService(vault);
            if (value.startsWith("umbra:invite:")) return new PairingResult(null, engine.stageExport(Bytes.utf8(pairing.request(value))));
            if (value.startsWith("umbra:request:")) return new PairingResult(null, engine.stageExport(Bytes.utf8(pairing.accept(value))));
            if (value.startsWith("umbra:ack:")) return new PairingResult(pairing.complete(value), null);
            // Existing signed cards remain an explicit manual enrollment path, never one-use invitations.
            return new PairingResult(engine.importCard(Wire.parse(bytes, 16000)), null);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private void pickContact() { external(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_CONTACT); }
    private void external(Intent intent, int requestCode) {
        try { externalUi = true; startActivityForResult(intent, requestCode); }
        catch (Exception e) { externalUi = false; notice("No hay una aplicación del sistema disponible para esta acción."); }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); externalUi = false;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == EXPORT_CONTACT || request == EXPORT_FILE || request == EXPORT_ADMISSION) pendingExportKey = null;
            return;
        }
        pendingResult = new PendingResult(request, data.getData());
        if (unlocked) resumeExternalResult(); // Otherwise authentication onResume resumes the explicitly requested action.
    }
    private void resumeExternalResult() {
        PendingResult result = pendingResult; if (result == null || !unlocked || engine == null) return;
        pendingResult = null; Uri uri = result.uri(); int request = result.request();
        if (!"content".equals(uri.getScheme())) { notice("Elige un documento mediante el selector de Android."); return; }
        if (request == PICK_CONTACT) confirm("Procesar vinculación", "Solo continúa si solicitaste este intercambio. La posesión del archivo no verifica a la persona. Una solicitud válida consume la invitación y crea un contacto sin verificar.", "Procesar", false, () -> action(() -> importPairing(uri), processed -> {
            if (processed.exportKey() != null) exportPairing(processed.exportKey());
            else { verifyMethod = SecurityScreens.Method.CODE; nav.push(Route.of(Route.Kind.VERIFY, processed.peer())); refresh(); }
        }));
        else if (request == PICK_ADMISSION) importAdmission(uri);
        else if (request == PICK_ADMIN_REVIEW || request == PICK_ADMIN_REVOKE) importAdmin(uri, request == PICK_ADMIN_REVIEW);
        else if (request == PICK_FILE) {
            String recipient = pendingAttachmentPeer; long lifetime = pendingAttachmentTtl;
            action(() -> {
                byte[] bytes = readBounded(uri, Engine.MAX_ATTACHMENT);
                try { return engine.sendFile(recipient, displayName(uri), bytes, lifetime); }
                finally { Arrays.fill(bytes, (byte) 0); }
            }, id -> { refresh(); syncNow(); });
        } else if (request == EXPORT_CONTACT || request == EXPORT_FILE || request == EXPORT_ADMISSION) {
            String key = pendingExportKey; pendingExportKey = null;
            if (key == null) { notice("La exportación no está disponible. Créala nuevamente."); return; }
            action(() -> {
                AccessGate.Lease lease = gate.enter();
                byte[] content = engine.exportData(key);
                try {
                    DocumentIO.write(gate, lease, content, () -> getContentResolver().openOutputStream(uri, "wt"));
                    return true;
                } finally {
                    Arrays.fill(content, (byte) 0);
                    // Never clean up with a new authentication epoch. Expiry handles cancelled exports.
                    gate.check(lease); engine.clearExport(key);
                }
            }, ok -> notice(request == EXPORT_ADMISSION ? "Archivo exportado. Solo contiene datos públicos firmados; entrégalo por un canal confiable."
                : "Documento exportado. La copia externa no está protegida por UMBRA."));
        }
    }
    private byte[] readBounded(Uri uri, int max) throws Exception {
        return DocumentIO.read(gate, max, () -> getContentResolver().openInputStream(uri));
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "archivo.bin";
    }
    // API 33+ uses the platform OnBackInvokedCallback registered in onCreate.
    // Keep this legacy callback for API 31-32; lint does not recognize that dual path.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { back(); }
    private void back() {
        if (!unlocked) { moveTaskToBack(true); return; }
        if (nav.back()) { refresh(); render(); return; }
        if (onRoute(Route.Kind.HOME) && nav.tab() != HomeTab.CHATS) { nav.selectTab(HomeTab.CHATS); refresh(); return; }
        lock(); moveTaskToBack(true);
    }
    private void stopLocationLocally() {
        voiceControls.close();
        var capture=locationCapture; locationCapture=null; if(capture!=null) capture.close();
        if(engine!=null) { engine.locations().cancelLocal(); engine.calls().cancelLocal(); }
        locationStatus="Ubicación interrumpida; requiere nueva autorización";
    }
    private String ttlName() { return ttl == 3600 ? "1 hora" : ttl == 604800 ? "7 días" : "24 horas"; }
    private void notice(String text) { if (!destroyed && text != null) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void track(Dialog dialog) { dialogs.add(dialog); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); }
    private void confirm(String title, String message, String confirmLabel, boolean destructive, Runnable yes) {
        SecureDialogs.confirm(this, this::track, title, message, confirmLabel, destructive, yes);
    }
    private final class SecureDialogBuilder extends AlertDialog.Builder {
        SecureDialogBuilder() { super(MainActivity.this); }
        @Override public AlertDialog show() {
            AlertDialog dialog = super.create();
            SecureDialogs.show(dialog, MainActivity.this::track);
            return dialog;
        }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        return (event.getFlags() & obscured) != 0 || super.dispatchTouchEvent(event);
    }
}
