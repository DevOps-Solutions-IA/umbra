package app.umbra.ui;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import app.umbra.core.Bytes;
import app.umbra.core.AccessGate;
import app.umbra.core.DocumentIO;
import app.umbra.protocol.Wire;
import app.umbra.BuildConfig;
import app.umbra.R;
import app.umbra.crypto.Engine;
import app.umbra.pairing.PairingService;
import app.umbra.data.Vault;
import app.umbra.transport.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Native Android UI. No WebView, advertising SDK, analytics SDK, phone number or address-book access. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(11,18,25), PANEL = Color.rgb(21,32,43), EDGE = Color.rgb(39,55,65);
    private static final int MINT = Color.rgb(118,225,204), TEXT = Color.rgb(237,244,247), MUTED = Color.rgb(148,169,181);
    private static final int AMBER = Color.rgb(242,190,104), RED = Color.rgb(243,136,143);
    private static final int PICK_CONTACT = 201, EXPORT_CONTACT = 202, PICK_FILE = 203, EXPORT_FILE = 204;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean syncBusy = new AtomicBoolean(false);
    private final Map<String,String> drafts = new HashMap<>();
    private final AccessGate gate = new AccessGate();
    private volatile RelayClient activeRelay;
    private final Set<Dialog> dialogs = new HashSet<>();
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
    private boolean authenticating, externalUi, destroyed, initialised, resumed, authenticationGranted;
    private long grantedAt;
    private long authAt;
    private volatile int generation;
    private int tab;
    private String chat, peerDetails, transportStatus = "Sin conexión activa";
    private JSONObject profile;
    private List<JSONObject> contacts = List.of(), messages = List.of(), locations = List.of();
    private LinearLayout root, page;
    private long ttl = 86400;
    private String pendingAttachmentPeer;
    private CancellationSignal authCancellation;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setHideOverlayWindows(true);
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
        action(() -> {
            if (vault == null) vault = new Vault(getApplicationContext(), gate);
            engine = new Engine(vault, SystemClock::elapsedRealtime); initialised = engine.initialized();
            if (initialised) { vault.get("meta", "identity"); engine.expire(); }
            return initialised;
        }, ready -> { if (ready) { refresh(); resumeExternalResult(); } else onboarding(); });
    }
    private void lock() {
        stopLocationLocally(); authenticationGranted = false; gate.lock(); unlocked = false; networkPaused = true; networkStateLoaded = false; generation++; cancelRelay();
        for (Dialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); dialogs.clear(); chat = null; peerDetails = null; drafts.clear(); messages = List.of(); locations = List.of(); contacts = List.of(); profile = null;
        BluetoothLink link = bluetooth; bluetooth = null; if (link != null) link.close();
        transportStatus = "Bloqueado · conexiones pausadas"; showLocked();
    }
    private void authenticate() {
        if (authenticating || destroyed) return;
        KeyguardManager manager = getSystemService(KeyguardManager.class);
        if (manager == null || !manager.isDeviceSecure()) {
            showLocked();
            label(page, "Activa un PIN, contraseña o biometría en Android antes de crear la identidad.", 15, MUTED);
            button(page, "Configurar bloqueo de Android", () -> external(new Intent(Settings.ACTION_SECURITY_SETTINGS), 0), false);
            return;
        }
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
                    authenticating = false; showLocked();
                    notice("No se pudo preparar Android Keystore. No se creará una identidad sin protección."); return;
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
                authenticating = false; grantedAt = SystemClock.elapsedRealtime();
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
    private <T> void action(Callable<T> operation, Consumer<T> success) {
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
                        else notice(safeError(e));
                    }
                });
            }
        });
    }
    private static boolean hasVaultFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof AccessGate.LockedException || t instanceof android.security.keystore.UserNotAuthenticatedException || t instanceof android.security.keystore.KeyPermanentlyInvalidatedException)
                return true;
        return false;
    }
    private static String safeError(Exception e) {
        if ((e instanceof IllegalArgumentException || e instanceof SecurityException || e instanceof IllegalStateException)
            && e.getMessage() != null && e.getMessage().length() < 180) return e.getMessage();
        return "Operación no completada. Revisa la conexión, la invitación y el estado del contacto.";
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
                boolean online = BuildConfig.ALLOW_RELAY && me.optBoolean("registered") && me.optBoolean("online") && !networkPaused;
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
                if (relay != null) transportStatus = "Internet disponible · relay cifrado";
            } catch (Exception e) {
                if (hasVaultFailure(e)) main.post(() -> { if (ticket == generation) lock(); });
                else transportStatus = "Sin respuesta del relay · mensajes pendientes";
            } finally {
                if (relay != null) { relay.close(); if (activeRelay == relay) activeRelay = null; }
                syncBusy.set(false);
                boolean update = changed;
                main.post(() -> { if (unlocked && ticket == generation && (update || tab == 1)) refresh(); });
            }
        });
    }
    private void cancelRelay() {
        RelayClient relay = activeRelay; activeRelay = null; if (relay != null) relay.close();
    }
    private RelayClient openRelay(String address, int ticket, boolean requireOnline) throws Exception {
        if (!BuildConfig.ALLOW_RELAY) throw new SecurityException("Esta edición no tiene acceso a internet");
        RelayClient relay = new RelayClient(address, () -> unlocked && ticket == generation && (!requireOnline || !networkPaused));
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
    private BluetoothLink link() {
        if (bluetooth != null) return bluetooth;
        final int ticket = generation;
        bluetooth = new BluetoothLink(this, new BluetoothLink.Listener() {
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
                main.post(() -> { if (unlocked && ticket == generation) { transportStatus = text; if (tab == 1) refresh(); } });
            }
        });
        return bluetooth;
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
        if (unlocked) { render(); notice("Vuelve a pulsar la acción y confirma para continuar."); }
    }
    private void refresh() {
        if (!unlocked || engine == null) return;
        String selected = chat;
        action(() -> new Snapshot(engine.profile(), engine.contacts(), selected == null ? List.of() : engine.messages(selected), selected == null ? List.of() : engine.locations().received(selected)), s -> {
            profile = s.profile;
            if (!networkStateLoaded) { networkPaused = !BuildConfig.ALLOW_RELAY || !profile.optBoolean("online"); networkStateLoaded = true; }
            contacts = s.contacts; if (Objects.equals(chat, selected)) { messages = s.messages; locations = s.locations; }
            render();
        });
    }
    private record Snapshot(JSONObject profile, List<JSONObject> contacts, List<JSONObject> messages, List<JSONObject> locations) {}
    private void base() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(14), dp(20), dp(10)); root.setFilterTouchesWhenObscured(true);
        root.setSaveEnabled(false); root.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(dp(20) + system.left, dp(10) + system.top, dp(20) + system.right, dp(8) + Math.max(system.bottom, keyboard.bottom));
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        page = column(); scroll.addView(page); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    private void heading(String title, String subtitle) {
        label(page, title, 29, TEXT).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(page, subtitle, 13, MUTED); space(page, 20);
    }
    private void showLocked() {
        base(); space(page, 75);
        TextView mark = label(page, "U", 72, MINT); mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label(page, "UMBRA", 35, TEXT).setLetterSpacing(.18f);
        label(page, "Tus conversaciones, bajo tu control.", 16, MUTED); space(page, 32);
        LinearLayout card = card(page);
        label(card, "Bóveda bloqueada", 18, TEXT);
        label(card, "La identidad y el historial requieren la autenticación de este teléfono.", 14, MUTED);
        button(page, "Desbloquear", this::authenticate, true);
        label(page, "Sin número de teléfono · Sin agenda compartida", 12, MUTED);
        label(page, "Versión de desarrollo. No auditada para uso sensible.", 12, AMBER);
    }
    private void onboarding() {
        base(); heading("Crea tu identidad", "No necesitas teléfono, correo ni una cuenta pública.");
        EditText alias = input(page, "Tu alias privado", false);
        LinearLayout warning = card(page);
        label(warning, "Una identidad. Este dispositivo.", 18, TEXT);
        label(warning, "No hay una llave maestra ni recuperación del historial. Perder el teléfono o invalidar su clave puede hacer los datos inaccesibles.", 14, MUTED);
        button(page, "Crear identidad protegida", () -> {
            String name = alias.getText().toString().trim();
            action(() -> { engine.initialize(name); initialised = true; return true; }, ok -> refresh());
        }, true);
    }
    private void render() {
        if (!unlocked) { showLocked(); return; }
        if (profile == null) return;
        if (peerDetails != null) { renderSecurity(peerDetails); return; }
        if (chat != null) { renderChat(); return; }
        base();
        switch (tab) { case 0 -> renderChats(); case 1 -> renderNearby(); case 2 -> renderIdentity(); default -> renderSettings(); }
        LinearLayout nav = row(); root.addView(nav);
        String[] tabs = {"Chats", "Cerca", "Identidad", "Ajustes"};
        for (int i = 0; i < tabs.length; i++) {
            final int destination = i;
            TextView button = text(tabs[i], 13, tab == i ? MINT : MUTED);
            button.setPadding(dp(4), dp(17), dp(4), dp(12)); button.setGravity(Gravity.CENTER);
            button.setOnClickListener(v -> { tab = destination; render(); });
            nav.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }
    private void renderChats() {
        heading("UMBRA", "Conversaciones privadas");
        LinearLayout banner = card(page); label(banner, "Tu contenido se cifra antes de salir", 15, MINT);
        label(banner, "Verifica cada contacto. La app se pausa al salir o bloquearla.", 13, MUTED);
        if (contacts.isEmpty()) {
            space(page, 34); label(page, "Tu espacio empieza en privado", 24, TEXT);
            label(page, "Conecta por Bluetooth o intercambia una invitación de contacto para iniciar una conversación.", 15, MUTED);
            button(page, "Conectar un teléfono cercano", () -> { tab = 1; render(); }, true);
            button(page, "Importar invitación", this::pickContact, false); return;
        }
        EditText search = input(page, "Buscar en tus contactos", false);
        LinearLayout results = column(); page.addView(results);
        Runnable rebuild = () -> {
            results.removeAllViews(); String filter = search.getText().toString().toLowerCase(Locale.ROOT);
            for (JSONObject contact : contacts) {
                JSONObject card = contact.optJSONObject("card"); String name = card.optString("alias");
                if (!name.toLowerCase(Locale.ROOT).contains(filter)) continue;
                LinearLayout item = row(); item.setPadding(0, dp(14), 0, dp(14));
                TextView avatar = text(name.substring(0, 1).toUpperCase(Locale.ROOT), 21, MINT);
                avatar.setGravity(Gravity.CENTER); avatar.setBackground(shape(PANEL, 50));
                item.addView(avatar, new LinearLayout.LayoutParams(dp(52), dp(52)));
                LinearLayout details = column(); details.setPadding(dp(14), 0, 0, 0);
                label(details, name, 18, TEXT);
                label(details, contact.optBoolean("blocked") ? "Bloqueado" : contact.optBoolean("verified") ? "Contacto verificado · cifrado E2E" : "Verificación de identidad pendiente", 12,
                    contact.optBoolean("verified") ? MUTED : AMBER);
                item.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                item.setOnClickListener(v -> {
                    if (!contact.optBoolean("verified") || contact.optBoolean("blocked")) { peerDetails = contact.optString("id"); render(); }
                    else { chat = contact.optString("id"); refresh(); }
                });
                results.addView(item); View line = new View(this); line.setBackgroundColor(EDGE); results.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        };
        watch(search, value -> rebuild.run()); rebuild.run();
    }
    private JSONObject uiContact(String id) { for (JSONObject c : contacts) if (id.equals(c.optString("id"))) return c; return null; }
    private void renderChat() {
        JSONObject contact = uiContact(chat); if (contact == null) { chat = null; render(); return; }
        base();
        LinearLayout header = row(); page.addView(header);
        TextView back = label(header, "‹", 33, MINT); back.setPadding(0, 0, dp(18), 0); back.setOnClickListener(v -> { chat = null; refresh(); });
        LinearLayout title = column(); header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        label(title, contact.optJSONObject("card").optString("alias"), 23, TEXT);
        label(title, "Verificado · cifrado de extremo a extremo", 11, MINT);
        TextView safety = label(header, "Seguridad", 12, MINT); safety.setOnClickListener(v -> { peerDetails = chat; render(); });
        space(page, 12);
        label(page, "Caducidad desde el envío: " + ttlName() + ". No impide que el destinatario copie el contenido.", 11, MUTED);
        space(page, 15);
        if (messages.isEmpty()) label(page, "Solo ustedes pueden abrir el contenido cifrado. Comienza la conversación.", 14, MUTED);
        for (JSONObject message : messages) {
            boolean outgoing = message.optBoolean("outgoing");
            LinearLayout wrap = row(); wrap.setGravity(outgoing ? Gravity.END : Gravity.START); page.addView(wrap);
            LinearLayout bubble = column(); bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
            bubble.setBackground(shape(outgoing ? Color.rgb(29,66,65) : PANEL, 17));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2); size.topMargin = dp(5);
            size.leftMargin = outgoing ? dp(30) : 0; size.rightMargin = outgoing ? 0 : dp(30); wrap.addView(bubble, size);
            if ("file".equals(message.optString("kind"))) {
                label(bubble, "Archivo · " + message.optString("name"), 16, TEXT);
                label(bubble, "Toca para guardar fuera de UMBRA", 11, MUTED);
                bubble.setOnClickListener(v -> confirm("Exportar archivo", "La copia exportada ya no estará protegida por la bóveda ni por el vencimiento del mensaje.", () -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, message.optString("name").replaceAll("[\\p{Cntrl}/\\\\]", "_"));
                    action(() -> engine.stageExport(Bytes.unb64(message.optString("data"))), key -> {
                        pendingExportKey = key; external(intent, EXPORT_FILE);
                    });
                }));
            } else label(bubble, message.optString("text"), 16, TEXT);
            String time = android.text.format.DateFormat.format("HH:mm", new Date(message.optLong("created") * 1000)).toString();
            label(bubble, time + (outgoing ? " · " + message.optString("status") : ""), 10, MUTED);
        }
        for(JSONObject location:locations) {
            JSONObject payload=location.optJSONObject("payload"), point=location.optJSONObject("lastPoint");
            label(page,"Ubicación · "+location.optString("display")+" · "+payload.optString("mode"),13,MUTED);
            if(point!=null) label(page,point.optLong("latE7")/10000000.0+", "+point.optLong("lonE7")/10000000.0+
                " · medición "+android.text.format.DateFormat.format("HH:mm:ss",new Date(point.optLong("measured")*1000))+
                " · incertidumbre sensor mm: "+point.optLong("sensorAccuracyMm")+" · celda E7: "+point.optLong("cellE7"),13,TEXT);
        }
        label(page,locationStatus,12,MUTED);
        button(page,"Compartir ubicación…",this::locationOptions,false);
        button(page,"Detener ubicación",() -> {
            var capture=locationCapture; String session=capture==null?null:capture.activeSession();
            if(session!=null) engine.locations().cancelCapture(session);
            if(capture!=null) capture.close();
            if(session!=null) action(() -> { engine.locations().stop(session); return true; },ok -> { locationStatus="Ubicación detenida"; refresh(); syncNow(); });
            else { engine.locations().cancelLocal(); action(() -> { engine.expire(); return true; },ok -> { locationStatus="Entregas de ubicación canceladas"; refresh(); }); }
        },false);
        if(BuildConfig.ALLOW_RELAY) button(page,"Señalización 1:1 (sin audio)…",this::callControls,false);
        // The composer remains below the scrollable history, without fake send or call buttons.
        LinearLayout composer = row(); composer.setGravity(Gravity.CENTER_VERTICAL); root.addView(composer);
        TextView attach = text("+", 29, MINT); attach.setPadding(dp(5), dp(8), dp(12), dp(8)); composer.addView(attach);
        attach.setOnClickListener(v -> {
            pendingAttachmentPeer = chat; pendingAttachmentTtl = ttl;
            external(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_FILE);
        });
        EditText draft = edit("Mensaje privado", false); draft.setText(drafts.getOrDefault(chat, ""));
        draft.setMaxLines(4); draft.setSingleLine(false); draft.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        composer.addView(draft, new LinearLayout.LayoutParams(0, -2, 1));
        String recipient = chat; watch(draft, value -> drafts.put(recipient, value));
        Runnable send = () -> {
            String text = draft.getText().toString(); if (text.trim().isEmpty()) return;
            action(() -> engine.sendText(recipient, text, ttl), id -> { drafts.remove(recipient); draft.setText(""); refresh(); syncNow(); });
        };
        TextView sendButton = text("Enviar", 14, MINT); sendButton.setPadding(dp(12), dp(12), 0, dp(12)); composer.addView(sendButton); sendButton.setOnClickListener(v -> send.run());
        draft.setOnEditorActionListener((v, actionId, event) -> { if (actionId == EditorInfo.IME_ACTION_SEND) { send.run(); return true; } return false; });
    }
    private void renderNearby() {
        heading("Cerca", "Conexión directa. Sin datos móviles ni internet.");
        LinearLayout status = card(page); label(status, transportStatus, 17, MINT);
        label(status, "Solo un enlace cercano a la vez. Ambos teléfonos deben mantener UMBRA abierta y desbloqueada.", 13, MUTED);
        Switch offline = new Switch(this); offline.setText(R.string.bluetooth_only); offline.setTextColor(TEXT);
        offline.setChecked(!BuildConfig.ALLOW_RELAY || !profile.optBoolean("online")); offline.setEnabled(BuildConfig.ALLOW_RELAY); page.addView(offline);
        offline.setOnCheckedChangeListener((v, checked) -> { networkPaused = checked; networkStateLoaded = true; if (checked) cancelRelay(); action(() -> { engine.setOnline(!checked); return true; }, ok -> refresh()); });
        button(page, "Esperar un contacto verificado", () -> {
            if (!bluetoothPermission()) return;
            try { link().listen(); render(); }
            catch (Exception e) { notice(safeError(e)); }
        }, true);
        button(page, "Hacer visible el teléfono durante 120 s", () -> {
            if (!bluetoothPermission()) return;
            external(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 0);
        }, false);
        button(page, "Conectar un contacto verificado", () -> chooseBluetooth(false), false);
        button(page, "Vincular un nuevo contacto", () -> confirm("Vinculación explícita", "Esta acción intercambia una tarjeta con identidad pública, alias y permiso de escritura al buzón. Verifica después el código completo en persona.", () ->
            new SecureDialogBuilder().setTitle("En ambos teléfonos elige vinculación nueva")
                .setItems(new String[]{"Esperar al nuevo contacto", "Conectar al nuevo contacto"}, (d, item) -> {
                    if (!bluetoothPermission()) return;
                    try { if (item == 0) { link().listen(true); render(); } else chooseBluetooth(true); }
                    catch (Exception e) { notice(safeError(e)); }
                }).setNegativeButton("Cancelar", null).show()), false);
        button(page, "Vincular teléfonos en ajustes de Android", () -> external(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0), false);
        button(page, "Desconectar Bluetooth", () -> { BluetoothLink b = bluetooth; if (b != null) b.close(); bluetooth = null; transportStatus = "Bluetooth desconectado"; render(); }, false);
        space(page, 12);
        label(page, "Después de conectar", 19, TEXT);
        label(page, "Los contactos aparecen en Chats. Abran Seguridad en ambos teléfonos, comparen el código completo y verifíquenlo antes de enviar.", 14, MUTED);
        label(page, "El alcance depende de los teléfonos y del entorno. No es una red de malla ni una conexión entre ciudades.", 13, AMBER);
    }
    private void chooseBluetooth(boolean enroll) {
        if (!bluetoothPermission()) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            notice("Se necesita permiso para consultar los dispositivos cercanos."); return;
        }
        try {
            BluetoothAdapter adapter = link().adapter();
            if (adapter == null || !adapter.isEnabled()) { notice("Activa Bluetooth en ajustes de Android."); return; }
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            if (devices.isEmpty()) { notice("Primero vincula ambos teléfonos en los ajustes Bluetooth de Android."); return; }
            String[] names = new String[devices.size()];
            for (int i = 0; i < names.length; i++) names[i] = Objects.toString(devices.get(i).getName(), "Dispositivo") + " · " + devices.get(i).getAddress();
            new SecureDialogBuilder().setTitle("Teléfono que está esperando").setItems(names, (d, index) -> {
                try { link().connect(devices.get(index), enroll); } catch (Exception e) { notice(safeError(e)); }
            }).setNegativeButton("Cancelar", null).show();
        } catch (SecurityException e) {
            notice("El permiso Bluetooth fue revocado. Revisa los permisos de dispositivos cercanos.");
        } catch (Exception e) { notice("No se pudo abrir Bluetooth. Revisa los permisos de dispositivos cercanos."); }
    }
    private void renderIdentity() {
        heading("Tu identidad", profile.optString("alias"));
        LinearLayout card = card(page); label(card, "Identidad pública del dispositivo", 16, TEXT);
        TextView fingerprint = label(card, group(profile.optString("id")), 14, MINT); fingerprint.setTypeface(Typeface.MONOSPACE);
        label(card, "Esta huella no es una contraseña. La verificación de cada conversación está en la ficha del contacto.", 13, MUTED);
        button(page, "Crear invitación de un uso (1 hora)", () -> action(() -> {
            String invite = new PairingService(vault).createInvitation(3600);
            return engine.stageExport(Bytes.utf8(invite));
        }, this::exportPairing), true);
        button(page, "Importar invitación, solicitud o confirmación", this::pickContact, false);
        button(page, "Revocar invitaciones sin consumir", () -> confirm("Revocar invitaciones", "Los archivos ya compartidos dejarán de permitir nuevos vínculos. Los contactos existentes no se eliminan.",
            () -> action(() -> new PairingService(vault).revokeUnused(), count -> notice("Invitaciones revocadas: " + count))), false);
        label(page, "Intercambia tres archivos: invitación, solicitud y confirmación. La invitación es un secreto de un uso y vence en una hora. Después, ambos deben comparar y verificar el código del contacto. Los archivos externos no están protegidos por la bóveda.", 14, MUTED);
        label(page, "Para conversar por internet, ambas personas deben registrar su buzón en el mismo servidor privado.", 14, MUTED);
    }
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
    private void renderSecurity(String peer) {
        JSONObject contact = uiContact(peer); if (contact == null) { peerDetails = null; render(); return; }
        base(); button(page, "‹ Volver", () -> { peerDetails = null; render(); }, false);
        heading("Verifica la identidad", contact.optJSONObject("card").optString("alias"));
        String code = Bytes.safetyCode(profile.optString("id"), peer);
        LinearLayout card = card(page); label(card, contact.optBoolean("verified") ? "Contacto verificado" : "No verificado: envío bloqueado", 18,
            contact.optBoolean("verified") ? MINT : AMBER);
        label(card, "Comparen este código completo en persona o por un canal previamente confiable. Debe ser igual en los dos teléfonos.", 14, MUTED);
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(app.umbra.verification.Verification.qr(profile.optString("id"), peer), BarcodeFormat.QR_CODE, 320, 320);
            Bitmap bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            ImageView qr = new ImageView(this); qr.setImageBitmap(bitmap); card.addView(qr, new LinearLayout.LayoutParams(dp(220), dp(220)));
        } catch (Exception ignored) { /* The complete textual code below is the primary verification path. */ }
        TextView fingerprint = label(card, group(code), 15, MINT); fingerprint.setTypeface(Typeface.MONOSPACE);
        if (!contact.optBoolean("blocked")) {
            EditText entered = input(page, "Código completo mostrado por la otra persona", false); entered.setSingleLine(false); entered.setMaxLines(4);
            button(page, "Comparar y verificar", () -> {
                String codeEntered = entered.getText().toString();
                action(() -> { engine.verify(peer, codeEntered); return true; }, ok -> { peerDetails = null; chat = peer; refresh(); syncNow(); });
            }, true);
        }
        button(page, contact.optBoolean("blocked") ? "Desbloquear contacto" : "Bloquear contacto", () -> action(() -> { engine.block(peer, !contact.optBoolean("blocked")); return true; }, ok -> {
            BluetoothLink active = bluetooth;
            if (active != null && peer.equals(active.connectedPeer())) { active.close(); bluetooth = null; }
            cancelRelay(); refresh();
        }), false);
        button(page, "Vaciar conversación local", () -> confirm("Vaciar conversación", "Borra el historial local y la cola pendiente. No borra las copias del otro teléfono ni revoca lo ya enviado al relay.",
            () -> action(() -> { engine.clearConversation(peer); return true; }, ok -> refresh())), false);
    }
    private void renderSettings() {
        heading("Ajustes", "Control explícito de la conexión y la privacidad");
        LinearLayout security = card(page); label(security, "Protección del dispositivo", 18, MINT);
        label(security, "Bóveda AES-GCM · Android Keystore · bloqueo al salir · sin copias automáticas · sin telemetría propia.", 14, MUTED);
        button(page, "Bloquear ahora", this::lock, false);
        LinearLayout expiry = card(page); label(expiry, "Mensajes nuevos: " + ttlName(), 17, TEXT);
        button(expiry, "Cambiar caducidad", () -> new SecureDialogBuilder().setTitle("Caducidad desde el envío")
            .setItems(new String[]{"1 hora", "24 horas", "7 días"}, (d, item) -> { ttl = new long[]{3600, 86400, 604800}[item]; render(); }).show(), false);
        if (BuildConfig.ALLOW_RELAY) {
        label(page, "Servidor de internet", 20, TEXT);
        label(page, profile.optBoolean("registered") ? "Buzón registrado" : "No configurado: Bluetooth sigue disponible", 13, AMBER);
        EditText server = input(page, "https://chat.tudominio.com", false); server.setText(profile.optString("relay"));
        EditText invitation = input(page, "Invitación privada del administrador", true);
        button(page, "Registrar buzón en el servidor", () -> {
            String address = server.getText().toString().trim(), invite = invitation.getText().toString().trim();
            action(() -> {
                String base = RelayClient.validate(address);
                try (RelayClient relay = openRelay(base, generation, false)) { relay.register(engine.profile(), invite); }
                engine.updateRelay(base, true); return true;
            }, ok -> { networkPaused = false; networkStateLoaded = true; invitation.setText(""); refresh(); syncNow(); });
        }, true);
        button(page, "Sincronizar ahora", this::syncNow, false);
        button(page, "Eliminar buzón del servidor", () -> confirm("Eliminar buzón remoto", "Se eliminan los mensajes pendientes de ese buzón. La identidad y el historial local permanecen.", () -> action(() -> {
            JSONObject me = engine.profile();
            try (RelayClient relay = openRelay(me.getString("relay"), generation, false)) { relay.unregister(me); }
            engine.updateRelay(me.getString("relay"), false); return true;
        }, ok -> refresh())), false);
        } else {
            LinearLayout offline = card(page); label(offline, "Edición OFFLINE", 18, MINT);
            label(offline, "Esta variante se compila sin permiso INTERNET. Solo utiliza Bluetooth; no puede sincronizar con el servidor.", 14, MUTED);
        }
        space(page, 16); label(page, "Alcance de esta entrega", 18, TEXT);
        label(page, "Android 12 o superior. Chats individuales y archivos de hasta 256 KiB. Sin llamadas, grupos, iPhone, malla ni recepción con la app bloqueada.", 13, MUTED);
        label(page, "0.2.0-dev · integración Android y libsignal pendientes de compilación y auditoría independiente. No usar para secretos reales todavía.", 13, AMBER);
        button(page, "Destruir identidad y datos locales", () -> confirm("Acción irreversible", "Se destruirá la clave de esta identidad. No existe recuperación. Esta acción no borra copias externas ni asegura borrado físico de la memoria flash.", () -> {
            BluetoothLink b = bluetooth; if (b != null) b.close(); bluetooth = null;
            action(() -> { vault.close(); Vault.destroyKey(); deleteDatabase("umbra.db"); vault = null; engine = null; initialised = false; return true; }, ok -> { lock(); authenticate(); });
        }), false);
    }
    private void external(Intent intent, int requestCode) {
        try { externalUi = true; startActivityForResult(intent, requestCode); }
        catch (Exception e) { externalUi = false; notice("No hay una aplicación del sistema disponible para esta acción."); }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); externalUi = false;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == EXPORT_CONTACT || request == EXPORT_FILE) pendingExportKey = null;
            return;
        }
        pendingResult = new PendingResult(request, data.getData());
        if (unlocked) resumeExternalResult(); // Otherwise authentication onResume resumes the explicitly requested action.
    }
    private void resumeExternalResult() {
        PendingResult result = pendingResult; if (result == null || !unlocked || engine == null) return;
        pendingResult = null; Uri uri = result.uri(); int request = result.request();
        if (!"content".equals(uri.getScheme())) { notice("Elige un documento mediante el selector de Android."); return; }
        if (request == PICK_CONTACT) confirm("Procesar vinculación", "Solo continúa si solicitaste este intercambio. La posesión del archivo no verifica a la persona. Una solicitud válida consume la invitación y crea un contacto sin verificar.", () -> action(() -> importPairing(uri), processed -> {
            if (processed.exportKey() != null) exportPairing(processed.exportKey());
            else { peerDetails = processed.peer(); refresh(); }
        }));
        else if (request == PICK_FILE) {
            String recipient = pendingAttachmentPeer; long lifetime = pendingAttachmentTtl;
            action(() -> {
                byte[] bytes = readBounded(uri, Engine.MAX_ATTACHMENT);
                try { return engine.sendFile(recipient, displayName(uri), bytes, lifetime); }
                finally { Arrays.fill(bytes, (byte) 0); }
            }, id -> { refresh(); syncNow(); });
        } else if (request == EXPORT_CONTACT || request == EXPORT_FILE) {
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
            }, ok -> notice("Documento exportado. La copia externa no está protegida por UMBRA."));
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
    private void back() { if (peerDetails != null) { peerDetails = null; render(); } else if (chat != null) { chat = null; refresh(); } else { lock(); moveTaskToBack(true); } }
    private void stopLocationLocally() {
        var capture=locationCapture; locationCapture=null; if(capture!=null) capture.close();
        if(engine!=null) { engine.locations().cancelLocal(); engine.calls().cancelLocal(); }
        locationStatus="Ubicación interrumpida; requiere nueva autorización";
    }
    private void callControls() {
        if(!BuildConfig.ALLOW_RELAY || !unlocked || chat==null) return;
        String peer=chat;
        new AlertDialog.Builder(this).setTitle("Señalización; audio/video no implementados")
            .setItems(new String[]{"Invitar (solo TURN en multimedia futura)","Revisar invitaciones / terminar"},(dialog,which) -> {
                if(which==0) action(() -> {
                    JSONObject index=engine.get("device-index",peer);
                    if(index==null) throw new SecurityException("Apruebe primero el conjunto de dispositivos");
                    return engine.calls().reviewInvite(index.getString("root"),app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY);
                },consent -> confirm("Invitar a señalización", "Sin micrófono ni cámara. Destinatario: "+peer+". Requiere TURN autorizado para multimedia futura.",
                    () -> action(() -> engine.calls().invite(consent,true),id -> { syncNow(); refresh(); })));
                else action(() -> engine.calls().sessions(),sessions -> {
                    for(JSONObject session:sessions) {
                        String id=session.optJSONObject("context").optString("callId"),state=session.optString("state");
                        if(app.umbra.calls.CallPayload.TERMINAL.contains(state)) continue;
                        new AlertDialog.Builder(this).setTitle("Señalización: "+state)
                            .setMessage("Sesión "+id+". No hay canal de audio/video.")
                            .setPositiveButton("Aceptar",(d,w) -> action(() -> engine.calls().reviewAccept(id,app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY),
                                consent -> confirm("Aceptar señalización", "Confirmar al interlocutor verificado: "+session.optJSONObject("context").optString("caller"),
                                    () -> action(() -> { engine.calls().accept(consent,true); return true; },ok -> {syncNow();refresh();}))))
                            .setNegativeButton("Rechazar / terminar",(d,w) -> { engine.calls().cancelPending(id); action(() -> {engine.calls().end(id);return true;},ok -> {syncNow();refresh();}); })
                            .setNeutralButton("Cerrar",null).show();
                    }
                });
            }).show();
    }
    private void locationOptions() {
        new SecureDialogBuilder().setTitle("Ubicación: solo mientras UMBRA esté desbloqueada")
            .setItems(new String[]{"Punto manual (sin GPS)","Punto del proveedor", "Temporal 15 minutos", "Temporal 1 hora", "Temporal 8 horas"},(d,index) -> {
                if(index==0) {
                    LinearLayout fields=column(); EditText lat=input(fields,"Latitud manual",false),lon=input(fields,"Longitud manual",false);
                    new SecureDialogBuilder().setTitle("Posición manual declarada").setView(fields).setNegativeButton("Cancelar",null)
                        .setPositiveButton("Revisar destinatarios",(dialog,w) -> {
                            try { reviewLocation(app.umbra.location.LocationPayload.Mode.MANUAL,120,false,Double.parseDouble(lat.getText().toString()),Double.parseDouble(lon.getText().toString())); }
                            catch(NumberFormatException invalid) { notice("Coordenadas inválidas"); }
                        }).show();
                } else {
                    boolean live=index>1; long duration=index==2?900:index==3?3600:index==4?28800:120;
                    new SecureDialogBuilder().setTitle("Detalle compartido")
                        .setItems(new String[]{"Estimación precisa","Aproximada (celda 0,01°)","Zona (celda 0,1°)"},(dialog,choice) -> {
                            var mode=choice==0?app.umbra.location.LocationPayload.Mode.PRECISE:choice==1?app.umbra.location.LocationPayload.Mode.APPROXIMATE:app.umbra.location.LocationPayload.Mode.ZONE;
                            boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
                            boolean coarse=checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
                            if(!fine && (!coarse || mode==app.umbra.location.LocationPayload.Mode.PRECISE)) {
                                requestPermissions(mode==app.umbra.location.LocationPayload.Mode.PRECISE?new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION}:new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},302);
                                return; // Permission result never starts capture. Require a new action/consent.
                            }
                            reviewLocation(mode,duration,live,0,0);
                        }).show();
                }
            }).show();
    }
    private void reviewLocation(app.umbra.location.LocationPayload.Mode mode,long duration,boolean live,double lat,double lon) {
        String peer=chat;
        action(() -> {
            JSONObject index=engine.get("device-index",peer);
            if(index==null) throw new SecurityException("Primero aprueba la lista autenticada de dispositivos del contacto");
            return engine.locations().review(index.getString("root"),mode,duration,live);
        },consent -> confirm("Consentimiento de ubicación", "Contacto: "+consent.recipient()+"\nDispositivos: "+String.join("\n",consent.devices())+
            "\nPrecisión: "+mode+" · máximo "+duration+" s. Captura solo este dispositivo. Bloquear o salir detiene; no se reanuda automáticamente.",() -> {
                action(() -> {
                    if(mode==app.umbra.location.LocationPayload.Mode.MANUAL) return engine.locations().manual(consent,true,lat,lon);
                    if(locationCapture!=null && locationCapture.activeSession()!=null) throw new SecurityException("Detén primero la captura actual");
                    String session=engine.locations().start(consent,true);
                    locationCapture=new app.umbra.location.AndroidLocationCapture(this,worker,() -> resumed && unlocked && !destroyed,engine.locations(),status -> main.post(() -> { if(unlocked) { locationStatus=status; refresh(); syncNow(); } }));
                    locationCapture.start(session,mode,live); return session;
                },session -> { refresh(); syncNow(); });
            }));
    }
    private String ttlName() { return ttl == 3600 ? "1 hora" : ttl == 604800 ? "7 días" : "24 horas"; }
    private String group(String code) { return code.replaceAll("(.{4})(?!$)", "$1 "); }
    private void notice(String text) { if (!destroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void confirm(String title, String message, Runnable yes) {
        new SecureDialogBuilder().setTitle(title).setMessage(message).setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar", (d, w) -> yes.run()).show();
    }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(3), 1); return t; }
    private TextView label(LinearLayout parent, String value, int size, int color) { TextView t = text(value, size, color); t.setPadding(0, dp(4), 0, dp(5)); parent.addView(t); return t; }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = column(); view.setPadding(dp(17), dp(14), dp(17), dp(14)); view.setBackground(shape(PANEL, 20));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(7); p.bottomMargin = dp(13); parent.addView(view, p); return view;
    }
    private final class SecureDialogBuilder extends AlertDialog.Builder {
        SecureDialogBuilder() { super(MainActivity.this); }
        @Override public AlertDialog show() {
            AlertDialog dialog = super.create();
            if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialogs.add(dialog); dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                dialog.getWindow().getDecorView().setFilterTouchesWhenObscured(true);
            }
            return dialog;
        }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED | MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        return (event.getFlags() & obscured) != 0 || super.dispatchTouchEvent(event);
    }
    private void button(LinearLayout parent, String value, Runnable tap, boolean primary) {
        TextView button = text(value, 15, primary ? BG : MINT); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER); button.setPadding(dp(15), dp(15), dp(15), dp(15)); button.setBackground(shape(primary ? MINT : PANEL, 14));
        button.setFilterTouchesWhenObscured(true); button.setOnClickListener(v -> tap.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2); size.topMargin = dp(7); size.bottomMargin = dp(5); parent.addView(button, size);
    }
    private EditText edit(String hint, boolean secret) {
        EditText input = new EditText(this); input.setHint(hint); input.setHintTextColor(MUTED); input.setTextColor(TEXT); input.setTextSize(15);
        input.setPadding(dp(14), dp(13), dp(14), dp(13)); input.setBackground(shape(PANEL, 13)); input.setSingleLine(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (secret) input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return input;
    }
    private EditText input(LinearLayout parent, String hint, boolean secret) {
        EditText input = edit(hint, secret); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(8); p.bottomMargin = dp(8); parent.addView(input, p); return input;
    }
    private void watch(EditText input, Consumer<String> changed) {
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { changed.accept(s.toString()); }
            public void afterTextChanged(Editable e) {}
        });
    }
}
