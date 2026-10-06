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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.Bytes;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import app.umbra.ui.screens.*;
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
    /** Synthetic presentation of an unlocked, admitted but not connected session (no domain state implied). */
    private static final ConnectivityPresentation OFFLINE_SESSION = ConnectivityPresentation.of("UNLOCKED_OFFLINE", !BuildConfig.ALLOW_RELAY, true, false, true,
        ConnectivityPresentation.Service.NOT_OBSERVED);

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
    /** A TextView whose whole text is exactly {@code text}. */
    private static boolean hasExact(View root, String text) {
        for (View v : all(root)) if (shown(v) && v instanceof TextView t && !(v instanceof EditText) && text.contentEquals(t.getText())) return true;
        return false;
    }
    /** The help control that opens the sheet for {@code topic}. */
    private static View help(View root, Help topic) {
        for (View v : all(root)) if (v instanceof ImageButton && ("Ayuda: " + topic.title).contentEquals(String.valueOf(v.getContentDescription()))) return v;
        return null;
    }
    /**
     * Clean style: Spanish only on screen, short buttons and short visible lines (explanations live in help sheets).
     * It measures human copy. Technical data (Ui.TECHNICAL: fingerprints, safety codes, identifiers, diagnostics)
     * is a value to compare, never shortened: it is checked instead for a technical-only alphabet, a spoken
     * accessibility label and wrapping (no ellipsis), so prose can never hide inside a "technical" view.
     */
    private static final java.util.regex.Pattern TECHNICAL_TEXT = java.util.regex.Pattern.compile("[0-9A-Za-z_\\-·.:/ \\n]+");
    private static void assertConcise(View root) {
        for (View v : all(root)) {
            if (!shown(v) || !(v instanceof TextView t) || v instanceof EditText) continue;
            String text = t.getText().toString();
            if (Ui.technical(v)) {
                assertTrue("Technical view carries prose: " + text, TECHNICAL_TEXT.matcher(text).matches());
                // Every token is a hex block or an identifier (digit, '_' or '-'): a prose word fails here.
                for (String token : text.trim().split("\\s+"))
                    assertTrue("Prose word inside technical data: " + token, token.matches("[0-9a-fA-F]{1,8}") || token.matches(".*[0-9_\\-].*"));
                assertNull("Technical data must wrap, never be truncated: " + text, t.getEllipsize());
                assertTrue("Technical data needs a spoken label", t.getContentDescription() != null && t.getContentDescription().length() > 0);
                for (String line : text.split("\n")) assertTrue("Technical line too long to wrap by blocks: " + line, line.length() <= MAX_LINE);
                continue;
            }
            assertNull("English on screen: " + text, SpanishText.englishWord(text));
            CharSequence d = v.getContentDescription();
            if (d != null) assertNull("English in accessibility label: " + d, SpanishText.englishWord(d.toString()));
            if (v instanceof Button) assertTrue("Button too long: " + text, text.length() <= MAX_BUTTON);
            else assertTrue("Line too long: " + text, text.length() <= MAX_LINE);
        }
    }
    private static final int MAX_BUTTON = 24, MAX_LINE = 64;
    /** A real QR has three dark finder squares (top-left, top-right, bottom-left) and a light quiet zone around them. */
    private static void assertQrFinderPatterns(Bitmap qr) {
        int n = qr.getWidth(), dark = 0, light = 0;
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (qr.getPixel(x, y) == Color.BLACK) dark++; else if (qr.getPixel(x, y) == Color.WHITE) light++;
        assertEquals("only black and white modules", n * n, dark + light);
        assertTrue("QR contains modules", dark > n * n / 5 && light > n * n / 5);
        assertEquals("quiet zone is light", Color.WHITE, qr.getPixel(1, 1));
        int[][] corners = {{0, 0}, {n - 1, 0}, {0, n - 1}};
        for (int[] c : corners) {
            int darkNear = 0;
            for (int dy = 0; dy < n / 8; dy++) for (int dx = 0; dx < n / 8; dx++) {
                int x = c[0] == 0 ? n / 16 + dx : n - 1 - n / 16 - dx, y = c[1] == 0 ? n / 16 + dy : n - 1 - n / 16 - dy;
                if (qr.getPixel(x, y) == Color.BLACK) darkNear++;
            }
            assertTrue("finder pattern near corner " + c[0] + "," + c[1], darkNear > (n / 8) * (n / 8) / 4);
        }
        int darkBottomRight = 0;
        for (int dy = 0; dy < n / 16; dy++) for (int dx = 0; dx < n / 16; dx++) if (qr.getPixel(n - 1 - n / 12 - dx, n - 1 - n / 12 - dy) == Color.BLACK) darkBottomRight++;
        assertTrue("no finder pattern in the bottom-right corner", darkBottomRight < (n / 16) * (n / 16));
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
        e.add(new ChatScreens.Entry(null, new ChatScreens.LocationEntry("Reciente · Bruno", "Aproximada · 4.61000, -74.08000 · medida 09:22", true, false)));
        return e;
    }
    private static ChatScreens.ChatState chat(TrustLevel trust, boolean sharing) {
        return new ChatScreens.ChatState(BRUNO, "Bruno", TrustPresentation.of(trust), conversation(), "24 horas", "", sharing,
            "En vivo · Aproximada · hasta 1 hora", FEATURES, !BuildConfig.ALLOW_RELAY, false);
    }
    private static final ChatScreens.ChatActions CHAT = new ChatScreens.ChatActions() {
        public void back() {} public void contact() {} public void verify() {} public void unblock() {} public void voiceCall() {} public void videoCall() {}
        public void openCall() {} public void attach() {} public void send(String t) {} public void draft(String t) {} public void message(MessageItem m) {}
        public void stopLocation() {} public void retry() {} public void openRestricted(String id) {}
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
        View v = render("01-lock", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(true, !BuildConfig.ALLOW_RELAY, null, null), new EntryScreens.LockActions() {
            public void unlock() {} public void openSecuritySettings() {}
        }));
        assertTrue(hasText(v, "UMBRA bloqueado"));
        assertNotNull(button(v, "Desbloquear"));
        assertTrue(hasText(v, "VERSIÓN DE DESARROLLO"));
        assertConcise(v);
        assertAccessible(v);
        View insecure = render("01b-lock-no-device-credential", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(false, false, null, null), new EntryScreens.LockActions() {
            public void unlock() {} public void openSecuritySettings() {}
        }));
        assertNull("no unlock without a device credential", button(insecure, "Desbloquear"));
        assertNotNull(button(insecure, "Configurar bloqueo"));
    }

    @Test public void onboardingIsShortAndLeadsToAdmission() {
        EntryScreens.OnboardingActions a = new EntryScreens.OnboardingActions() { public void step(int n) {} public void create(String s) {} };
        View first = render("02a-onboarding-intro", ui -> EntryScreens.onboarding(ui, 0, !BuildConfig.ALLOW_RELAY, a));
        assertTrue(hasText(first, "Sin teléfono ni correo"));
        assertTrue(hasText(first, "Paso 1 de 3"));
        View identity = render("02b-onboarding-identity", ui -> EntryScreens.onboarding(ui, 1, !BuildConfig.ALLOW_RELAY, a));
        assertTrue(hasText(identity, BuildConfig.ALLOW_RELAY ? "Después: conexión privada" : "Después: contactos"));
        assertFalse("identity creation never claims admission", hasText(identity, "Acceso activo"));
        assertNotNull(button(identity, "Crear identidad"));
        View last = render("02c-onboarding-ready", ui -> EntryScreens.onboarding(ui, 2, !BuildConfig.ALLOW_RELAY, a));
        assertTrue(hasText(last, "Listo"));
        assertTrue("contacts start unverified", hasText(last, "sin verificar"));
        assertEquals(BuildConfig.ALLOW_RELAY, button(last, "Configurar conexión") != null);
        assertNotNull(button(last, "Agregar contacto"));
        for (String banned : new String[]{"realm", "prekey", "credencial", "autoridad"}) assertFalse(banned, visibleText(last).toLowerCase(Locale.ROOT).contains(banned));
        assertAccessible(identity); assertAccessible(last);
        assertConcise(first); assertConcise(identity); assertConcise(last);
    }

    @Test public void homeListsPeopleWithTrustAndNoContentPreview() {
        List<ConversationItem> items = List.of(ConversationItem.direct(ANA, "Ana", TrustLevel.VERIFIED, "09:30"),
            ConversationItem.direct(BRUNO, "Bruno", TrustLevel.IDENTITY_CHANGED, "Ayer"),
            ConversationItem.direct(CARLOS, "Carlos", TrustLevel.UNVERIFIED, ""));
        HomeScreens.ChatsActions a = new HomeScreens.ChatsActions() {
            public void open(ConversationItem i) {} public void newMessage() {} public void newGroup() {} public void addContact() {}
            public void filter(HomeScreens.Filter f) {} public void openIncoming(String id) {} public void networkDetails() {} public void admission() {}
            public void emergency() {}
        };
        View v = render("03-home-chats", ui -> HomeScreens.chats(ui, new HomeScreens.ChatsState(items, false, HomeScreens.Filter.ALL, FEATURES, !BuildConfig.ALLOW_RELAY, OFFLINE_SESSION, null, null, null), a, nav(ui, HomeTab.CHATS)));
        String text = visibleText(v);
        assertTrue(text.contains("Identidad cambió"));
        assertTrue(text.contains("Sin verificar"));
        assertEquals(BuildConfig.ALLOW_RELAY, text.contains("Llamadas"));
        assertAccessible(v);
        View empty = render("17-empty-state", ui -> HomeScreens.chats(ui, new HomeScreens.ChatsState(List.of(), false, HomeScreens.Filter.ALL, FEATURES, !BuildConfig.ALLOW_RELAY, OFFLINE_SESSION, null, null, null), a, nav(ui, HomeTab.CHATS)));
        assertTrue(hasText(empty, "Sin conversaciones"));
        assertNotNull("one primary action", button(empty, "Nueva conversación"));
        assertNotNull("chat list keeps the primary action", button(v, "Nueva conversación"));
        assertConcise(v); assertConcise(empty);
        // Groups have no end-to-end implementation: no group filter, preview or pending marker in the chat list.
        assertFalse(hasExact(v, "Grupos")); assertFalse(hasText(v, "Vista previa")); assertFalse(hasText(v, "Próximamente"));
        render("03c-home-loading", ui -> HomeScreens.chats(ui, new HomeScreens.ChatsState(items, true, HomeScreens.Filter.ALL, FEATURES, !BuildConfig.ALLOW_RELAY, OFFLINE_SESSION, null, null, null), a, nav(ui, HomeTab.CHATS)));
    }

    @Test public void verifiedChatShowsComposerAndContentKinds() {
        View v = render("04-chat-verified", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, false), CHAT));
        assertTrue(hasText(v, "Verificado"));
        boolean composer = false; for (View x : all(v)) if (x instanceof EditText e && "Mensaje".contentEquals(e.getHint())) composer = true;
        assertTrue(composer);
        assertAccessible(v);
        assertConcise(v);
        render("04b-chat-sharing-location", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, true), CHAT));
    }

    @Test public void identityChangeBlocksSendingAndSaysWhy() {
        View v = render("15-chat-identity-changed", ui -> ChatScreens.direct(ui, chat(TrustLevel.IDENTITY_CHANGED, false), CHAT));
        assertTrue(hasText(v, "Verifica de nuevo"));
        assertTrue(hasText(v, "Envío bloqueado"));
        for (View x : all(v)) assertFalse("no composer when identity changed", x instanceof EditText);
        assertFalse(hasText(v, "Verificado"));
        View unverified = render("15b-chat-unverified", ui -> ChatScreens.direct(ui, chat(TrustLevel.UNVERIFIED, false), CHAT));
        for (View x : all(unverified)) assertFalse(x instanceof EditText);
        View blocked = render("15c-chat-blocked", ui -> ChatScreens.direct(ui, chat(TrustLevel.BLOCKED, false), CHAT));
        assertTrue(hasText(blocked, "Bloqueado"));
        assertAccessible(v);
        assertConcise(v); assertConcise(unverified); assertConcise(blocked);
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
        assertTrue(hasText(v, "Vista previa · no se envía"));
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
        View contact = render("06-contact-info", ui -> SecurityScreens.contact(ui, new SecurityScreens.ContactState(BRUNO, "Bruno", TrustPresentation.of(TrustLevel.VERIFIED), 2, 1, FEATURES.visible(Feature.VOICE_CALLS), FEATURES,
            PeerAdmissionPresentation.of("VALID_LOCALLY", "NEARBY_PROOF")), ca));
        assertTrue(hasText(contact, "Verificado"));
        assertTrue(hasExact(contact, "Dispositivos")); assertTrue(hasExact(contact, "2"));
        assertAccessible(contact);
        assertConcise(contact);
        String code = Bytes.safetyCode(ME, BRUNO);
        // The production QR path (the same method the verification screen uses), never ZXing called from the test APK:
        // under R8 the app keeps only what its own code reaches, so this also proves the encoder survived shrinking.
        Bitmap qr;
        try { qr = app.umbra.ui.design.QrCodes.render(app.umbra.verification.Verification.qr(ME, BRUNO), app.umbra.ui.design.QrCodes.SIZE); }
        catch (Exception e) { throw new AssertionError("production QR path failed", e); }
        assertEquals(app.umbra.ui.design.QrCodes.SIZE, qr.getWidth());
        assertQrFinderPatterns(qr);
        SecurityScreens.VerifyActions va = new SecurityScreens.VerifyActions() { public void back() {} public void method(SecurityScreens.Method m) {} public void compare(String c) {} public void technical(boolean s) {} };
        View verify = render("07-verify-code", ui -> SecurityScreens.verify(ui, new SecurityScreens.VerifyState("Bruno", TrustPresentation.of(TrustLevel.UNVERIFIED), code, null, SecurityScreens.Method.CODE, false, "D4D4D4D4", "B2B2B2B2", FEATURES), va));
        assertTrue(hasText(verify, "Sin verificar"));
        assertNotNull(help(verify, Help.VERIFY));
        assertConcise(verify);
        assertFalse(visibleText(verify).toLowerCase(Locale.ROOT).contains("x3dh"));
        assertTrue(visibleText(verify).contains(Fingerprints.group(code).substring(0, 9)));
        assertAccessible(verify);
        Bitmap finalQr = qr;
        View qrScreen = render("07b-verify-qr", ui -> SecurityScreens.verify(ui, new SecurityScreens.VerifyState("Bruno", TrustPresentation.of(TrustLevel.UNVERIFIED), code, finalQr, SecurityScreens.Method.QR, false, "D4D4D4D4", "B2B2B2B2", FEATURES), va));
        boolean qrShown = false;
        for (View x : all(qrScreen)) if (x instanceof android.widget.ImageView iv && "QR del código de seguridad con Bruno".contentEquals(String.valueOf(iv.getContentDescription()))
                && iv.getDrawable() instanceof android.graphics.drawable.BitmapDrawable bd && bd.getBitmap() == finalQr && iv.getWidth() > 0) qrShown = true;
        assertTrue("the verification screen renders the production QR", qrShown);
        assertConcise(qrScreen);
        render("07c-verify-manual-identity-changed", ui -> SecurityScreens.verify(ui, new SecurityScreens.VerifyState("Bruno", TrustPresentation.of(TrustLevel.IDENTITY_CHANGED), code, null, SecurityScreens.Method.MANUAL, true, "D4D4D4D4", "B2B2B2B2", FEATURES), va));
    }

    @Test public void devicesAndLocationStateWhoHowLongAndPrecision() {
        List<DeviceItem> own = List.of(DeviceItem.of(ME, true, true, true), DeviceItem.of("e5".repeat(32), false, true, true), DeviceItem.of("f6".repeat(32), false, false, true));
        View devices = render("08-devices", ui -> DeviceScreens.devices(ui, new DeviceScreens.DevicesState(own, true, null,
            List.of(new DeviceScreens.ContactDevices("Ana", 1), new DeviceScreens.ContactDevices("Bruno", 2), new DeviceScreens.ContactDevices("Carlos", -1)), FEATURES),
            new DeviceScreens.DevicesActions() { public void back() {} public void revoke(DeviceItem d) {} public void add() {} }));
        assertTrue(hasText(devices, "Este dispositivo"));
        assertTrue(hasText(devices, "Revocado"));
        assertConcise(devices);
        for (View x : all(devices)) if (x instanceof Button b && b.getText().toString().startsWith("Revocar")) assertEquals(FEATURES.available(Feature.DEVICE_REVOCATION), b.isEnabled());
        assertAccessible(devices);
        View location = render("09-location-sheet", ui -> Screen.of(null, DeviceScreens.locationSheet(ui, new DeviceScreens.LocationSheetState("Bruno", true, LocationShareDraft.Precision.APPROXIMATE, 1),
            new DeviceScreens.LocationActions() { public void live(boolean l) {} public void precision(LocationShareDraft.Precision p) {} public void duration(int i) {} public void review(String a, String b) {} public void stopAll() {} }), null));
        assertTrue(hasExact(location, "Para"));
        assertTrue(hasExact(location, "Duración"));
        assertTrue(hasExact(location, "Precisión"));
        assertNotNull(help(location, Help.LOCATION));
        assertConcise(location);
        Button stopAll = button(location, "Detener todo"); assertNotNull(stopAll); assertTrue(stopAll.isEnabled());
        View sharing = render("09b-location-stop", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, true), CHAT));
        Button stop = button(sharing, "Detener"); assertNotNull(stop); assertTrue("stop needs no extra password", stop.isEnabled());
    }

    @Test public void callScreenShowsRealTransmissionState() {
        assertEquals(BuildConfig.ALLOW_RELAY, HomeTab.visible(FEATURES).contains(HomeTab.CALLS));
        if (!FEATURES.visible(Feature.VOICE_CALLS)) {
            Navigator nav = new Navigator(FEATURES); nav.home();
            assertFalse("offline has no call destinations", nav.push(Route.of(Route.Kind.CALL, "x")));
            return; // No call screenshots in the offline edition.
        }
        View voice = render("10-call-voice", ui -> CallScreens.call(ui, call("OFF", false, false, false, false), CALL, null));
        assertTrue(hasText(voice, "Voz natural"));
        assertTrue(hasText(voice, "00:42"));
        assertFalse(visibleText(voice).contains("WebRTC"));
        assertAccessible(voice);
        assertConcise(voice);
        View muted = render("10b-call-muted", ui -> CallScreens.call(ui, call("ON", true, false, false, false), CALL, null));
        assertTrue(hasExact(muted, "Silenciado"));
        View pending = render("10c-call-mic-not-authorized", ui -> CallScreens.call(ui, new CallScreens.CallState("c", "Bruno", CallPresentation.of("SELECTED", null), null, false, false, null, false, null, false, false, FEATURES), CALL, null));
        assertNotNull(button(pending, "Autorizar micrófono"));
        View incoming = render("16-incoming-call", ui -> CallScreens.incoming(ui, new CallScreens.IncomingState("c", "Bruno", TrustPresentation.of(TrustLevel.VERIFIED), false),
            new CallScreens.IncomingActions() { public void reject() {} public void answer() {} public void later() {} }));
        assertTrue(hasText(incoming, "Llamada entrante"));
        assertTrue(hasExact(incoming, "Bruno"));
        assertAccessible(incoming);
        assertConcise(incoming);
    }

    @Test public void modulatorNeverClaimsModulationBeforeEngineConfirms() {
        if (!FEATURES.visible(Feature.VOICE_MODULATION)) { assertFalse(FEATURES.available(Feature.VOICE_MODULATION)); return; }
        View enabling = render("11a-modulator-enabling", ui -> CallScreens.call(ui, call("ENABLING", false, false, false, true), CALL, null));
        assertTrue(hasText(enabling, "Activando modulación…"));
        for (View x : all(enabling)) if (x instanceof Button b && "Modulada".contentEquals(b.getText())) { assertFalse(b.isSelected()); assertFalse(b.isEnabled()); }
        View on = render("11b-modulator-on", ui -> CallScreens.call(ui, call("ON", false, false, false, true), CALL, null));
        boolean selected = false; for (View x : all(on)) if (x instanceof Button b && "Modulada".contentEquals(b.getText())) selected = b.isSelected();
        assertTrue(selected);
        assertTrue(hasText(on, "Voz modulada"));
        View failed = render("11c-modulator-error", ui -> CallScreens.call(ui, call("ERROR_MUTED", false, false, false, true), CALL, null));
        assertTrue(hasText(failed, "Modulación fallida"));
        assertTrue(hasText(failed, "Silenciado por fallo"));
        assertNotNull(button(failed, "Reintentar"));
        assertFalse(visibleText(failed).toLowerCase(Locale.ROOT).contains("anónim"));
        assertAccessible(failed);
        assertConcise(failed);
    }

    @Test public void videoCallRequiresConsentPerDirection() {
        if (!FEATURES.visible(Feature.VIDEO_CALLS)) { assertFalse(FEATURES.available(Feature.VIDEO_CALLS)); return; }
        View request = render("12a-video-consent", ui -> CallScreens.call(ui, call("OFF", false, true, true, false), CALL, null));
        assertTrue(hasText(request, "Bruno pide video"));
        assertNotNull(button(request, "Rechazar"));
        assertNotNull(button(request, "Solo recibir"));
        assertNotNull(button(request, "Enviar mi cámara"));
        View video = render("12b-video-call", ui -> CallScreens.call(ui, call("ON", false, true, false, false), CALL, null));
        assertTrue(hasText(video, "Tu cámara transmite"));
        assertAccessible(video);
        assertConcise(request); assertConcise(video);
    }

    @Test public void settingsSectionsAreHonestAboutPendingControls() {
        int[] emergencies = {0};
        HomeScreens.SettingsActions ra = new HomeScreens.SettingsActions() { public void open(SettingsSection s) {} public void lockNow() {} };
        View rootSettings = render("13-settings", ui -> HomeScreens.settings(ui, "Ana", OFFLINE_SESSION, ra, nav(ui, HomeTab.SETTINGS)));
        assertEquals(BuildConfig.ALLOW_RELAY ? "https://relay.egoumbra.sbs" : "", BuildConfig.RELAY_ORIGIN);
        for (SettingsSection s : SettingsSection.values()) if (s != SettingsSection.PROFILE) assertTrue(s.title, hasText(rootSettings, s.title));
        assertAccessible(rootSettings);
        assertConcise(rootSettings);
        SettingsScreens.SettingsActions sa = new SettingsScreens.SettingsActions() {
            public void back() {} public void createInvitation() {} public void importInvitation() {} public void revokeInvitations() {} public void lockNow() {}
            public void destroyIdentity() {} public void expiry(int i) {} public void register(String i) {} public void syncNow() {} public void unregister() {}
            public void connect() {} public void disconnect() {} public void nearby() {} public void changePassword() {} public void enrollPassword() {}
            public void admission() {} public void devices() {} public void emergency() { emergencies[0]++; }
        };
        for (SettingsSection s : new SettingsSection[]{SettingsSection.PROFILE, SettingsSection.PRIVACY, SettingsSection.SECURITY, SettingsSection.NETWORK, SettingsSection.ABOUT}) {
            View v = render("13-settings-" + s.name().toLowerCase(Locale.ROOT), ui -> SettingsScreens.section(ui, new SettingsScreens.SettingsState(s, "Ana", ME, FEATURES,
                !BuildConfig.ALLOW_RELAY, OFFLINE_SESSION, BuildConfig.ALLOW_RELAY, BuildConfig.ALLOW_RELAY ? "https://servidor.ejemplo.test" : "", "24 horas", 1, BuildConfig.VERSION_NAME,
                true, "4 min", AdmissionPresentation.of("ADMITTED", false, false)), sa));
            assertAccessible(v);
            assertConcise(v);
            if (s == SettingsSection.SECURITY) {
                Button emergency = button(v, "Bloqueo de emergencia"); assertNotNull(emergency);
                assertTrue("emergency lock is a real action now", FEATURES.available(Feature.EMERGENCY_LOCK) && emergency.isEnabled());
                InstrumentationRegistry.getInstrumentation().runOnMainSync(emergency::performClick);
                assertEquals("one tap, no password dialog in between", 1, emergencies[0]);
            }
            if (s == SettingsSection.PROFILE) assertFalse(visibleText(v).toLowerCase(Locale.ROOT).contains("private key"));
            if (s == SettingsSection.ABOUT) {
                // The real build version, flavor suffix included ("0.2.0-dev-offline"), is shown whole as a technical
                // identifier with a Spanish spoken label; it is not human copy and is never shortened.
                boolean version = false;
                for (View x : all(v)) if (x instanceof TextView t && BuildConfig.VERSION_NAME.contentEquals(t.getText())) {
                    assertTrue("version is tagged as a technical identifier", Ui.technical(t));
                    assertEquals("Versión " + BuildConfig.VERSION_NAME, String.valueOf(t.getContentDescription()));
                    version = true;
                }
                assertTrue("full build version shown", version);
                assertTrue("product name is human copy", hasExact(v, "UMBRA"));
            }
        }
    }

    // ------------------------------------------------------------------ PAIRING_PRODUCT_V1 (synthetic data only)
    private static final PairingScreens.FlowActions FLOW = new PairingScreens.FlowActions() {
        public void close() {} public void cancelPairing() {} public void retry() {} public void verify() {} public void later() {}
        public void submitCode(EditText[] g) {} public void requestCamera() {} public void enterCodeInstead() {}
        public void scannerSurface(android.view.TextureView v) { fail("the camera must not open in a render test"); }
        public void createFile() {} public void pickFile() {} public void saveResponse() {}
    };
    private static app.umbra.pairing.PairingSnapshot pairingSnap(app.umbra.pairing.PairingSnapshot.Role role, app.umbra.pairing.PairingSnapshot.Phase phase,
                                                              app.umbra.pairing.PairingException.Code failure, boolean verify) {
        return new app.umbra.pairing.PairingSnapshot("synthetic", role, phase, app.umbra.pairing.PairingSnapshot.NextAction.WAITING_FOR_PEER, 582, failure,
            phase == app.umbra.pairing.PairingSnapshot.Phase.COMPLETE ? BRUNO : null, verify);
    }
    private static PairingScreens.Flow flow(PairingScreens.Mode mode, app.umbra.pairing.PairingSnapshot snap, String busy, String problem, boolean retry,
                                            Bitmap qr, char[] code, String scanState, boolean denied, PairingScreens.FileStage file) {
        return new PairingScreens.Flow(mode, snap, busy, problem, retry, qr, code, snap == null ? null : PairingPresentation.validFor(snap.expiresInSeconds()),
            scanState, denied, file);
    }
    private static void assertNoProtocolTerms(View v) {
        String text = visibleText(v).toLowerCase(Locale.ROOT);
        for (String banned : new String[]{"invite", "request", "ack", "realm", "capability", "prekey", "token", "rendezvous", "relay"})
            assertFalse("protocol term on screen: " + banned, text.contains(banned));
    }

    @Test public void addContactOffersScanCodeQrAndFileWithoutProtocolTerms() {
        PairingScreens.EntryActions ea = new PairingScreens.EntryActions() {
            public void scan() {} public void enterCode() {} public void showQr() {} public void showCode() {} public void file() {} public void nearby() {} public void configure() {}
        };
        assertEquals("camera scanner only where the manifest has CAMERA", BuildConfig.ALLOW_RELAY, app.umbra.ui.media.QrScanner.AVAILABLE);
        View ready = render("30a-add-contact", ui -> Screen.of(null, PairingScreens.addContact(ui, new PairingScreens.Entry(app.umbra.ui.media.QrScanner.AVAILABLE, BuildConfig.ALLOW_RELAY, null), ea), null));
        assertTrue(hasText(ready, "Agregar contacto"));
        assertTrue(hasText(ready, "Archivo de vinculación"));
        assertEquals(BuildConfig.ALLOW_RELAY, hasText(ready, "Escanear QR"));
        assertEquals(BuildConfig.ALLOW_RELAY, hasText(ready, "Ingresar código"));
        assertEquals(BuildConfig.ALLOW_RELAY, hasText(ready, "Mostrar mi QR"));
        assertNoProtocolTerms(ready); assertAccessible(ready); assertConcise(ready);
        if (BuildConfig.ALLOW_RELAY) {
            View notReady = render("30b-add-contact-no-private-connection", ui -> Screen.of(null, PairingScreens.addContact(ui,
                new PairingScreens.Entry(true, true, PairingPresentation.ONLINE_UNAVAILABLE), ea), null));
            assertTrue(hasText(notReady, "Conexión privada no disponible"));
            assertTrue("online pairing is never faked", hasText(notReady, "Configurar"));
            for (View x : all(notReady)) if (x instanceof LinearLayout row && String.valueOf(row.getContentDescription()).startsWith("Escanear QR"))
                assertFalse("not clickable while the private connection is missing", row.isClickable());
            assertConcise(notReady);
        }
    }

    @Test public void pairingQrAndCodeAreShownWithoutRevealingThemAsText() {
        Bitmap qr = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) qr.setPixel(x, y, ((x / 8 + y / 8) % 2 == 0) ? Color.BLACK : Color.WHITE);
        app.umbra.pairing.PairingSnapshot waiting = pairingSnap(app.umbra.pairing.PairingSnapshot.Role.INVITER, app.umbra.pairing.PairingSnapshot.Phase.INVITE_CREATED, null, true);
        View showQr = render("31a-pairing-show-qr", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.SHOW_QR, waiting, null, null, false, qr, null, null, false, null), FLOW));
        boolean described = false;
        for (View x : all(showQr)) if (x instanceof android.widget.ImageView iv && "Código QR para agregar este contacto".contentEquals(String.valueOf(iv.getContentDescription()))) described = true;
        assertTrue("QR is described without reading its payload", described);
        assertTrue(hasText(showQr, "Válido durante 9:42"));
        assertTrue(hasText(showQr, "Esperando al otro dispositivo…"));
        assertNotNull(button(showQr, "Cancelar vinculación"));
        assertNoProtocolTerms(showQr); assertAccessible(showQr); assertConcise(showQr);
        char[] code = "ABCDEFGHJKMNPQRS".toCharArray();
        View showCode = render("31b-pairing-show-code", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.SHOW_CODE, waiting, null, null, false, null, code, null, false, null), FLOW));
        TextView shown = null;
        for (View x : all(showCode)) if (x instanceof TextView t && "ABCD-EFGH-JKMN-PQRS".contentEquals(t.getText())) shown = t;
        assertNotNull("code shown in four groups", shown);
        assertTrue("a value, not copy", Ui.technical(shown));
        assertFalse("never selectable or copyable", shown.isTextSelectable() || shown.isLongClickable());
        assertEquals("A B C D, E F G H, J K M N, P Q R S", String.valueOf(shown.getContentDescription()));
        assertTrue(hasText(showCode, "Un solo uso"));
        assertConcise(showCode); assertAccessible(showCode);
        View preparing = render("31c-pairing-preparing", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.SHOW_QR, null, "Preparando…", null, false, null, null, null, false, null), FLOW));
        assertTrue(hasText(preparing, "Preparando"));
    }

    @Test public void pairingEntryScanAndResultsAreHonest() {
        View enter = render("32a-pairing-enter-code", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.ENTER_CODE, null, null, null, false, null, null, null, false, null), FLOW));
        int groups = 0;
        for (EditText e : fields(enter)) {
            groups++;
            assertTrue("visible uppercase input, no suggestions", (e.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);
            assertFalse(e.isSaveEnabled());
        }
        assertEquals(HumanCodeInput.GROUPS, groups);
        assertNotNull(button(enter, "Continuar"));
        assertConcise(enter); assertAccessible(enter);
        View working = render("32b-pairing-code-working", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.ENTER_CODE, null, "Verificando código…", null, false, null, null, null, false, null), FLOW));
        Button busy = button(working, "Verificando código…");
        assertNotNull("the button itself shows the real phase", busy);
        assertFalse("double taps are ignored while busy", busy.isEnabled());
        View denied = render("32c-pairing-camera-denied", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.SCAN, null, null, null, false, null, null, "Permite la cámara para escanear.", true, null), FLOW));
        assertTrue(hasText(denied, "Permite la cámara"));
        assertNotNull(button(denied, "Permitir cámara"));
        assertNotNull("always an alternative to the camera", button(denied, "Ingresar código"));
        app.umbra.pairing.PairingSnapshot added = pairingSnap(app.umbra.pairing.PairingSnapshot.Role.JOINER, app.umbra.pairing.PairingSnapshot.Phase.COMPLETE, null, true);
        View done = render("32d-pairing-added-unverified", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.ENTER_CODE, added, null, null, false, null, null, null, false, null), FLOW));
        assertTrue(hasText(done, "Contacto agregado"));
        assertTrue(hasText(done, "Verificación pendiente"));
        assertNotNull(button(done, "Verificar ahora"));
        assertNotNull(button(done, "Más tarde"));
        String text = visibleText(done).toLowerCase(Locale.ROOT);
        assertFalse("pairing never claims verification", text.contains("verificado") || text.contains("seguro"));
        assertConcise(done); assertAccessible(done);
        View expired = render("32e-pairing-expired", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.SHOW_CODE, null, null,
            PairingPresentation.failure(app.umbra.pairing.PairingException.Code.EXPIRED), false, null, null, null, false, null), FLOW));
        assertTrue(hasText(expired, "Este código venció."));
        assertNull("no retry for a typed terminal failure", button(expired, "Intentar de nuevo"));
        View unavailable = render("32f-pairing-unavailable", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.SHOW_QR, null, null,
            PairingPresentation.failure(app.umbra.pairing.PairingException.Code.UNAVAILABLE), true, null, null, null, false, null), FLOW));
        assertNotNull(button(unavailable, "Intentar de nuevo"));
        View file = render("32g-pairing-file", ui -> PairingScreens.flow(ui, flow(PairingScreens.Mode.FILE, null, null, null, false, null, null, null, false, PairingScreens.FileStage.START), FLOW));
        assertNotNull(button(file, "Seleccionar archivo"));
        assertNotNull(button(file, "Crear archivo"));
        assertNoProtocolTerms(file); assertConcise(file);
    }

    @Test public void accessPhasesShowRealWorkAndLockReasons() {
        EntryScreens.LockActions la = new EntryScreens.LockActions() { public void unlock() {} public void openSecuritySettings() {} };
        View locked = render("33a-lock-after-autolock", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(true, !BuildConfig.ALLOW_RELAY, null, null,
            AccessPresentation.lockReason(app.umbra.core.AccessGate.LockCause.AUTOLOCK), false), la));
        assertTrue(hasText(locked, "Se bloqueó por tiempo."));
        View authenticating = render("33b-lock-authenticating", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(true, !BuildConfig.ALLOW_RELAY, null, null, null, true), la));
        Button waiting = button(authenticating, "Esperando a Android…");
        assertNotNull(waiting); assertFalse(waiting.isEnabled());
        AccessScreens.UnlockActions ua = new AccessScreens.UnlockActions() { public void submit(EditText p) {} public void autoLock(int i) {} public void lockNow() {} };
        View opening = render("33c-unlock-working", ui -> AccessScreens.unlock(ui, new AccessScreens.UnlockState(true, null, 2, !BuildConfig.ALLOW_RELAY), ua));
        Button open = button(opening, "Abriendo…");
        assertNotNull("unlock shows its phase on the button", open); assertFalse(open.isEnabled());
        assertNotNull("the selector says the ceiling is a maximum", button(opening, "4 min máx."));
        AccessScreens.CreateActions ca = new AccessScreens.CreateActions() { public void submit(EditText p, EditText c) {} public void later() {} public void lockNow() {} };
        View creating = render("33d-create-working", ui -> AccessScreens.create(ui, new AccessScreens.CreateState(false, true, null), ca));
        assertNotNull(button(creating, "Creando protección…"));
        assertConcise(locked); assertConcise(opening); assertConcise(creating);
        assertAccessible(opening);
    }

    @Test public void offlineEditionShowsBluetoothIdentityAndNoInternetFeatures() {
        HomeScreens.NearbyActions na = new HomeScreens.NearbyActions() {
            public void startNearby() {} public void stopNearby() {} public void listen() {} public void makeVisible() {} public void connectVerified() {}
            public void enrollNew() {} public void systemSettings() {} public void networkSettings() {}
        };
        View nearby = render("14-offline-nearby", ui -> HomeScreens.nearby(ui, new HomeScreens.NearbyState("Sin enlace", !BuildConfig.ALLOW_RELAY, OFFLINE_SESSION, true, true), na, nav(ui, HomeTab.NEARBY)));
        assertAccessible(nearby);
        assertTrue(hasText(nearby, "Cercanía activa"));
        assertNotNull(button(nearby, "Detener cercanía"));
        assertNotNull(help(nearby, Help.NEARBY));
        assertConcise(nearby);
        if (!BuildConfig.ALLOW_RELAY) {
            assertFalse(hasText(nearby, "Llamadas"));
            assertFalse(hasExact(nearby, "Red")); // No Internet control where Internet does not exist.
            View chat = render("14b-offline-chat", ui -> ChatScreens.direct(ui, chat(TrustLevel.VERIFIED, false), CHAT));
            for (View x : all(chat)) assertFalse("no call buttons offline", "Llamada de voz".contentEquals(String.valueOf(x.getContentDescription())) || "Videollamada".contentEquals(String.valueOf(x.getContentDescription())));
        } else assertTrue(hasExact(nearby, "Red"));
    }

    // ------------------------------------------------------------------ vault password, admission and connectivity
    private static List<EditText> fields(View root) {
        List<EditText> out = new ArrayList<>(); for (View v : all(root)) if (v instanceof EditText e) out.add(e); return out;
    }
    /** Secret inputs: password type, labelled, never autofilled or saved, and the typed value never reaches accessibility text. */
    private static void assertSecretInputs(View root, int expected) {
        List<EditText> secrets = new ArrayList<>();
        for (EditText e : fields(root)) if ((e.getInputType() & android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0) secrets.add(e);
        assertEquals(expected, secrets.size());
        for (EditText e : secrets) {
            assertFalse(e.isSaveEnabled());
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, e.getImportantForAutofill());
            boolean labelled = false;
            for (View v : all(root)) if (v instanceof TextView t && !(v instanceof EditText) && t.getLabelFor() == e.getId()) labelled = true;
            assertTrue("secret field has a visible label", labelled);
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { for (EditText e : secrets) e.setText("synthetic-typed-value"); });
        for (View v : all(root)) {
            CharSequence d = v.getContentDescription();
            assertFalse("secret leaked to accessibility", d != null && d.toString().contains("synthetic-typed-value"));
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { for (EditText e : secrets) e.getText().clear(); });
    }

    @Test public void passwordScreensLabelSecretsAndOfferNoRecoveryOrReset() {
        AccessScreens.CreateActions ca = new AccessScreens.CreateActions() { public void submit(EditText p, EditText c) {} public void later() {} public void lockNow() {} };
        View create = render("24a-password-create", ui -> AccessScreens.create(ui, new AccessScreens.CreateState(false, false, null), ca));
        assertTrue(hasText(create, "Nueva contraseña"));
        assertTrue(hasText(create, "Sin recuperación"));
        assertNotNull(button(create, "Crear"));
        assertNull("no skip on a new install", button(create, "Ahora no"));
        assertNotNull(help(create, Help.ACCESS));
        assertConcise(create);
        assertSecretInputs(create, 2);
        assertAccessible(create);
        View legacy = render("24b-password-enroll-legacy", ui -> AccessScreens.create(ui, new AccessScreens.CreateState(true, false, PasswordPolicy.Problem.MISMATCH.message), ca));
        assertNotNull(button(legacy, "Inscribir"));
        assertNotNull(button(legacy, "Ahora no"));
        assertTrue(hasText(legacy, "No coinciden."));
        assertConcise(legacy);
        AccessScreens.UnlockActions ua = new AccessScreens.UnlockActions() { public void submit(EditText p) {} public void autoLock(int i) {} public void lockNow() {} };
        View unlock = render("24c-password-unlock", ui -> AccessScreens.unlock(ui, new AccessScreens.UnlockState(false, AccessStep.UNLOCK_FAILED, 2, !BuildConfig.ALLOW_RELAY), ua));
        assertTrue(hasText(unlock, AccessStep.UNLOCK_FAILED));
        assertEquals("unlocking never connects (said once, connected only)", BuildConfig.ALLOW_RELAY, hasText(unlock, "Abrir no conecta"));
        assertTrue(Help.NETWORK.lines.contains("Desbloquear no conecta."));
        assertNotNull(help(unlock, Help.AUTO_LOCK));
        assertConcise(unlock);
        for (String label : PasswordPolicy.AUTO_LOCK_LABELS) assertNotNull(label, button(unlock, label));
        assertNull("no five-minute option in v1", button(unlock, "5 min"));
        assertSecretInputs(unlock, 1);
        assertAccessible(unlock);
        View busy = render("24d-password-unlock-busy", ui -> AccessScreens.unlock(ui, new AccessScreens.UnlockState(true, null, 2, !BuildConfig.ALLOW_RELAY), ua));
        Button opening = button(busy, "Abriendo…");
        assertNotNull("the submit button shows the real phase while the domain works", opening);
        assertFalse("double submission prevented while the domain works", opening.isEnabled());
        assertNull(button(busy, "Abrir"));
        AccessScreens.ChangeActions cha = new AccessScreens.ChangeActions() { public void back() {} public void submit(EditText a, EditText b, EditText c) {} };
        View change = render("24e-password-change", ui -> AccessScreens.change(ui, new AccessScreens.ChangeState(false, null), cha));
        assertNotNull(button(change, "Cambiar"));
        assertConcise(change);
        assertSecretInputs(change, 3);
        assertAccessible(change);
        for (AccessStep failure : new AccessStep[]{AccessStep.CORRUPT, AccessStep.KEY_UNAVAILABLE}) {
            View v = render("24f-vault-" + failure.name().toLowerCase(Locale.ROOT), ui -> AccessScreens.failure(ui, failure, () -> {}));
            String text = visibleText(v).toLowerCase(Locale.ROOT);
            assertFalse(text.contains("restablecer")); assertFalse(text.contains("recuperar"));
            for (View x : all(v)) if (x instanceof Button b) assertEquals("only lock is offered", "Bloquear", b.getText().toString());
        }
    }

    private static final String FP = "e5f6".repeat(16), REALM = "SyntheticRealmIdentifier_0123456789abcdefghij";
    private static AdmissionScreens.AdmissionState admissionState(String state, boolean request, boolean expired) {
        AdmissionScreens.RequestInfo info = request ? new AdmissionScreens.RequestInfo(Fingerprints.lines(FP, 4), Fingerprints.lines(ME, 4), REALM, "27/09 10:40", expired) : null;
        boolean configured = !"UNCONFIGURED".equals(state);
        return new AdmissionScreens.AdmissionState(AdmissionPresentation.of(state, request, expired), configured ? REALM : null, configured ? Fingerprints.lines(FP, 4) : null,
            Fingerprints.lines(ME, 4), info, "ADMITTED".equals(state) ? "03/10 10:30" : null, !BuildConfig.ALLOW_RELAY, false, "ADMITTED".equals(state));
    }
    @Test public void admissionScreensKeepEveryStateDistinctFromVerification() {
        AdmissionScreens.AdmissionActions aa = new AdmissionScreens.AdmissionActions() {
            public void back() {} public void importFile() {} public void createRequest() {} public void exportRequest() {} public void admin() {}
            public void cancelRequest() {}
        };
        String[][] cases = {{"UNCONFIGURED", "0", "0"}, {"NOT_ADMITTED", "0", "0"}, {"REQUEST_PENDING", "1", "0"}, {"REJECTED", "0", "0"},
            {"ADMITTED", "0", "0"}, {"EXPIRED", "1", "1"}, {"EXPIRED", "0", "0"}, {"REVOKED", "0", "0"}, {"INVALID", "0", "0"}};
        for (String[] c : cases) {
            boolean request = c[1].equals("1"), expired = c[2].equals("1");
            AdmissionPresentation p = AdmissionPresentation.of(c[0], request, expired);
            View v = render("25-admission-" + c[0].toLowerCase(Locale.ROOT) + (request ? "-request" : ""), ui -> AdmissionScreens.status(ui, admissionState(c[0], request, expired), aa));
            assertTrue(c[0], hasText(v, p.title()));
            assertFalse(c[0] + " never shown as verification", hasText(v, "Verificado"));
            assertNotNull(help(v, Help.ADMISSION));
            assertAccessible(v);
            assertConcise(v);
            if (c[0].equals("REVOKED") || c[0].equals("INVALID"))
                for (View x : all(v)) if (x instanceof Button b) assertFalse(c[0] + " offers no bypass: " + b.getText(),
                    b.getText().toString().startsWith("Generar") || b.getText().toString().startsWith("Importar"));
        }
        View pending = render("25-admission-request_pending-detail", ui -> AdmissionScreens.status(ui, admissionState("REQUEST_PENDING", true, false), aa));
        assertTrue(hasText(pending, "Generada · no recibida."));
        assertNotNull(button(pending, "Exportar solicitud"));
        assertNotNull(button(pending, "Importar respuesta"));
        assertNull("no local approval", button(pending, "Aprobar"));
        View unconfigured = render("25-admission-unconfigured-detail", ui -> AdmissionScreens.status(ui, admissionState("UNCONFIGURED", false, false), aa));
        assertTrue(hasText(unconfigured, AdmissionPresentation.of("UNCONFIGURED", false, false).body()));
        assertTrue(Help.ADMISSION.lines.contains("Configurar el entorno no admite."));
        AdmissionScreens.AdminActions ad = new AdmissionScreens.AdminActions() {
            public void back() {} public void createRealm() {} public void exportRealm() {} public void reviewRequest() {} public void revokeCredential() {}
        };
        View admin = render("26a-admission-admin", ui -> AdmissionScreens.admin(ui, new AdmissionScreens.AdminState(true, REALM, Fingerprints.lines(FP, 4), false, true,
            List.of(new AdmissionScreens.IssuedRow("A1A1 A1A1", "27/09 10:40", "04/10 10:40", false, false),
                new AdmissionScreens.IssuedRow("B2B2 B2B2", "20/09 09:00", "21/09 09:00", true, true))), ad));
        assertNotNull(help(admin, Help.ADMIN));
        assertTrue(Help.ADMIN.lines.contains("El motor lo comprueba en cada operación."));
        assertNotNull(button(admin, "Revisar solicitud"));
        assertAccessible(admin);
        assertConcise(admin);
        View create = render("26b-admission-admin-create", ui -> AdmissionScreens.admin(ui, new AdmissionScreens.AdminState(false, null, null, false, false, List.of()), ad));
        assertTrue(hasText(create, "Sin recuperación ni rotación"));
        View review = render("26c-admission-review", ui -> Screen.of(null, AdmissionScreens.reviewSheet(ui, new AdmissionScreens.ReviewInfo(Fingerprints.lines(FP, 4),
            Fingerprints.lines(ANA, 4), REALM, "27/09 10:40", false), new AdmissionScreens.ReviewActions() { public void approve(long t) {} public void reject() {} public void cancel() {} }), null));
        assertTrue(hasText(review, Fingerprints.lines(FP, 4)));
        assertTrue(hasText(review, REALM));
        assertNotNull(button(review, "Aprobar 24 h"));
        assertNotNull(button(review, "Aprobar 7 d"));
        assertNotNull(button(review, "Rechazar"));
        assertAccessible(review);
        assertConcise(review);
        View revoke = render("26d-admission-revoke", ui -> Screen.of(null, AdmissionScreens.revokeSheet(ui, new AdmissionScreens.RevokeInfo(Fingerprints.lines(ANA, 4), "03/10 10:30"),
            new AdmissionScreens.RevokeActions() { public void revoke(String r) {} public void cancel() {} }), null));
        assertTrue(hasText(revoke, "No borra datos ya entregados"));
        assertFalse(visibleText(revoke).toLowerCase(Locale.ROOT).contains("datos eliminados"));
        assertAccessible(revoke);
        assertConcise(revoke);
    }

    @Test public void networkAndSecuritySettingsReflectOnlyDomainConsent() {
        SettingsScreens.SettingsActions sa = new SettingsScreens.SettingsActions() {
            public void back() {} public void createInvitation() {} public void importInvitation() {} public void revokeInvitations() {} public void lockNow() {}
            public void destroyIdentity() {} public void expiry(int i) {} public void register(String i) {} public void syncNow() {} public void unregister() {}
            public void connect() {} public void disconnect() {} public void nearby() {} public void changePassword() {} public void enrollPassword() {}
            public void admission() {} public void devices() {} public void emergency() {}
        };
        java.util.function.BiFunction<ConnectivityPresentation, Boolean, SettingsScreens.SettingsState> state = (c, admitted) -> new SettingsScreens.SettingsState(
            SettingsSection.NETWORK, "Ana", ME, FEATURES, !BuildConfig.ALLOW_RELAY, c, false, BuildConfig.ALLOW_RELAY ? "https://servidor.ejemplo.test" : "", "24 horas", 1,
            BuildConfig.VERSION_NAME, true, "4 min", AdmissionPresentation.of(admitted ? "ADMITTED" : "NOT_ADMITTED", false, false));
        ConnectivityPresentation.Service none = ConnectivityPresentation.Service.NOT_OBSERVED;
        View offline = render("27a-network-unlocked-offline", ui -> SettingsScreens.section(ui, state.apply(OFFLINE_SESSION, true), sa));
        assertAccessible(offline);
        if (!BuildConfig.ALLOW_RELAY) {
            assertNull("no network control without INTERNET", button(offline, "Conectar"));
            assertTrue(hasText(offline, "Edición sin internet"));
        } else {
            assertTrue(button(offline, "Conectar").isEnabled());
            assertNotNull(help(offline, Help.NETWORK));
            assertFalse("normal settings expose no server address", visibleText(offline).contains("servidor.ejemplo.test"));
            assertFalse("normal settings expose no address field", visibleText(offline).contains("Dirección"));
            assertEquals("only the administrator invitation secret remains editable", 1, fields(offline).size());
            assertSecretInputs(offline, 1);
            assertConcise(offline);
            View notAdmitted = render("27b-network-not-admitted", ui -> SettingsScreens.section(ui,
                state.apply(ConnectivityPresentation.of("UNLOCKED_OFFLINE", false, false, false, true, none), false), sa));
            assertFalse(button(notAdmitted, "Conectar").isEnabled());
            assertFalse(button(notAdmitted, "Conectar y registrar").isEnabled());
            View connected = render("27c-network-connected", ui -> SettingsScreens.section(ui,
                state.apply(ConnectivityPresentation.of("CONNECTED", false, false, false, true, ConnectivityPresentation.Service.RESPONDED), true), sa));
            assertNotNull(button(connected, "Desconectar"));
            assertTrue(hasText(connected, "Red habilitada"));
            assertTrue(Help.NETWORK.lines.contains("Desconectar no bloquea la bóveda."));
            assertConcise(connected);
            assertFalse(hasText(connected, "Conectado · servidor privado"));
            View error = render("27d-network-error", ui -> SettingsScreens.section(ui,
                state.apply(ConnectivityPresentation.of("OFFLINE_ERROR", false, true, false, true, ConnectivityPresentation.Service.UNREACHABLE), true), sa));
            assertTrue(hasText(error, "No reconecta sola."));
            assertNotNull(button(error, "Conectar"));
        }
        View security = render("27e-settings-security-password", ui -> SettingsScreens.section(ui, new SettingsScreens.SettingsState(SettingsSection.SECURITY, "Ana", ME, FEATURES,
            !BuildConfig.ALLOW_RELAY, OFFLINE_SESSION, true, "", "24 horas", 1, BuildConfig.VERSION_NAME, true, "2 min", AdmissionPresentation.of("REQUEST_PENDING", true, false)), sa));
        assertTrue(hasText(security, "Contraseña activa"));
        assertNotNull(button(security, "Cambiar contraseña"));
        assertTrue(hasExact(security, "Bloqueo automático")); assertTrue(hasExact(security, "2 min"));
        assertTrue(Help.AUTO_LOCK.lines.contains("No es el bloqueo de emergencia."));
        assertTrue(hasExact(security, "Solicitando acceso"));
        assertNotNull("security explains the auto-lock ceiling", help(security, Help.AUTO_LOCK));
        assertAccessible(security);
        assertConcise(security);
        View legacy = render("27f-settings-security-legacy", ui -> SettingsScreens.section(ui, new SettingsScreens.SettingsState(SettingsSection.SECURITY, "Ana", ME, FEATURES,
            !BuildConfig.ALLOW_RELAY, OFFLINE_SESSION, true, "", "24 horas", 1, BuildConfig.VERSION_NAME, false, "4 min", AdmissionPresentation.of("UNCONFIGURED", false, false)), sa));
        assertNotNull(button(legacy, "Añadir contraseña"));
        assertNull(button(legacy, "Cambiar contraseña"));
    }

    // ------------------------------------------------------------------ contract UI_SECURITY_CONTENT_API_V1 screens
    private static app.umbra.core.EmergencyLock.Status emergencyStatus(app.umbra.core.EmergencyLock.State state, app.umbra.core.EmergencyLock.Outcome... outcomes) {
        List<app.umbra.core.EmergencyLock.Result> results = new ArrayList<>();
        app.umbra.core.EmergencyLock.Subsystem[] subsystems = {app.umbra.core.EmergencyLock.Subsystem.VAULT, app.umbra.core.EmergencyLock.Subsystem.DOCUMENTS,
            app.umbra.core.EmergencyLock.Subsystem.NEARBY, app.umbra.core.EmergencyLock.Subsystem.MEDIA};
        for (int i = 0; i < outcomes.length; i++) results.add(new app.umbra.core.EmergencyLock.Result(subsystems[i], outcomes[i], 1));
        return new app.umbra.core.EmergencyLock.Status(state, 1, 2, 3, results);
    }
    @Test public void emergencyStatusOffersUnlockOnlyAfterConfirmedClosure() {
        int[] unlocks = {0};
        View closing = render("28a-emergency-closing", ui -> EntryScreens.emergencyStatus(ui, EmergencyPresentation.of(emergencyStatus(app.umbra.core.EmergencyLock.State.CLOSING,
            app.umbra.core.EmergencyLock.Outcome.CLOSED, app.umbra.core.EmergencyLock.Outcome.CLOSING)), () -> unlocks[0]++));
        assertTrue(hasText(closing, "Cerrando…"));
        assertNull("no unlock while closing", button(closing, "Desbloquear"));
        assertNotNull(help(closing, Help.EMERGENCY));
        assertConcise(closing); assertAccessible(closing);
        View closed = render("28b-emergency-closed", ui -> EntryScreens.emergencyStatus(ui, EmergencyPresentation.of(emergencyStatus(app.umbra.core.EmergencyLock.State.CLOSED,
            app.umbra.core.EmergencyLock.Outcome.CLOSED, app.umbra.core.EmergencyLock.Outcome.CLOSED, app.umbra.core.EmergencyLock.Outcome.CLOSED)), () -> unlocks[0]++));
        assertTrue(hasText(closed, "Cierre confirmado"));
        Button unlock = button(closed, "Desbloquear"); assertNotNull(unlock);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(unlock::performClick);
        assertEquals(1, unlocks[0]);
        assertConcise(closed); assertAccessible(closed);
        View incomplete = render("28c-emergency-incomplete", ui -> EntryScreens.emergencyStatus(ui, EmergencyPresentation.of(emergencyStatus(app.umbra.core.EmergencyLock.State.INCOMPLETE,
            app.umbra.core.EmergencyLock.Outcome.CLOSED, app.umbra.core.EmergencyLock.Outcome.TIMED_OUT)), () -> unlocks[0]++));
        assertTrue(hasText(incomplete, "Cierre incompleto"));
        assertTrue(hasText(incomplete, "Sin confirmar"));
        assertNull("INCOMPLETE keeps access denied", button(incomplete, "Desbloquear"));
        assertFalse(visibleText(incomplete).toLowerCase(Locale.ROOT).contains("borrad"));
        assertConcise(incomplete);
    }
    @Test public void protectedContentSheetOffersDomainFormatsModesAndExpiryOnly() {
        List<RestrictedPresentation.Kind> picked = new ArrayList<>();
        ContentScreens.SendActions actions = new ContentScreens.SendActions() {
            public void mode(String m) {} public void ttl(int i) {} public void session(int i) {} public void pick(RestrictedPresentation.Kind k) { picked.add(k); } public void capture() {}
        };
        View sheet = render("29a-protected-send-sheet", ui -> Screen.of(null, ContentScreens.sendSheet(ui, new ContentScreens.SendState("Bruno",
            RestrictedPresentation.defaultChoice(), FEATURES.available(Feature.RESTRICTED_CAPTURE), false), actions), null));
        for (RestrictedPresentation.Kind k : RestrictedPresentation.Kind.values()) assertTrue(k.label, hasText(sheet, k.label));
        assertEquals("capture only where the edition declares the microphone", BuildConfig.ALLOW_RELAY, hasText(sheet, "Grabar nota"));
        assertNotNull(button(sheet, "Una vez")); assertNotNull(button(sheet, "Solo en UMBRA"));
        for (String t : RestrictedPresentation.TTL_LABELS) assertNotNull(t, button(sheet, t));
        for (String t : RestrictedPresentation.SESSION_LABELS) assertNotNull(t, button(sheet, t));
        assertNotNull(help(sheet, Help.PROTECTED));
        for (View x : all(sheet)) if (x.isClickable() && x.getContentDescription() != null && x.getContentDescription().toString().startsWith("Foto protegido"))
            InstrumentationRegistry.getInstrumentation().runOnMainSync(x::performClick);
        assertEquals(List.of(RestrictedPresentation.Kind.PHOTO), picked);
        assertConcise(sheet); assertAccessible(sheet);
        View capture = render("29b-protected-capture", ui -> Screen.of(null, ContentScreens.captureSheet(ui, new ContentScreens.CaptureState(true, "Micrófono activo"),
            new ContentScreens.CaptureActions() { public void stop() {} public void cancel() {} }), null));
        assertNotNull(button(capture, "Detener")); assertNotNull(button(capture, "Descartar"));
        assertConcise(capture);
    }
    @Test public void receivedProtectedObjectsShowDomainStateAndOpenOnlyWhenAvailable() {
        List<String> opened = new ArrayList<>();
        ChatScreens.ChatActions actions = new ChatScreens.ChatActions() {
            public void back() {} public void contact() {} public void verify() {} public void unblock() {} public void voiceCall() {} public void videoCall() {}
            public void openCall() {} public void attach() {} public void send(String t) {} public void draft(String t) {} public void message(MessageItem m) {}
            public void stopLocation() {} public void retry() {} public void openRestricted(String id) { opened.add(id); }
        };
        List<ChatScreens.Entry> entries = new ArrayList<>(conversation());
        entries.add(new ChatScreens.Entry(null, null, RestrictedPresentation.received("r-open", "PNG", "ONCE", false, false, "27/09 22:40")));
        entries.add(new ChatScreens.Entry(null, null, RestrictedPresentation.received("r-used", "PDF_PAGES", "ONCE", true, false, "27/09 22:40")));
        entries.add(new ChatScreens.Entry(null, null, RestrictedPresentation.received("r-old", "AVC_MP4", "UMBRA_ONLY", false, true, "26/09 10:00")));
        View v = render("30-chat-protected-objects", ui -> ChatScreens.direct(ui, new ChatScreens.ChatState(BRUNO, "Bruno", TrustPresentation.of(TrustLevel.VERIFIED), entries,
            "24 horas", "", false, "", FEATURES, !BuildConfig.ALLOW_RELAY, false), actions));
        String text = visibleText(v);
        assertTrue(text.contains("Disponible")); assertTrue(text.contains("Ya abierto")); assertTrue(text.contains("Caducado"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("visto"));
        for (View x : all(v)) if (x.isClickable() && x.getContentDescription() != null && x.getContentDescription().toString().startsWith("Foto protegido"))
            InstrumentationRegistry.getInstrumentation().runOnMainSync(x::performClick);
        for (View x : all(v)) if (x.getContentDescription() != null && x.getContentDescription().toString().startsWith("PDF protegido")) assertFalse("consumed rows are not actionable", x.isClickable());
        assertEquals(List.of("r-open"), opened);
        assertConcise(v); assertAccessible(v);
        View attach = render("30b-attach-sheet", ui -> Screen.of(null, ChatScreens.attachSheet(ui, FEATURES, true, new ChatScreens.AttachActions() {
            public void file() {} public void restricted() {} public void location() {} }), null));
        assertTrue(hasText(attach, "Contenido protegido")); assertTrue(hasText(attach, "Máximo 256 KiB · exportable"));
        assertConcise(attach);
    }
    @Test public void protectedViewerChromeHasNoExportAndSeparatesObjectAndSessionExpiry() {
        ContentScreens.ViewerActions actions = new ContentScreens.ViewerActions() { public void close() {} public void previous() {} public void next() {} public void emergency() {} };
        View pdf = render("31a-protected-viewer-pdf", ui -> ContentScreens.viewer(ui, new ContentScreens.ViewerState(RestrictedPresentation.Kind.PDF, "ONCE", "Bruno",
            "Abierto", Tone.ACCENT, "27/09 22:40", "Sesión limitada", 0, 3, false), new app.umbra.ui.design.ProtectedFrameView(ui.context()), actions, new ContentScreens.Handles()));
        assertTrue(hasText(pdf, "PDF protegido")); assertTrue(hasText(pdf, "caduca 27/09 22:40")); assertTrue(hasText(pdf, "Sesión limitada"));
        assertFalse("first page: no previous", button(pdf, "Anterior").isEnabled());
        assertTrue(button(pdf, "Siguiente").isEnabled());
        assertTrue(hasText(pdf, "Sin exportar · sin compartir"));
        for (View x : all(pdf)) if (x instanceof Button b) for (String f : new String[]{"Compartir", "Guardar", "Exportar", "Imprimir", "Reenviar", "Copiar"}) assertFalse(b.getText().toString().contains(f));
        boolean emergency = false; for (View x : all(pdf)) if ("Bloqueo de emergencia".contentEquals(String.valueOf(x.getContentDescription()))) emergency = true;
        assertTrue("emergency stays one tap away while content is shown", emergency);
        assertConcise(pdf); assertAccessible(pdf);
        View note = render("31b-protected-viewer-note", ui -> ContentScreens.viewer(ui, new ContentScreens.ViewerState(RestrictedPresentation.Kind.NOTE, "UMBRA_ONLY", "Bruno",
            RestrictedPresentation.playbackLabel("COMPLETED"), Tone.NEUTRAL, "27/09 22:40", "Sesión limitada", 0, 1, false), null, actions, new ContentScreens.Handles()));
        assertTrue(hasText(note, "Terminó")); assertTrue(hasText(note, "Solo en UMBRA"));
        assertFalse(visibleText(note).toLowerCase(Locale.ROOT).contains("escuchad"));
        assertConcise(note);
    }
    @Test public void adminToolsFollowTheAuthoritySnapshot() {
        AdmissionScreens.AdminActions ad = new AdmissionScreens.AdminActions() {
            public void back() {} public void createRealm() {} public void exportRealm() {} public void reviewRequest() {} public void revokeCredential() {}
        };
        View member = render("26e-admission-admin-member", ui -> AdmissionScreens.admin(ui, new AdmissionScreens.AdminState(true, REALM, Fingerprints.lines(FP, 4), false, false, List.of()), ad));
        assertTrue(hasText(member, "Este teléfono no es la autoridad"));
        assertNull("no decision tools without authority", button(member, "Revisar solicitud"));
        assertNull(button(member, "Revocar credencial"));
        assertNotNull(button(member, "Exportar entorno"));
        View authority = render("26f-admission-admin-issued", ui -> AdmissionScreens.admin(ui, new AdmissionScreens.AdminState(true, REALM, Fingerprints.lines(FP, 4), false, true,
            List.of(new AdmissionScreens.IssuedRow("A1A1 A1A1", "27/09 10:40", "04/10 10:40", false, false),
                new AdmissionScreens.IssuedRow("B2B2 B2B2", "20/09 09:00", "21/09 09:00", true, true))), ad));
        assertTrue(hasText(authority, "Credenciales emitidas")); assertTrue(hasText(authority, "Vigente")); assertTrue(hasText(authority, "Revocada"));
        assertConcise(authority); assertAccessible(authority);
    }

    @Test public void errorsAreHumanWithOptionalTechnicalDetails() {
        View v = render("18-errors", ui -> {
            LinearLayout box = ui.column();
            for (ErrorKind k : new ErrorKind[]{ErrorKind.NO_CONNECTION, ErrorKind.RELAY_UNAVAILABLE, ErrorKind.TURN_UNAVAILABLE, ErrorKind.IDENTITY_CHANGED, ErrorKind.DEVICE_REVOKED, ErrorKind.MICROPHONE_DENIED})
                box.addView(ui.errorState(ErrorPresentation.of(k, "SecurityException: synthetic diagnostic"), () -> {}, true));
            return Screen.of(null, box, null);
        });
        assertTrue(hasText(v, "Conexión privada no disponible"));
        assertFalse("technical details collapsed by default", hasText(v, "synthetic diagnostic"));
        assertNotNull(button(v, "Detalles técnicos"));
        assertAccessible(v);
    }

    @Test public void largeFontScaleWrapsWithoutHorizontalOverflow() {
        View lock = render(2.0f, "20a-font-200-lock", ui -> EntryScreens.lock(ui, new EntryScreens.LockState(true, !BuildConfig.ALLOW_RELAY, null, null), new EntryScreens.LockActions() {
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
