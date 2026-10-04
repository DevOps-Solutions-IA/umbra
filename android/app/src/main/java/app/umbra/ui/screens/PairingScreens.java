package app.umbra.ui.screens;

import android.graphics.Bitmap;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.umbra.pairing.PairingSnapshot;
import app.umbra.ui.design.Motion;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraTokens;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import java.nio.CharBuffer;

/**
 * "Agregar contacto": scan a QR, enter a code, show my QR or code, and the file fallback under "Más opciones".
 * The user never sees invite/request/ack, realm, capability or prekeys. Every state shown comes from a
 * PAIRING_PRODUCT_V1 snapshot or typed failure built by the host; screens hold no secrets of their own except
 * the char[] code the host passes for display (wiped by the host on leave/lock).
 */
public final class PairingScreens {
    private PairingScreens() {}

    /** What can be offered right now. {@code onlineProblem} non-null means QR/code need the private connection. */
    public record Entry(boolean scanAvailable, boolean onlineBuild, String onlineProblem) {}
    public interface EntryActions { void scan(); void enterCode(); void showQr(); void showCode(); void file(); void nearby(); void configure(); }

    /** Content of the "Agregar contacto" sheet: three primary actions, the rest under "Más opciones". */
    public static LinearLayout addContact(Ui ui, Entry e, EntryActions a) {
        LinearLayout box = ui.column();
        box.addView(ui.heading(UmbraType.TITLE, "Agregar contacto"), ui.margins(Ui.match(), 0, UmbraTokens.SPACE_8));
        if (e.onlineBuild()) {
            if (e.onlineProblem() != null)
                box.addView(ui.banner(Tone.WARNING, Glyph.CLOUD_OFF, e.onlineProblem(), null, "Configurar", a::configure));
            boolean ready = e.onlineProblem() == null;
            if (e.scanAvailable()) box.addView(entryRow(ui, Glyph.CAMERA, "Escanear QR", ready, a::scan));
            box.addView(entryRow(ui, Glyph.PASSWORD, "Ingresar código", ready, a::enterCode));
            box.addView(entryRow(ui, Glyph.QR, "Mostrar mi QR", ready, a::showQr));
            box.addView(entryRow(ui, Glyph.SHIELD, "Mostrar mi código", ready, a::showCode));
        }
        box.addView(ui.sectionHeader("Más opciones"));
        box.addView(entryRow(ui, Glyph.FILE, "Archivo de vinculación", true, a::file));
        box.addView(entryRow(ui, Glyph.BLUETOOTH, "Conexión cercana", true, a::nearby));
        return box;
    }
    private static LinearLayout entryRow(Ui ui, Glyph glyph, String title, boolean enabled, Runnable onClick) {
        LinearLayout r = ui.listRow(ui.iconTile(glyph, enabled ? Tone.ACCENT : Tone.NEUTRAL), title, null, enabled ? ui.chevron() : null, enabled ? onClick : null);
        if (!enabled) { r.setAlpha(UmbraTokens.OPACITY_DISABLED); r.setContentDescription(title + ". No disponible ahora"); }
        return r;
    }

    // ------------------------------------------------------------------ flow screen
    public enum Mode { SHOW_QR, SHOW_CODE, ENTER_CODE, SCAN, FILE }

    /**
     * @param snapshot     last domain snapshot (null while preparing)
     * @param busyPhase    real phase label of a running domain call, or null
     * @param problem      typed failure text (PairingPresentation.failure), or null
     * @param qr           QR rendered from the signed invitation by PairingQrCodec (SHOW_QR only); never shown as text
     * @param code         one-use code for display (SHOW_CODE only), host-owned and wiped on leave/lock
     * @param validFor     "Válido durante m:ss" observed text, or null
     * @param scanState    SCAN: null while the camera runs, else a short reason. ENTER_CODE: inline input hint.
     * @param fileStage    FILE only: what the user can do next
     */
    public record Flow(Mode mode, PairingSnapshot snapshot, String busyPhase, String problem, boolean retryable, Bitmap qr,
                       char[] code, String validFor, String scanState, boolean scanPermissionDenied, FileStage fileStage) {}
    public enum FileStage { START, SAVE_RESPONSE, CONTINUE, DONE }
    public interface FlowActions {
        void close(); void cancelPairing(); void retry(); void verify(); void later();
        void submitCode(EditText[] groups); void requestCamera(); void enterCodeInstead(); void scannerSurface(TextureView view);
        void createFile(); void pickFile(); void saveResponse();
    }

    /**
     * Views/buffers the host updates or wipes without re-mounting: the validity line (countdown in place) and the
     * char[] copies of the code backing the displayed text and its spoken label (wiped when the flow is cleared).
     */
    public static final class Handles {
        public TextView validity;
        private final java.util.List<char[]> buffers = new java.util.ArrayList<>();
        public void wipe() { for (char[] b : buffers) HumanCodeInput.wipe(b); buffers.clear(); validity = null; }
    }
    public static Screen flow(Ui ui, Flow f, FlowActions a) { return flow(ui, f, new Handles(), a); }
    public static Screen flow(Ui ui, Flow f, Handles h, FlowActions a) {
        String title = switch (f.mode()) {
            case SHOW_QR -> "Mi QR"; case SHOW_CODE -> "Mi código"; case ENTER_CODE -> "Ingresar código";
            case SCAN -> "Escanear QR"; case FILE -> "Archivo de vinculación";
        };
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::close, ui.titleBlock(title, null), ui.helpButton(Help.PAIRING)));
        LinearLayout body = ui.column(); body.setPadding(0, ui.dp(UmbraTokens.SPACE_8), 0, ui.dp(UmbraTokens.SPACE_16));
        LinearLayout bottom = ui.column();
        PairingPresentation.Stage stage = PairingPresentation.stage(f.snapshot());

        // A terminal result replaces the step content: the user only needs the outcome and the next action.
        // The file flow first saves the response file (the inviter already added the contact at that point).
        boolean saveFirst = f.mode() == Mode.FILE && f.fileStage() == FileStage.SAVE_RESPONSE;
        if (stage == PairingPresentation.Stage.ADDED && !saveFirst) { added(ui, body, bottom, f, a); return Screen.of(top, body, bottom); }
        if (f.problem() != null || stage == PairingPresentation.Stage.ENDED) {
            String text = f.problem() != null ? f.problem() : PairingPresentation.failure(f.snapshot().failure());
            body.addView(ui.banner(Tone.DANGER, Glyph.WARNING, text, null, null, null));
            if (f.retryable()) bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Intentar de nuevo", Glyph.RETRY, a::retry));
            bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Cerrar", Glyph.CLOSE, a::close));
            return Screen.of(top, body, bottom);
        }

        switch (f.mode()) {
            case SHOW_QR -> showQr(ui, body, f, h);
            case SHOW_CODE -> showCode(ui, body, f, h);
            case ENTER_CODE -> enterCode(ui, body, bottom, f, a);
            case SCAN -> scan(ui, body, bottom, f, a);
            case FILE -> file(ui, body, bottom, f, a, h);
        }
        // Online flows show the real phases once the domain reports them.
        if (f.mode() != Mode.FILE && (f.snapshot() != null || (f.busyPhase() != null && f.mode() != Mode.ENTER_CODE)))
            body.addView(progress(ui, f), ui.margins(Ui.match(), UmbraTokens.SPACE_16, 0));
        if (f.snapshot() != null && stage == PairingPresentation.Stage.WAITING)
            bottom.addView(ui.button(Ui.ButtonKind.SECONDARY, "Cancelar vinculación", Glyph.CLOSE, a::cancelPairing));
        return Screen.of(top, body, bottom);
    }

    private static LinearLayout progress(Ui ui, Flow f) {
        int current = PairingPresentation.stepIndex(f.snapshot());
        Ui.StepState[] states = new Ui.StepState[PairingPresentation.STEPS.length];
        for (int i = 0; i < states.length; i++) states[i] = i < current ? Ui.StepState.DONE : i == current ? Ui.StepState.CURRENT : Ui.StepState.PENDING;
        return ui.stepProgress(PairingPresentation.STEPS, states);
    }

    private static void validity(Ui ui, LinearLayout body, Flow f, Handles h) {
        if (f.validFor() == null) return;
        TextView t = ui.text(UmbraType.LABEL, f.validFor(), UmbraColors.TEXT_SECONDARY); t.setGravity(Gravity.CENTER);
        h.validity = t;
        body.addView(t, ui.margins(Ui.match(), UmbraTokens.SPACE_12, 0));
    }

    private static void showQr(Ui ui, LinearLayout body, Flow f, Handles h) {
        if (f.qr() == null) { body.addView(ui.pageLoader("Preparando QR…", null)); return; }
        FrameLayout frame = new FrameLayout(ui.context());
        frame.setBackground(ui.shape(0xFFFFFFFF, UmbraTokens.RADIUS_CARD)); // the code needs a light quiet zone
        int pad = ui.dp(UmbraTokens.SPACE_16); frame.setPadding(pad, pad, pad, pad);
        ImageView image = new ImageView(ui.context()); image.setImageBitmap(f.qr()); image.setAdjustViewBounds(true);
        // Never read the payload aloud.
        image.setContentDescription("Código QR para agregar este contacto");
        frame.addView(image, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(280), ui.dp(280)); p.gravity = Gravity.CENTER_HORIZONTAL;
        p.topMargin = ui.dp(UmbraTokens.SPACE_16);
        body.addView(frame, p);
        validity(ui, body, f, h);
        Motion.enter(frame);
    }

    private static void showCode(Ui ui, LinearLayout body, Flow f, Handles h) {
        if (f.code() == null) { body.addView(ui.pageLoader("Preparando código…", null)); return; }
        char[] grouped = HumanCodeInput.grouped(f.code()); h.buffers.add(grouped);
        TextView code = ui.text(UmbraType.DISPLAY, "");
        code.setText(grouped, 0, grouped.length); // char[]: no extra String copy of the code
        code.setTypeface(UmbraType.MONOSPACE.typeface()); code.setLetterSpacing(0.06f); code.setGravity(Gravity.CENTER);
        code.setTextIsSelectable(false); code.setLongClickable(false); code.setTag(Ui.TECHNICAL);
        char[] spoken = HumanCodeInput.spoken(f.code()); h.buffers.add(spoken);
        code.setContentDescription(CharBuffer.wrap(spoken));
        code.setBackground(ui.outlined(UmbraColors.SURFACE, UmbraColors.OUTLINE, UmbraTokens.RADIUS_CARD));
        int pad = ui.dp(UmbraTokens.SPACE_16); code.setPadding(pad, ui.dp(UmbraTokens.SPACE_24), pad, ui.dp(UmbraTokens.SPACE_24));
        body.addView(code, ui.margins(Ui.match(), UmbraTokens.SPACE_16, 0));
        TextView once = ui.text(UmbraType.CAPTION, "Un solo uso. Díctalo solo a esa persona."); once.setGravity(Gravity.CENTER);
        body.addView(once, ui.margins(Ui.match(), UmbraTokens.SPACE_8, 0));
        validity(ui, body, f, h);
        Motion.enter(code);
    }

    private static void enterCode(Ui ui, LinearLayout body, LinearLayout bottom, Flow f, FlowActions a) {
        LinearLayout groups = ui.row(); groups.setGravity(Gravity.CENTER);
        EditText[] fields = new EditText[HumanCodeInput.GROUPS];
        for (int i = 0; i < fields.length; i++) {
            EditText e = ui.field(null);
            e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(HumanCodeInput.GROUP), new InputFilter.AllCaps()});
            // Visible uppercase only; no suggestions or autocorrect that could change an ambiguous character.
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            e.setTypeface(UmbraType.MONOSPACE.typeface()); e.setGravity(Gravity.CENTER); e.setSingleLine(true);
            e.setContentDescription("Grupo " + (i + 1) + " de " + HumanCodeInput.GROUPS);
            e.setImeOptions(i == fields.length - 1 ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
            if (f.busyPhase() != null) e.setEnabled(false);
            fields[i] = e;
            LinearLayout.LayoutParams p = Ui.weight(); p.setMarginStart(ui.dp(i == 0 ? 0 : 6)); groups.addView(e, p);
        }
        for (int i = 0; i < fields.length; i++) {
            final int index = i;
            fields[i].addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int after) {}
                @Override public void onTextChanged(CharSequence s, int st, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    // A pasted full code ("ABCD-EFGH-…") is spread over the groups; auto-advance when a group is full.
                    if (index == 0 && s.length() > HumanCodeInput.GROUP) return;
                    if (s.length() == HumanCodeInput.GROUP && index + 1 < fields.length) fields[index + 1].requestFocus();
                }
            });
            if (i > 0) fields[i].setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL && event.getAction() == android.view.KeyEvent.ACTION_DOWN && fields[index].length() == 0) {
                    fields[index - 1].requestFocus(); return true;
                }
                return false;
            });
        }
        fields[fields.length - 1].setOnEditorActionListener((v, actionId, ev) -> { if (actionId == EditorInfo.IME_ACTION_DONE) { a.submitCode(fields); return true; } return false; });
        body.addView(groups, ui.margins(Ui.match(), UmbraTokens.SPACE_24, 0));
        boolean warn = f.scanState() != null;
        TextView hint = ui.text(UmbraType.CAPTION, warn ? f.scanState() : "16 caracteres, en 4 grupos.", warn ? UmbraColors.DANGER_FG : UmbraColors.TEXT_SECONDARY);
        hint.setGravity(Gravity.CENTER); hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(hint, ui.margins(Ui.match(), UmbraTokens.SPACE_8, 0));
        if (f.snapshot() == null) {
            Button submit = ui.button(Ui.ButtonKind.PRIMARY, "Continuar", Glyph.CHECK, () -> a.submitCode(fields));
            if (f.busyPhase() != null) ui.busy(submit, f.busyPhase());
            bottom.addView(submit);
        }
    }

    private static void scan(Ui ui, LinearLayout body, LinearLayout bottom, Flow f, FlowActions a) {
        if (f.snapshot() == null && f.busyPhase() == null) {
            if (f.scanState() == null) {
                FrameLayout frame = new FrameLayout(ui.context());
                frame.setBackground(ui.shape(UmbraColors.BACKGROUND_SECONDARY, UmbraTokens.RADIUS_CARD)); frame.setClipToOutline(true);
                TextureView preview = new TextureView(ui.context());
                preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
                View guide = new View(ui.context()); // framing guide only, decorative
                guide.setBackground(ui.outlined(0x00000000, UmbraColors.ACCENT_MUTED, UmbraTokens.RADIUS_CARD));
                FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(ui.dp(220), ui.dp(220), Gravity.CENTER); frame.addView(guide, gp);
                frame.setContentDescription("Cámara: apunta al QR del otro teléfono");
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, ui.dp(320)); p.topMargin = ui.dp(UmbraTokens.SPACE_8);
                body.addView(frame, p);
                TextView hint = ui.text(UmbraType.BODY_SECONDARY, "Apunta al QR del otro teléfono."); hint.setGravity(Gravity.CENTER);
                body.addView(hint, ui.margins(Ui.match(), UmbraTokens.SPACE_12, 0));
                a.scannerSurface(preview);
            } else {
                body.addView(ui.banner(f.scanPermissionDenied() ? Tone.WARNING : Tone.NEUTRAL, Glyph.CAMERA, f.scanState(), null, null, null));
                if (f.scanPermissionDenied()) bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Permitir cámara", Glyph.CAMERA, a::requestCamera));
            }
            bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Ingresar código", Glyph.PASSWORD, a::enterCodeInstead));
        }
    }

    private static void file(Ui ui, LinearLayout body, LinearLayout bottom, Flow f, FlowActions a, Handles h) {
        FileStage st = f.fileStage() == null ? FileStage.START : f.fileStage();
        if (f.busyPhase() != null) { body.addView(ui.inlineLoader(f.busyPhase())); return; }
        switch (st) {
            case START -> {
                body.addView(ui.text(UmbraType.BODY_SECONDARY, "Sin conexión privada: intercambien archivos hasta terminar."));
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Seleccionar archivo", Glyph.FILE, a::pickFile));
                bottom.addView(ui.button(Ui.ButtonKind.SECONDARY, "Crear archivo", Glyph.ADD, a::createFile));
            }
            case SAVE_RESPONSE -> {
                body.addView(ui.banner(Tone.ACCENT, Glyph.FILE, "Guarda el archivo y envíalo al otro teléfono.", null, null, null));
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Guardar archivo", Glyph.FILE, a::saveResponse));
            }
            case CONTINUE -> {
                body.addView(ui.banner(Tone.NEUTRAL, Glyph.TIMER, "Cuando recibas su archivo, selecciónalo aquí.", null, null, null));
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Seleccionar archivo", Glyph.FILE, a::pickFile));
            }
            case DONE -> bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Cerrar", Glyph.CLOSE, a::close));
        }
        if (f.snapshot() != null && PairingPresentation.stage(f.snapshot()) == PairingPresentation.Stage.WAITING) validity(ui, body, f, h);
    }

    /** Contact exists locally and is UNVERIFIED: never "seguro" or "verificado". */
    private static void added(Ui ui, LinearLayout body, LinearLayout bottom, Flow f, FlowActions a) {
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER_HORIZONTAL); box.setPadding(0, ui.dp(64), 0, 0);
        LinearLayout tile = ui.iconTile(Glyph.PERSON_ADD, Tone.ACCENT); tile.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        box.addView(tile);
        TextView h = ui.heading(UmbraType.TITLE, "Contacto agregado"); h.setGravity(Gravity.CENTER); box.addView(h, ui.margins(Ui.match(), UmbraTokens.SPACE_16, UmbraTokens.SPACE_4));
        TextView d = ui.text(UmbraType.BODY_SECONDARY, PairingPresentation.addedDetail(f.snapshot())); d.setGravity(Gravity.CENTER); box.addView(d, Ui.match());
        box.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(box);
        Motion.confirm(tile);
        if (f.snapshot().verificationRequired() && f.snapshot().peerId() != null) {
            bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Verificar ahora", Glyph.SHIELD_CHECK, a::verify));
            bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Más tarde", null, a::later));
        } else bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Listo", Glyph.CHECK, a::later));
    }

    /** "Conexión privada no disponible": online pairing is not faked; the user can go configure it. */
    public static String onlineProblem(boolean relayConfigured, boolean admitted, boolean networkAllowed) {
        return PairingPresentation.onlineProblem(relayConfigured, admitted, networkAllowed);
    }

    /** Reads typed groups into a normalized char[] (caller wipes), clearing nothing itself. */
    public static char[] read(EditText[] groups) {
        int total = 0; for (EditText e : groups) total += e.length();
        char[] raw = new char[total]; int at = 0;
        for (EditText e : groups) { e.getText().getChars(0, e.length(), raw, at); at += e.length(); }
        char[] normalized = HumanCodeInput.normalize(CharBuffer.wrap(raw));
        HumanCodeInput.wipe(raw);
        return normalized;
    }
    public static void clear(EditText[] groups) { for (EditText e : groups) e.getText().clear(); }
}
