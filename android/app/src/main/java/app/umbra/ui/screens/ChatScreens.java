package app.umbra.ui.screens;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 1:1 conversation, group conversation (prepared), attachments, new message and new group flows. */
public final class ChatScreens {
    private ChatScreens() {}

    /** A location entry shown in the conversation (received or our own active share). */
    public record LocationEntry(String title, String detail, boolean live, boolean outgoing) {}
    /** One row of the history list: a message, a location card or a received restricted object (metadata only). */
    public record Entry(MessageItem message, LocationEntry location, RestrictedPresentation.Received restricted) {
        public Entry(MessageItem message, LocationEntry location) { this(message, location, null); }
    }

    public record ChatState(String peerId, String alias, TrustPresentation trust, List<Entry> entries, String expiryLabel,
                            String draft, boolean sharingLocation, String locationStatus, FeatureAvailability features,
                            boolean offlineEdition, boolean voiceSessionActive) {}
    public interface ChatActions {
        void back(); void contact(); void verify(); void unblock(); void voiceCall(); void videoCall(); void openCall();
        void attach(); void send(String text); void draft(String text); void message(MessageItem item);
        void stopLocation(); void retry(); void openRestricted(String id);
    }

    public static Screen direct(Ui ui, ChatState s, ChatActions a) {
        LinearLayout top = ui.column();
        LinearLayout title = ui.row();
        title.addView(ui.avatar(s.alias(), false, 40));
        LinearLayout names = ui.column(); names.setPadding(ui.dp(10), 0, 0, 0);
        TextView name = ui.heading(UmbraType.HEADING, s.alias()); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); names.addView(name);
        LinearLayout trustLine = ui.row();
        trustLine.addView(ui.iconView(s.trust().glyph(), Ui.toneColor(s.trust().tone()), 14));
        TextView tl = ui.text(UmbraType.CAPTION, s.trust().label(), Ui.toneColor(s.trust().tone())); tl.setTypeface(UmbraType.LABEL.typeface()); tl.setPadding(ui.dp(4), 0, 0, 0);
        trustLine.addView(tl); names.addView(trustLine);
        title.addView(names, Ui.weight());
        title.setClickable(true); title.setFocusable(true); title.setOnClickListener(v -> a.contact());
        title.setContentDescription(s.alias() + ". " + s.trust().label() + ". Abrir información del contacto");
        title.setMinimumHeight(ui.dp(Ui.TOUCH_MIN_DP));
        boolean callsVisible = s.features().visible(Feature.VOICE_CALLS);
        ImageButton call = callsVisible ? ui.iconButton(Glyph.CALL, "Llamada de voz", a::voiceCall) : null;
        ImageButton video = callsVisible ? ui.iconButton(Glyph.VIDEO, "Videollamada", a::videoCall) : null;
        if (callsVisible && !s.trust().allowsCalls()) {
            for (ImageButton b : new ImageButton[]{call, video}) { b.setEnabled(false); b.setContentDescription(b.getContentDescription() + ". No disponible: " + s.trust().blockedReason()); }
        }
        top.addView(ui.topBar(a::back, title, call, video));
        if (s.voiceSessionActive())
            top.addView(ui.banner(Tone.SUCCESS, Glyph.MIC, "Llamada en curso", null, "Volver", a::openCall));
        switch (s.trust().level()) {
            case IDENTITY_CHANGED -> top.addView(ui.banner(s.trust().tone(), s.trust().glyph(), "Verifica de nuevo", null, "Verificar", a::verify));
            case UNVERIFIED -> top.addView(ui.banner(Tone.WARNING, Glyph.SHIELD, "Sin verificar", null, "Verificar", a::verify));
            case BLOCKED -> top.addView(ui.banner(s.trust().tone(), s.trust().glyph(), "Bloqueado", null, "Desbloquear", a::unblock));
            default -> {}
        }
        if (s.sharingLocation())
            top.addView(ui.locationCard("Tu ubicación", s.locationStatus(), true, true, a::stopLocation));

        List<Object> rows = new ArrayList<>();
        rows.add("header");
        rows.addAll(s.entries());
        ListView list = Lists.of(ui, rows, row -> {
            if (row instanceof String) {
                LinearLayout h = ui.column(); h.setGravity(Gravity.CENTER_HORIZONTAL); h.setPadding(0, ui.dp(8), 0, ui.dp(8));
                h.addView(ui.chip(Tone.NEUTRAL, Glyph.TIMER, "Caduca en " + s.expiryLabel()));
                return h;
            }
            Entry e = (Entry) row;
            if (e.location() != null) return ui.locationCard(e.location().title(), e.location().detail(), e.location().live(), e.location().outgoing(), null);
            if (e.restricted() != null) return restrictedRow(ui, e.restricted(), a);
            MessageItem m = e.message();
            // Files: explicit export. Ordinary text: explicit copy through the private clipboard (one-use consent).
            boolean actionable = m.kind() == MessageItem.Kind.FILE || m.kind() == MessageItem.Kind.IMAGE
                || (m.kind() == MessageItem.Kind.TEXT && s.features().available(Feature.CLIPBOARD_PROTECTION));
            return ui.bubble(m, actionable ? () -> a.message(m) : null, null);
        }, true);
        list.setContentDescription("Historial de mensajes con " + s.alias());

        View bottom;
        if (s.trust().allowsMessaging()) bottom = composer(ui, s.draft(), true, a);
        else {
            LinearLayout blocked = ui.column();
            blocked.addView(ui.banner(s.trust().tone(), s.trust().glyph(), "Envío bloqueado", null, null, null));
            bottom = blocked;
        }
        return Screen.list(top, list, bottom);
    }

    static LinearLayout composer(Ui ui, String draft, boolean enabled, ChatActions a) {
        LinearLayout c = ui.row(); c.setGravity(Gravity.BOTTOM); c.setPadding(0, ui.dp(6), 0, 0);
        c.addView(ui.iconButton(Glyph.ATTACH, "Adjuntar", a::attach));
        EditText input = ui.field("Mensaje");
        input.setText(draft == null ? "" : draft);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5); input.setSingleLine(false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setBackground(ui.outlined(UmbraColors.SURFACE, UmbraColors.OUTLINE, 22));
        LinearLayout.LayoutParams ip = Ui.weight(); ip.setMarginStart(ui.dp(4)); ip.setMarginEnd(ui.dp(4)); c.addView(input, ip);
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence t, int s, int n, int af) {}
            public void onTextChanged(CharSequence t, int s, int b, int n) { a.draft(t.toString()); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        ImageButton send = ui.iconButton(Glyph.SEND, "Enviar", () -> { String t = input.getText().toString(); if (!t.trim().isEmpty()) a.send(t); });
        send.setBackground(ui.shape(UmbraColors.ACCENT_STRONG, 24));
        send.setImageTintList(null); send.setImageDrawable(ui.icon(Glyph.SEND, UmbraColors.ON_ACCENT, 22));
        c.addView(send);
        input.setOnEditorActionListener((v, id, e) -> { if (id == EditorInfo.IME_ACTION_SEND) { send.performClick(); return true; } return false; });
        if (!enabled) { input.setEnabled(false); send.setEnabled(false); }
        return c;
    }

    /** Received restricted object: public metadata only (format, mode, expiry, domain state). No preview. */
    static LinearLayout restrictedRow(Ui ui, RestrictedPresentation.Received r, ChatActions a) {
        String title = (r.kind() == null ? "Contenido" : r.kind().label) + " protegido";
        LinearLayout row = ui.listRow(ui.iconTile(r.kind() == null ? Glyph.WARNING : r.kind().glyph, r.tone()), title, r.detail(),
            ui.chip(r.tone(), r.canOpen() ? Glyph.EYE : Glyph.LOCK, r.state()), r.canOpen() ? () -> a.openRestricted(r.id()) : null);
        row.setContentDescription(title + ". " + r.detail() + ". " + r.state() + (r.canOpen() ? ". Abrir" : ""));
        return row;
    }

    // ------------------------------------------------------------------ attachments
    public interface AttachActions { void file(); void restricted(); void location(); }

    /**
     * Attachment sheet. "Archivo" is an ordinary stored file (exportable by the recipient). Protected content
     * (photo, voice note, video, PDF) goes through the restricted service: not exportable, ONCE or Solo en UMBRA.
     */
    public static LinearLayout attachSheet(Ui ui, FeatureAvailability f, boolean allowsLocation, AttachActions a) {
        LinearLayout box = ui.column();
        box.addView(ui.heading(UmbraType.TITLE, "Compartir"));
        // Same precondition the domain enforces (one verified, admitted recipient); shown, never decided, here.
        LinearLayout restricted = ui.listRow(ui.iconTile(Glyph.SHIELD_CHECK, allowsLocation ? Tone.ACCENT : Tone.NEUTRAL), "Contenido protegido",
            allowsLocation ? "Foto, nota, video o PDF" : "Requiere contacto verificado", ui.chevron(), allowsLocation ? a::restricted : null);
        if (!allowsLocation) restricted.setAlpha(0.6f);
        box.addView(restricted);
        box.addView(ui.listRow(ui.iconTile(Glyph.FILE, Tone.NEUTRAL), "Archivo", "Máximo 256 KiB · exportable", ui.chevron(), a::file));
        LinearLayout location = ui.listRow(ui.iconTile(Glyph.LOCATION, allowsLocation ? Tone.ACCENT : Tone.NEUTRAL), "Ubicación",
            allowsLocation ? null : "Requiere contacto verificado", ui.chevron(), allowsLocation ? a::location : null);
        if (!allowsLocation) location.setAlpha(0.6f);
        box.addView(location);
        return box;
    }

    // ------------------------------------------------------------------ group conversation (prepared)
    public record GroupState(String id, String name, List<String> members, List<MessageItem> messages, FeatureAvailability features) {}
    public interface GroupActions { void back(); void info(); }

    /**
     * Group conversation layout. Only reachable when {@link Feature#GROUP_CHAT} is AVAILABLE; today it
     * is rendered for design review with synthetic data and states plainly that no group engine exists.
     */
    public static Screen group(Ui ui, GroupState s, GroupActions a) {
        LinearLayout top = ui.column();
        LinearLayout title = ui.row(); title.addView(ui.avatar(s.name(), true, 40));
        LinearLayout names = ui.column(); names.setPadding(ui.dp(10), 0, 0, 0);
        names.addView(ui.heading(UmbraType.HEADING, s.name()));
        names.addView(ui.text(UmbraType.CAPTION, s.members().size() + " miembros"));
        title.addView(names, Ui.weight());
        title.setContentDescription("Grupo " + s.name() + ". " + s.members().size() + " miembros");
        top.addView(ui.topBar(a::back, title, ui.iconButton(Glyph.INFO, "Información del grupo", a::info)));
        if (!s.features().available(Feature.GROUP_CHAT))
            top.addView(ui.banner(Tone.NEUTRAL, Glyph.INFO, "Vista previa · no se envía", null, null, null));
        ListView list = Lists.of(ui, s.messages(), m -> ui.bubble(m, null, null), true);
        LinearLayout bottom = ui.column();
        Button send = ui.button(Ui.ButtonKind.SECONDARY, "Escribir al grupo", Glyph.SEND, null);
        if (!s.features().available(Feature.GROUP_CHAT)) ui.disabled(send, "próximamente");
        bottom.addView(send);
        return Screen.list(top, list, bottom);
    }

    // ------------------------------------------------------------------ new message
    public record Contact(String id, String alias, TrustLevel trust) {}
    public interface PickActions { void back(); void pick(Contact contact); void addContact(); }

    /** Contacts grouped by trust. Security state is visible on every row, never behind a menu. */
    public static Screen newMessage(Ui ui, List<Contact> contacts, PickActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Nuevo mensaje", null)));
        LinearLayout body = ui.column();
        body.addView(ui.listRow(ui.iconTile(Glyph.PERSON_ADD, Tone.ACCENT), "Agregar contacto", null, ui.chevron(), a::addContact));
        List<Contact> verified = new ArrayList<>(), attention = new ArrayList<>();
        for (Contact c : contacts) (c.trust() == TrustLevel.VERIFIED ? verified : attention).add(c);
        body.addView(ui.sectionHeader("Contactos verificados"));
        if (verified.isEmpty()) body.addView(ui.text(UmbraType.CAPTION, "Sin contactos verificados."));
        for (Contact c : verified) body.addView(contactRow(ui, c, () -> a.pick(c)));
        if (!attention.isEmpty()) {
            body.addView(ui.sectionHeader("Requieren atención"));
            for (Contact c : attention) body.addView(contactRow(ui, c, () -> a.pick(c)));
        }
        return Screen.of(top, body, null);
    }

    static LinearLayout contactRow(Ui ui, Contact c, Runnable onClick) {
        TrustPresentation p = TrustPresentation.of(c.trust());
        LinearLayout row = ui.listRow(ui.avatar(c.alias(), false, 44), c.alias(), null, ui.trustBadge(p), onClick);
        row.setContentDescription(c.alias() + ". " + p.label());
        return row;
    }

    // ------------------------------------------------------------------ new group (prepared flow)
    public record NewGroupState(int step, List<Contact> contacts, Set<String> selected, String name, FeatureAvailability features) {}
    public interface NewGroupActions { void back(); void toggle(String id); void step(int step); void name(String name); void create(); }

    public static Screen newGroup(Ui ui, NewGroupState s, NewGroupActions a) {
        LinearLayout top = ui.column();
        String[] titles = {"Elegir miembros", "Revisar miembros", "Nombre del grupo"};
        top.addView(ui.topBar(a::back, ui.titleBlock("Nuevo grupo", ui.text(UmbraType.CAPTION, "Paso " + (s.step() + 1) + " de 3 · " + titles[Math.min(2, s.step())]))));
        LinearLayout body = ui.column();
        if (!s.features().available(Feature.GROUP_CHAT))
            body.addView(ui.banner(Tone.NEUTRAL, Glyph.INFO, "Vista previa · próximamente", null, null, null));
        LinearLayout bottom = ui.column();
        switch (s.step()) {
            case 0 -> {
                body.addView(ui.text(UmbraType.CAPTION, "Solo contactos verificados."));
                for (Contact c : s.contacts()) {
                    boolean allowed = c.trust() == TrustLevel.VERIFIED, on = s.selected().contains(c.id());
                    CheckBox box = new CheckBox(ui.context()); box.setChecked(on); box.setEnabled(allowed);
                    box.setButtonTintList(android.content.res.ColorStateList.valueOf(UmbraColors.ACCENT_MUTED));
                    box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); box.setClickable(false);
                    LinearLayout row = ui.listRow(ui.avatar(c.alias(), false, 44), c.alias(),
                        allowed ? TrustPresentation.of(c.trust()).label() : TrustPresentation.of(c.trust()).label() + " · no disponible", box, allowed ? () -> a.toggle(c.id()) : null);
                    if (!allowed) row.setAlpha(0.55f);
                    row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                        @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info); info.setCheckable(true); info.setChecked(on); info.setClassName(CheckBox.class.getName()); info.setEnabled(allowed);
                        }
                    });
                    body.addView(row);
                }
                Button next = ui.button(Ui.ButtonKind.PRIMARY, "Continuar (" + s.selected().size() + ")", Glyph.CHEVRON, () -> a.step(1));
                if (s.selected().isEmpty()) ui.disabled(next, "elige un miembro");
                bottom.addView(next);
            }
            case 1 -> {
                body.addView(ui.text(UmbraType.BODY_SECONDARY, "Miembros:"));
                for (Contact c : s.contacts()) if (s.selected().contains(c.id())) body.addView(contactRow(ui, c, null));
                bottom.addView(ui.button(Ui.ButtonKind.PRIMARY, "Continuar", Glyph.CHEVRON, () -> a.step(2)));
                bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Cambiar", Glyph.BACK, () -> a.step(0)));
            }
            default -> {
                EditText name = ui.field("Nombre");
                name.setText(s.name()); name.setSingleLine(true);
                name.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence t, int st, int n, int af) {}
                    public void onTextChanged(CharSequence t, int st, int b, int n) { a.name(t.toString()); }
                    public void afterTextChanged(android.text.Editable e) {}
                });
                body.addView(ui.labeledField("Nombre del grupo", name));
                body.addView(ui.text(UmbraType.CAPTION, s.selected().size() + " miembros + tú"));
                Button create = ui.button(Ui.ButtonKind.PRIMARY, "Crear grupo", Glyph.GROUP, a::create);
                if (!s.features().available(Feature.GROUP_CHAT)) { ui.disabled(create, "próximamente"); bottom.addView(create); bottom.addView(ui.pendingChip()); }
                else bottom.addView(create);
                bottom.addView(ui.button(Ui.ButtonKind.GHOST, "Atrás", Glyph.BACK, () -> a.step(1)));
            }
        }
        return Screen.of(top, body, bottom);
    }

}
