package app.umbra;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.Bytes;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import app.umbra.ui.screens.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Renders the production screen builders with SYNTHETIC presentation state on a real Android
 * framework (emulator), checks security-state wording, basic accessibility and layout, and saves
 * PNG evidence under the app's external files dir (ui-evidence/<flavor>/). It never creates an
 * identity, never opens the vault and uses no real data: Ana, Bruno, Carlos and "Equipo
 * Operaciones" are invented. Screenshots are off-screen renders of the screen builders, not a
 * recording of an unlocked session.
 */
@RunWith(AndroidJUnit4.class)
public class UiScreensRenderTest {
    private static final int WIDTH = 1080, HEIGHT = 2340;
    private static final String ANA = "a1".repeat(32), BRUNO = "b2".repeat(32), CARLOS = "c3".repeat(32), ME = "d4".repeat(32);
    private static final FeatureAvailability FEATURES = FeatureAvailability.forBuild(BuildConfig.ALLOW_RELAY, app.umbra.calls.CallPlatform.ENABLED);

    // ------------------------------------------------------------------ harness
    private static Context themed(float fontScale) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration c = new Configuration(target.getResources().getConfiguration()); c.fontScale = fontScale;
        return new ContextThemeWrapper(target.createConfigurationContext(c), R.style.Theme_Umbra);
    }
    private static View render(float fontScale, String name, Function<Ui, Screen> build) {
        View[] out = new View[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Ui ui = new Ui(themed(fontScale));
            LinearLayout root = build.apply(ui).compose(ui);
            root.setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(16));
            root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, WIDTH, HEIGHT);
            root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, WIDTH, HEIGHT); // Second pass lets ListView populate its visible rows.
            Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.BLACK);
            root.draw(new android.graphics.Canvas(bitmap));
            save(name, bitmap);
            bitmap.recycle();
            out[0] = root;
        });
        return out[0];
    }
    private static View render(String name, Function<Ui, Screen> build) { return render(1f, name, build); }
    private static void save(String name, Bitmap bitmap) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File dir = new File(target.getExternalFilesDir(null), "ui-evidence/" + (BuildConfig.ALLOW_RELAY ? "connected" : "offline"));
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException e) { throw new AssertionError("Evidence not written: " + name, e); }
    }
    private static List<View> all(View root) {
        List<View> views = new ArrayList<>(); Deque<View> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) {
            View v = queue.poll(); views.add(v);
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) queue.add(g.getChildAt(i));
        }
        return views;
    }
    private static boolean shown(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View parent ? parent : null) if (p.getVisibility() != View.VISIBLE) return false;
        return true;
    }
    private static String visibleText(View root) {
        StringBuilder b = new StringBuilder();
        for (View v : all(root)) if (shown(v) && v instanceof TextView t) b.append(t.getText()).append('\n');
        return b.toString();
    }
    private static boolean hasText(View root, String text) { return visibleText(root).contains(text); }
    /** Every interactive view is labelled and at least 48dp in both dimensions; nothing overflows horizontally. */
    private static void assertAccessible(View root) {
        float density = root.getResources().getDisplayMetrics().density;
        int min = Math.round(Ui.TOUCH_MIN_DP * density) - 1;
        for (View v : all(root)) {
            if (!shown(v)) continue;
            int left = 0; for (View p = v; p != null && p != root; p = p.getParent() instanceof View parent ? parent : null) left += p.getLeft();
            if (v.getWidth() > 0) assertTrue("Horizontal overflow: " + describe(v), left + v.getWidth() <= WIDTH + 1);
            if (!v.isClickable() || !v.isEnabled()) continue;
            CharSequence label = v.getContentDescription();
            if ((label == null || label.length() == 0) && v instanceof TextView t) label = t.getText().length() > 0 ? t.getText() : t.getHint();
            assertTrue("Unlabelled control: " + describe(v), label != null && label.toString().trim().length() > 0);
            if (v.getHeight() > 0) assertTrue("Touch target too small (" + v.getWidth() + "x" + v.getHeight() + "): " + describe(v), v.getHeight() >= min && v.getWidth() >= min);
        }
    }
    private static String describe(View v) {
        return v.getClass().getSimpleName() + " " + (v instanceof TextView t ? t.getText() : "") + " " + v.getContentDescription();
    }
    private static Button button(View root, String text) {
        for (View v : all(root)) if (v instanceof Button b && text.contentEquals(b.getText())) return b;
        return null;
    }

    // ------------------------------------------------------------------ synthetic fixtures
    private static final FeatureAvailability NO_GROUPS = FEATURES;
    private static List<ChatScreens.Entry> conversation() {
        List<ChatScreens.Entry> e = new ArrayList<>();
        e.add(new ChatScreens.Entry(MessageItem.text("1", false, "Hola, ¿revisaste el plan de mañana?", "09:12", null, null), null));
        e.add(new ChatScreens.Entry(MessageItem.text("2", true, "Mensaje de prueba. Sí, te envío el documento.", "09:14", "Entregado", null), null));
        e.add(new ChatScreens.Entry(MessageItem.file("3", true, "Documento.pdf", 184_320, "09:15", "En cola del servidor", null), null));
        e.add(new ChatScreens.Entry(MessageItem.file("4", false, "Plano.png", 96_000, "09:20", null, null), null));
        e.add(new ChatScreens.Entry(MessageItem.text("5", true, "Perfecto, nos vemos a las 10.", "09:21", "Pendiente", null), null));
        e.add(new ChatScreens.Entry(null, new ChatScreens.LocationEntry("Ubicación reciente de Bruno", "Aproximada · 4.61000, -74.08000 · medida 09:22", true, false)));
        return e;
    }
    private static ChatScreens.ChatState chat(TrustLevel trust, boolean sharing) {
        return new ChatScreens.ChatState(BRUNO, "Bruno", TrustPresentation.of(trust), conversation(), "24 horas", "", sharing,
            "En vivo · Aproximada · hasta 1 hora", FEATURES, !BuildConfig.ALLOW_RELAY, false);
    }
    private static final ChatScreens.ChatActions CHAT = new ChatScreens.ChatActions() {
        public void back() {} public void contact() {} public void verify() {} public void unblock() {} public void voiceCall() {} public void videoCall() {}
        public void openCall() {} public void attach() {} public void send(String t) {} public void draft(String t) {} public void message(MessageItem m) {}
        public void stopLocation() {} public void retry() {}
    };
    private static final CallScreens.CallActions CALL = new CallScreens.CallActions() {
        public void minimize() {} public void authorizeMicrophone() {} public void mute(boolean m) {} public void audioOutput() {} public void toggleModulator() {}
        public void modulated() {} public void natural() {} public void retryModulation() {} public void video() {} public void stopVideo() {} public void switchCamera() {}
        public void showRemoteVideo() {} public void answerVideo(int c) {} public void hangUp() {}
    };
    private static View nav(Ui ui, HomeTab tab) { return HomeScreens.bottomNav(ui, FEATURES, tab, t -> {}); }
    private static CallScreens.CallState call(String modulation, boolean muted, boolean video, boolean request, boolean modulatorOpen) {
        long now = android.os.SystemClock.elapsedRealtimeNanos();
        return new CallScreens.CallState("call-1", "Bruno", CallPresentation.of("NEGOTIATING", "ACTIVE"), "00:42", true, muted,
            ModulatorPresentation.of(modulation, muted), modulatorOpen, video ? VideoPresentation.of("ACTIVE", now, now - 200_000_000L, 0) : null, request, video, FEATURES);
    }

    // ------------------------------------------------------------------ tests
    @Test public void lockScreenShowsOnlyRealProtection() {
        View v = render("01-lock", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(true, !BuildConfig.ALLOW_RELAY, null), new EntryScreens.LockActions() {
            public void unlock() {} public void openSecuritySettings() {}
        }));
        assertTrue(hasText(v, "Bóveda bloqueada"));
        assertNotNull(button(v, "Desbloquear"));
        assertTrue(hasText(v, "Versión de desarrollo. No auditada para uso sensible."));
        assertAccessible(v);
        View insecure = render("01b-lock-no-device-credential", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(false, false, null), new EntryScreens.LockActions() {
            public void unlock() {} public void openSecuritySettings() {}
        }));
        assertNull("no unlock without a device credential", button(insecure, "Desbloquear"));
        assertNotNull(button(insecure, "Configurar bloqueo de Android"));
    }

    @Test public void onboardingIsShortAndMarksPendingAdmission() {
        EntryScreens.OnboardingActions a = new EntryScreens.OnboardingActions() { public void step(int n) {} public void create(String s) {} };
        View first = render("02a-onboarding-intro", ui -> EntryScreens.onboarding(ui, 0, !BuildConfig.ALLOW_RELAY, a));
        assertTrue(hasText(first, "Sin teléfono ni correo"));
        assertTrue(hasText(first, "Paso 1 de 3"));
        View last = render("02c-onboarding-identity", ui -> EntryScreens.onboarding(ui, 2, !BuildConfig.ALLOW_RELAY, a));
        assertTrue(hasText(last, "UI preparada · Backend pendiente"));
        assertAccessible(last);
        render("02b-onboarding-verification", ui -> EntryScreens.onboarding(ui, 1, !BuildConfig.ALLOW_RELAY, a));
    }

    @Test public void homeListsPeopleWithTrustAndNoContentPreview() {
        List<ConversationItem> items = List.of(ConversationItem.direct(ANA, "Ana", TrustLevel.VERIFIED, "09:30"),
            ConversationItem.direct(BRUNO, "Bruno", TrustLevel.IDENTITY_CHANGED, "Ayer"),
            ConversationItem.direct(CARLOS, "Carlos", TrustLevel.UNVERIFIED, ""));
        HomeScreens.ChatsActions a = new HomeScreens.ChatsActions() {
            public void open(ConversationItem i) {} public void newMessage() {} public void newGroup() {} public void addContact() {}
            public void filter(HomeScreens.Filter f) {} public void openIncoming(String id) {} public void networkDetails() {}
        };
        View v = render("03-home-chats", ui -> HomeScreens.chats(ui, new HomeScreens.ChatsState(items, false, HomeScreens.Filter.ALL, FEATURES, !BuildConfig.ALLOW_RELAY, false, null, null), a, nav(ui, HomeTab.CHATS)));
        String text = visibleText(v);
        assertTrue(text.contains("La identidad cambió · verifica de nuevo"));
        assertTrue(text.contains("Verificación pendiente · envío bloqueado"));
        assertEquals(BuildConfig.ALLOW_RELAY, text.contains("Llamadas"));
        assertAccessible(v);
        View empty = render("17-empty-state", ui -> HomeScreens.chats(ui, new HomeScreens.ChatsState(List.of(), false, HomeScreens.Filter.ALL, FEATURES, !BuildConfig.ALLOW_RELAY, false, null, null), a, nav(ui, HomeTab.CHATS)));
        assertTrue(hasText(empty, "Todavía no tienes conversaciones."));
        assertTrue(hasText(empty, "Agrega un contacto mediante una invitación segura."));
        View groups = render("03b-home-groups-pending", ui -> HomeScreens.chats(ui, new HomeScreens.ChatsState(items, false, HomeScreens.Filter.GROUPS, FEATURES, !BuildConfig.ALLOW_RELAY, false, null, null), a, nav(ui, HomeTab.CHATS)));
        assertTrue(hasText(groups, "UI preparada · Backend pendiente"));
        render("03c-home-loading", ui -> HomeScreens.chats(ui, new HomeScreens.ChatsState(items, true, HomeScreens.Filter.ALL, FEATURES, !BuildConfig.ALLOW_RELAY, false, null, null), a, nav(ui, HomeTab.CHATS)));
    }

    @Test public void verifiedChatShowsComposerAndContentKinds() {
        View v = render("04-chat-verified", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, false), CHAT));
        assertTrue(hasText(v, "Verificado"));
        boolean composer = false; for (View x : all(v)) if (x instanceof EditText e && "Mensaje privado".contentEquals(e.getHint())) composer = true;
        assertTrue(composer);
        assertAccessible(v);
        render("04b-chat-sharing-location", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, true), CHAT));
    }

    @Test public void identityChangeBlocksSendingAndSaysWhy() {
        View v = render("15-chat-identity-changed", ui -> ChatScreens.direct(ui, chat(TrustLevel.IDENTITY_CHANGED, false), CHAT));
        assertTrue(hasText(v, "La identidad criptográfica de este contacto cambió. Verifica nuevamente antes de continuar con operaciones sensibles."));
        assertTrue(hasText(v, "Envío bloqueado por seguridad"));
        for (View x : all(v)) assertFalse("no composer when identity changed", x instanceof EditText);
        assertFalse(hasText(v, "✓ Verificado"));
        View unverified = render("15b-chat-unverified", ui -> ChatScreens.direct(ui, chat(TrustLevel.UNVERIFIED, false), CHAT));
        for (View x : all(unverified)) assertFalse(x instanceof EditText);
        View blocked = render("15c-chat-blocked", ui -> ChatScreens.direct(ui, chat(TrustLevel.BLOCKED, false), CHAT));
        assertTrue(hasText(blocked, "Contacto bloqueado"));
        assertAccessible(v);
    }

    @Test public void groupScreensStayPreparedWithoutEngine() {
        List<MessageItem> messages = List.of(MessageItem.system("s1", "Carlos se unió al grupo", "08:00"),
            MessageItem.text("g1", false, "Buenos días, equipo.", "08:01", null, "Ana"),
            MessageItem.text("g2", false, "Revisen Documento.pdf antes de las 10.", "08:03", null, "Carlos"),
            MessageItem.text("g3", true, "Recibido, gracias.", "08:05", "Entregado", null));
        View v = render("05-chat-group", ui -> ChatScreens.group(ui, new ChatScreens.GroupState("g", "Equipo Operaciones", List.of("Ana", "Bruno", "Carlos", "Tú", "Diana"), messages, NO_GROUPS), new ChatScreens.GroupActions() {
            public void back() {} public void info() {}
        }));
        assertTrue(hasText(v, "Equipo Operaciones"));
        assertTrue(hasText(v, "5 miembros"));
        assertTrue(hasText(v, "Vista previa de diseño"));
        Button send = button(v, "Escribir al grupo"); assertNotNull(send); assertFalse(send.isEnabled());
        List<ChatScreens.Contact> contacts = List.of(new ChatScreens.Contact(ANA, "Ana", TrustLevel.VERIFIED), new ChatScreens.Contact(BRUNO, "Bruno", TrustLevel.IDENTITY_CHANGED), new ChatScreens.Contact(CARLOS, "Carlos", TrustLevel.VERIFIED));
        ChatScreens.NewGroupActions na = new ChatScreens.NewGroupActions() { public void back() {} public void toggle(String id) {} public void step(int s) {} public void name(String n) {} public void create() {} };
        render("19a-new-group-members", ui -> ChatScreens.newGroup(ui, new ChatScreens.NewGroupState(0, contacts, Set.of(ANA, CARLOS), "", NO_GROUPS), na));
        View create = render("19b-new-group-name", ui -> ChatScreens.newGroup(ui, new ChatScreens.NewGroupState(2, contacts, Set.of(ANA, CARLOS), "Equipo Operaciones", NO_GROUPS), na));
        Button b = button(create, "Crear grupo"); assertNotNull(b); assertFalse("no group engine: creation disabled", b.isEnabled());
        View pick = render("19c-new-message", ui -> ChatScreens.newMessage(ui, contacts, new ChatScreens.PickActions() { public void back() {} public void pick(ChatScreens.Contact c) {} public void addContact() {} }));
        assertTrue(hasText(pick, "Contactos verificados"));
        assertTrue(hasText(pick, "Requieren atención"));
        assertAccessible(pick);
    }

    @Test public void contactAndVerificationUsePlainLanguage() {
        SecurityScreens.ContactActions ca = new SecurityScreens.ContactActions() { public void back() {} public void verify() {} public void block(boolean b) {} public void clear() {} public void call() {} public void message() {} };
        View contact = render("06-contact-info", ui -> SecurityScreens.contact(ui, new SecurityScreens.ContactState(BRUNO, "Bruno", TrustPresentation.of(TrustLevel.VERIFIED), 2, 1, FEATURES.visible(Feature.VOICE_CALLS), FEATURES), ca));
        assertTrue(hasText(contact, "Contacto verificado"));
        assertTrue(hasText(contact, "2 dispositivos en su lista firmada"));
        assertAccessible(contact);
        String code = Bytes.safetyCode(ME, BRUNO);
        Bitmap qr;
        try {
            BitMatrix m = new MultiFormatWriter().encode(app.umbra.verification.Verification.qr(ME, BRUNO), BarcodeFormat.QR_CODE, 320, 320);
            qr = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 320; y++) for (int x = 0; x < 320; x++) qr.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
        } catch (Exception e) { throw new AssertionError(e); }
        SecurityScreens.VerifyActions va = new SecurityScreens.VerifyActions() { public void back() {} public void method(SecurityScreens.Method m) {} public void compare(String c) {} public void technical(boolean s) {} };
        View verify = render("07-verify-code", ui -> SecurityScreens.verify(ui, new SecurityScreens.VerifyState("Bruno", TrustPresentation.of(TrustLevel.UNVERIFIED), code, null, SecurityScreens.Method.CODE, false, "D4D4D4D4", "B2B2B2B2", FEATURES), va));
        assertTrue(hasText(verify, "NO VERIFICADO"));
        assertTrue(hasText(verify, "IDENTIDAD CAMBIÓ"));
        assertTrue(hasText(verify, "BLOQUEADO"));
        assertFalse(visibleText(verify).toLowerCase(Locale.ROOT).contains("x3dh"));
        assertTrue(visibleText(verify).contains(Fingerprints.group(code).substring(0, 9)));
        assertAccessible(verify);
        Bitmap finalQr = qr;
        render("07b-verify-qr", ui -> SecurityScreens.verify(ui, new SecurityScreens.VerifyState("Bruno", TrustPresentation.of(TrustLevel.UNVERIFIED), code, finalQr, SecurityScreens.Method.QR, false, "D4D4D4D4", "B2B2B2B2", FEATURES), va));
        render("07c-verify-manual-identity-changed", ui -> SecurityScreens.verify(ui, new SecurityScreens.VerifyState("Bruno", TrustPresentation.of(TrustLevel.IDENTITY_CHANGED), code, null, SecurityScreens.Method.MANUAL, true, "D4D4D4D4", "B2B2B2B2", FEATURES), va));
    }

    @Test public void devicesAndLocationStateWhoHowLongAndPrecision() {
        List<DeviceItem> own = List.of(DeviceItem.of(ME, true, true, true), DeviceItem.of("e5".repeat(32), false, true, true), DeviceItem.of("f6".repeat(32), false, false, true));
        View devices = render("08-devices", ui -> DeviceScreens.devices(ui, new DeviceScreens.DevicesState(own, true, null,
            List.of(new DeviceScreens.ContactDevices("Ana", 1), new DeviceScreens.ContactDevices("Bruno", 2), new DeviceScreens.ContactDevices("Carlos", -1)), FEATURES),
            new DeviceScreens.DevicesActions() { public void back() {} public void revoke(DeviceItem d) {} public void add() {} }));
        assertTrue(hasText(devices, "Este dispositivo"));
        assertTrue(hasText(devices, "Revocado"));
        for (View x : all(devices)) if (x instanceof Button b && b.getText().toString().startsWith("Revocar")) assertEquals(FEATURES.available(Feature.DEVICE_REVOCATION), b.isEnabled());
        assertAccessible(devices);
        View location = render("09-location-sheet", ui -> Screen.of(null, DeviceScreens.locationSheet(ui, new DeviceScreens.LocationSheetState("Bruno", true, LocationShareDraft.Precision.APPROXIMATE, 1),
            new DeviceScreens.LocationActions() { public void live(boolean l) {} public void precision(LocationShareDraft.Precision p) {} public void duration(int i) {} public void review(String a, String b) {} public void stopAll() {} }), null));
        assertTrue(hasText(location, "Quién la recibirá"));
        assertTrue(hasText(location, "Durante cuánto tiempo"));
        assertTrue(hasText(location, "Con qué precisión"));
        Button stopAll = button(location, "DETENER UBICACIÓN"); assertNotNull(stopAll); assertTrue(stopAll.isEnabled());
        View sharing = render("09b-location-stop", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, true), CHAT));
        Button stop = button(sharing, "DETENER UBICACIÓN"); assertNotNull(stop); assertTrue("stop needs no extra password", stop.isEnabled());
    }

    @Test public void callScreenShowsRealTransmissionState() {
        assertEquals(BuildConfig.ALLOW_RELAY, HomeTab.visible(FEATURES).contains(HomeTab.CALLS));
        if (!FEATURES.visible(Feature.VOICE_CALLS)) {
            Navigator nav = new Navigator(FEATURES); nav.home();
            assertFalse("offline has no call destinations", nav.push(Route.of(Route.Kind.CALL, "x")));
            return; // No call screenshots in the offline edition.
        }
        View voice = render("10-call-voice", ui -> CallScreens.call(ui, call("OFF", false, false, false, false), CALL, null));
        assertTrue(hasText(voice, "Transmitiendo tu voz natural"));
        assertTrue(hasText(voice, "00:42"));
        assertFalse(visibleText(voice).contains("WebRTC"));
        assertAccessible(voice);
        View muted = render("10b-call-muted", ui -> CallScreens.call(ui, call("ON", true, false, false, false), CALL, null));
        assertTrue(hasText(muted, "Micrófono silenciado · no se transmite audio"));
        View pending = render("10c-call-mic-not-authorized", ui -> CallScreens.call(ui, new CallScreens.CallState("c", "Bruno", CallPresentation.of("SELECTED", null), null, false, false, null, false, null, false, false, FEATURES), CALL, null));
        assertNotNull(button(pending, "Autorizar micrófono"));
        View incoming = render("16-incoming-call", ui -> CallScreens.incoming(ui, new CallScreens.IncomingState("c", "Bruno", TrustPresentation.of(TrustLevel.VERIFIED), false),
            new CallScreens.IncomingActions() { public void reject() {} public void answer() {} public void later() {} }));
        assertTrue(hasText(incoming, "Llamada de Bruno"));
        assertAccessible(incoming);
    }

    @Test public void modulatorNeverClaimsModulationBeforeEngineConfirms() {
        if (!FEATURES.visible(Feature.VOICE_MODULATION)) { assertFalse(FEATURES.available(Feature.VOICE_MODULATION)); return; }
        View enabling = render("11a-modulator-enabling", ui -> CallScreens.call(ui, call("ENABLING", false, false, false, true), CALL, null));
        assertTrue(hasText(enabling, "Activando modulación…"));
        for (View x : all(enabling)) if (x instanceof Button b && "Modulada".contentEquals(b.getText())) { assertFalse(b.isSelected()); assertFalse(b.isEnabled()); }
        View on = render("11b-modulator-on", ui -> CallScreens.call(ui, call("ON", false, false, false, true), CALL, null));
        boolean selected = false; for (View x : all(on)) if (x instanceof Button b && "Modulada".contentEquals(b.getText())) selected = b.isSelected();
        assertTrue(selected);
        assertTrue(hasText(on, "Transmitiendo voz modulada"));
        View failed = render("11c-modulator-error", ui -> CallScreens.call(ui, call("ERROR_MUTED", false, false, false, true), CALL, null));
        assertTrue(hasText(failed, "La modulación falló."));
        assertTrue(hasText(failed, "Tu micrófono permanece silenciado. Reintenta o confirma voz natural."));
        assertFalse(visibleText(failed).toLowerCase(Locale.ROOT).contains("anónim"));
        assertAccessible(failed);
    }

    @Test public void videoCallRequiresConsentPerDirection() {
        if (!FEATURES.visible(Feature.VIDEO_CALLS)) { assertFalse(FEATURES.available(Feature.VIDEO_CALLS)); return; }
        View request = render("12a-video-consent", ui -> CallScreens.call(ui, call("OFF", false, true, true, false), CALL, null));
        assertTrue(hasText(request, "Bruno solicita activar video."));
        assertNotNull(button(request, "Rechazar"));
        assertNotNull(button(request, "Permitir recibir"));
        assertNotNull(button(request, "Compartir mi cámara"));
        View video = render("12b-video-call", ui -> CallScreens.call(ui, call("ON", false, true, false, false), CALL, null));
        assertTrue(hasText(video, "Tu cámara está transmitiendo"));
        assertAccessible(video);
    }

    @Test public void settingsSectionsAreHonestAboutPendingControls() {
        HomeScreens.SettingsActions ra = new HomeScreens.SettingsActions() { public void open(SettingsSection s) {} public void lockNow() {} };
        View rootSettings = render("13-settings", ui -> HomeScreens.settings(ui, "Ana", !BuildConfig.ALLOW_RELAY, false, ra, nav(ui, HomeTab.SETTINGS)));
        for (SettingsSection s : SettingsSection.values()) if (s != SettingsSection.PROFILE) assertTrue(s.title, hasText(rootSettings, s.title));
        assertAccessible(rootSettings);
        SettingsScreens.SettingsActions sa = new SettingsScreens.SettingsActions() {
            public void back() {} public void createInvitation() {} public void importInvitation() {} public void revokeInvitations() {} public void lockNow() {}
            public void destroyIdentity() {} public void expiry(int i) {} public void register(String a, String i) {} public void syncNow() {} public void unregister() {}
            public void bluetoothOnly(boolean e) {} public void devices() {}
        };
        for (SettingsSection s : new SettingsSection[]{SettingsSection.PROFILE, SettingsSection.PRIVACY, SettingsSection.SECURITY, SettingsSection.NETWORK, SettingsSection.ABOUT}) {
            View v = render("13-settings-" + s.name().toLowerCase(Locale.ROOT), ui -> SettingsScreens.section(ui, new SettingsScreens.SettingsState(s, "Ana", ME, FEATURES,
                !BuildConfig.ALLOW_RELAY, false, BuildConfig.ALLOW_RELAY, BuildConfig.ALLOW_RELAY ? "https://relay.example.test" : "", "24 horas", 1, BuildConfig.VERSION_NAME), sa));
            assertAccessible(v);
            if (s == SettingsSection.SECURITY) {
                Button emergency = button(v, "BLOQUEAR UMBRA"); assertNotNull(emergency);
                assertEquals(FEATURES.available(Feature.EMERGENCY_LOCK), emergency.isEnabled());
                assertTrue(hasText(v, "UI preparada · Backend pendiente"));
            }
            if (s == SettingsSection.PROFILE) assertFalse(visibleText(v).toLowerCase(Locale.ROOT).contains("private key"));
        }
    }

    @Test public void offlineEditionShowsBluetoothIdentityAndNoInternetFeatures() {
        HomeScreens.NearbyActions na = new HomeScreens.NearbyActions() {
            public void bluetoothOnly(boolean e) {} public void listen() {} public void makeVisible() {} public void connectVerified() {} public void enrollNew() {} public void systemSettings() {} public void disconnect() {}
        };
        View nearby = render("14-offline-nearby", ui -> HomeScreens.nearby(ui, new HomeScreens.NearbyState("Sin conexión activa", !BuildConfig.ALLOW_RELAY, true, BuildConfig.ALLOW_RELAY), na, nav(ui, HomeTab.NEARBY)));
        assertAccessible(nearby);
        if (!BuildConfig.ALLOW_RELAY) {
            assertTrue(hasText(nearby, "Modo offline · Bluetooth"));
            assertFalse(hasText(nearby, "Llamadas"));
            assertFalse(hasText(nearby, "Solo Bluetooth")); // No Internet switch where Internet does not exist.
            View chat = render("14b-offline-chat", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, false), CHAT));
            for (View x : all(chat)) assertFalse("no call buttons offline", "Llamada de voz".contentEquals(String.valueOf(x.getContentDescription())) || "Videollamada".contentEquals(String.valueOf(x.getContentDescription())));
        } else assertTrue(hasText(nearby, "Solo Bluetooth"));
    }

    @Test public void errorsAreHumanWithOptionalTechnicalDetails() {
        View v = render("18-errors", ui -> {
            LinearLayout box = ui.column();
            for (ErrorKind k : new ErrorKind[]{ErrorKind.NO_CONNECTION, ErrorKind.RELAY_UNAVAILABLE, ErrorKind.TURN_UNAVAILABLE, ErrorKind.IDENTITY_CHANGED, ErrorKind.DEVICE_REVOKED, ErrorKind.MICROPHONE_DENIED})
                box.addView(ui.errorState(ErrorPresentation.of(k, "SecurityException: synthetic diagnostic"), () -> {}, true));
            return Screen.of(null, box, null);
        });
        assertTrue(hasText(v, "Servidor privado no disponible"));
        assertFalse("technical details collapsed by default", hasText(v, "synthetic diagnostic"));
        assertNotNull(button(v, "Detalles técnicos"));
        assertAccessible(v);
    }

    @Test public void largeFontScaleWrapsWithoutHorizontalOverflow() {
        View lock = render(2.0f, "20a-font-200-lock", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(true, !BuildConfig.ALLOW_RELAY, null), new EntryScreens.LockActions() {
            public void unlock() {} public void openSecuritySettings() {}
        }));
        assertAccessible(lock);
        View chat = render(2.0f, "20b-font-200-chat-identity-changed", ui -> ChatScreens.direct(ui, chat(TrustLevel.IDENTITY_CHANGED, false), CHAT));
        assertAccessible(chat);
        View verify = render(1.5f, "20c-font-150-verify", ui -> SecurityScreens.verify(ui, new SecurityScreens.VerifyState("Bruno", TrustPresentation.of(TrustLevel.VERIFIED), Bytes.safetyCode(ME, BRUNO), null, SecurityScreens.Method.CODE, false, "D4", "B2", FEATURES),
            new SecurityScreens.VerifyActions() { public void back() {} public void method(SecurityScreens.Method m) {} public void compare(String c) {} public void technical(boolean s) {} }));
        assertAccessible(verify);
    }

    @Test public void brandingLauncherSplashNotificationAndIconSetRenderOnAndroid() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = themed(1f); float d = ctx.getResources().getDisplayMetrics().density;
            android.graphics.drawable.Drawable launcher = ctx.getDrawable(R.mipmap.ic_launcher);
            assertTrue("launcher must be adaptive", launcher instanceof android.graphics.drawable.AdaptiveIconDrawable);
            android.graphics.drawable.AdaptiveIconDrawable adaptive = (android.graphics.drawable.AdaptiveIconDrawable) launcher;
            if (android.os.Build.VERSION.SDK_INT >= 33) assertNotNull("themed icon layer", adaptive.getMonochrome());
            assertTrue(ctx.getDrawable(R.mipmap.ic_launcher_round) instanceof android.graphics.drawable.AdaptiveIconDrawable);
            // Foreground stays inside the 66dp safe zone (radius 33 of 108) for every mask.
            int size = Math.round(108 * d);
            Bitmap fg = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.drawable.Drawable front = ctx.getDrawable(R.drawable.ic_launcher_foreground);
            front.setBounds(0, 0, size, size); front.draw(new android.graphics.Canvas(fg));
            double max = 0; int opaque = 0;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (Color.alpha(fg.getPixel(x, y)) > 16) { opaque++; max = Math.max(max, Math.hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0)); }
            assertTrue("symbol drawn", opaque > size * size / 50);
            assertTrue("symbol leaves the safe zone: " + max / d + "dp", max <= 33 * d + 1);
            // Mask and backdrop sheet (real framework drawing of the real resources).
            String[] masks = {"circle", "squircle", "rounded", "teardrop", "square"};
            int[] backdrops = {Color.BLACK, 0xFF7A7F78, Color.WHITE, 0xFFD9DDD2, 0xFF1F2A22};
            int cell = Math.round(72 * d), pad = Math.round(12 * d);
            Bitmap sheet = Bitmap.createBitmap((cell + pad) * masks.length + pad, (cell + pad) * backdrops.length + pad, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(sheet);
            for (int r = 0; r < backdrops.length; r++) {
                android.graphics.Paint bg = new android.graphics.Paint(); bg.setColor(backdrops[r]);
                c.drawRect(0, r * (cell + pad), sheet.getWidth(), (r + 1) * (cell + pad) + pad, bg);
                for (int m = 0; m < masks.length; m++) {
                    int left = pad + m * (cell + pad), top = pad + r * (cell + pad);
                    android.graphics.Path clip = new android.graphics.Path(); android.graphics.RectF box = new android.graphics.RectF(left, top, left + cell, top + cell);
                    switch (masks[m]) {
                        case "circle" -> clip.addOval(box, android.graphics.Path.Direction.CW);
                        case "squircle" -> clip.addRoundRect(box, cell * 0.36f, cell * 0.36f, android.graphics.Path.Direction.CW);
                        case "rounded" -> clip.addRoundRect(box, cell * 0.18f, cell * 0.18f, android.graphics.Path.Direction.CW);
                        case "teardrop" -> clip.addRoundRect(box, new float[]{cell / 2f, cell / 2f, cell / 2f, cell / 2f, cell * 0.1f, cell * 0.1f, cell / 2f, cell / 2f}, android.graphics.Path.Direction.CW);
                        default -> clip.addRect(box, android.graphics.Path.Direction.CW);
                    }
                    c.save(); c.clipPath(clip);
                    // Adaptive layers are 108dp with 18dp bleed on each side of the 72dp viewport.
                    int bleed = Math.round(cell * 18f / 72f);
                    adaptive.getBackground().setBounds(left - bleed, top - bleed, left + cell + bleed, top + cell + bleed); adaptive.getBackground().draw(c);
                    adaptive.getForeground().setBounds(left - bleed, top - bleed, left + cell + bleed, top + cell + bleed); adaptive.getForeground().draw(c);
                    c.restore();
                }
            }
            save("21-launcher-icon-masks", sheet); sheet.recycle();
            // Splash: resolve the theme attributes and compose the equivalent frame.
            android.content.res.TypedArray t = ctx.obtainStyledAttributes(R.style.Theme_Umbra, new int[]{android.R.attr.windowSplashScreenAnimatedIcon, android.R.attr.windowSplashScreenBackground});
            int icon = t.getResourceId(0, 0); int splashBg = t.getColor(1, 0); t.recycle();
            assertEquals(R.drawable.ic_launcher_foreground, icon);
            assertEquals(0xFF0E120F, splashBg);
            Bitmap splash = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888); splash.eraseColor(splashBg);
            int s2 = Math.round(240 * d); android.graphics.drawable.Drawable si = ctx.getDrawable(icon);
            si.setBounds((WIDTH - s2) / 2, (HEIGHT - s2) / 2, (WIDTH + s2) / 2, (HEIGHT + s2) / 2); si.draw(new android.graphics.Canvas(splash));
            save("22-splash-theme-composition", splash); splash.recycle();
            // Notification icon: white silhouette only.
            Bitmap note = Bitmap.createBitmap(Math.round(24 * d), Math.round(24 * d), Bitmap.Config.ARGB_8888);
            android.graphics.drawable.Drawable n = ctx.getDrawable(R.drawable.ic_notification_umbra); n.setBounds(0, 0, note.getWidth(), note.getHeight()); n.draw(new android.graphics.Canvas(note));
            int white = 0;
            for (int y = 0; y < note.getHeight(); y++) for (int x = 0; x < note.getWidth(); x++) {
                int px = note.getPixel(x, y);
                if (Color.alpha(px) > 200) { white++; assertTrue("notification icon must be white", Color.red(px) > 240 && Color.green(px) > 240 && Color.blue(px) > 240); }
            }
            assertTrue(white > 20); note.recycle();
        });
        View icons = render("23-icon-set", ui -> {
            LinearLayout box = ui.column();
            int[] colors = {app.umbra.ui.design.StateColors.NORMAL, app.umbra.ui.design.StateColors.SELECTED, app.umbra.ui.design.StateColors.DISABLED,
                app.umbra.ui.design.StateColors.WARNING, app.umbra.ui.design.StateColors.DANGER, app.umbra.ui.design.StateColors.VERIFIED};
            LinearLayout line = null; int i = 0;
            for (Glyph g : Glyph.values()) {
                if (i++ % 10 == 0) { line = ui.row(); box.addView(line); }
                line.addView(ui.iconView(g, colors[i % colors.length], 24), ui.margins(new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)), 4, 4));
            }
            LinearLayout sizes = ui.row(); box.addView(sizes);
            for (int sdp : new int[]{16, 20, 24, 32, 48}) sizes.addView(ui.iconView(Glyph.VOICE, app.umbra.ui.design.UmbraColors.ACCENT_SECONDARY, sdp));
            box.addView(ui.logo(48, app.umbra.ui.design.UmbraColors.ACCENT_MUTED));
            return Screen.of(null, box, null);
        });
        assertNotNull(icons);
    }
}
