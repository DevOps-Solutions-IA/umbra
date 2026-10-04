package app.umbra.ui.screens;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import app.umbra.ui.model.RestrictedPresentation.Kind;

/**
 * Protected content (F01 photo, F02 voice note, F03 file video, F04 static PDF, F05 mode, F06 expiry).
 * Builders only present domain metadata and the person's choices; the Activity performs review, preparation,
 * send, open and close on its worker. No screen offers share, save, forward, copy, print or an external viewer.
 */
public final class ContentScreens {
    private ContentScreens() {}

    // ------------------------------------------------------------------ send sheet
    public record SendState(String alias, RestrictedPresentation.Choice choice, boolean captureVisible, boolean busy) {}
    public interface SendActions { void mode(String mode); void ttl(int index); void session(int index); void pick(Kind kind); void capture(); }

    public static LinearLayout sendSheet(Ui ui, SendState s, SendActions a) {
        LinearLayout box = ui.column();
        LinearLayout head = ui.row(); head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.heading(UmbraType.TITLE, "Contenido protegido"), Ui.weight()); head.addView(ui.helpButton(Help.PROTECTED));
        box.addView(head, ui.margins(Ui.match(), 0, 4));
        box.addView(ui.chip(Tone.NEUTRAL, Glyph.PERSON, "Para " + s.alias()), ui.margins(Ui.wrap(), 0, 6));
        RestrictedPresentation.Choice c = s.choice();
        box.addView(ui.sectionHeader("Modo"));
        String[] modes = {RestrictedPresentation.modeLabel(RestrictedPresentation.ONCE), RestrictedPresentation.modeLabel(RestrictedPresentation.UMBRA_ONLY)};
        box.addView(ui.segmented(modes, RestrictedPresentation.UMBRA_ONLY.equals(c.mode()) ? 1 : 0, null, i -> a.mode(RestrictedPresentation.MODES[i])));
        box.addView(ui.text(UmbraType.CAPTION, RestrictedPresentation.modeDetail(c.mode())), ui.margins(Ui.match(), 4, 0));
        box.addView(ui.sectionHeader("Caduca en"));
        box.addView(ui.segmented(RestrictedPresentation.TTL_LABELS, c.ttlIndex(), null, a::ttl));
        box.addView(ui.sectionHeader("Sesión"));
        box.addView(ui.segmented(RestrictedPresentation.SESSION_LABELS, c.sessionIndex(), null, a::session));
        box.addView(ui.sectionHeader("Elegir"));
        for (Kind kind : Kind.values()) {
            LinearLayout row = ui.listRow(ui.iconTile(kind.glyph, Tone.ACCENT), kind.label, kind.limits, ui.chevron(), s.busy() ? null : () -> a.pick(kind));
            row.setContentDescription(kind.label + " protegido. " + kind.limits);
            box.addView(row);
        }
        if (s.captureVisible()) {
            LinearLayout rec = ui.listRow(ui.iconTile(Glyph.MIC, Tone.ACCENT), "Grabar nota", "Hasta 8 s · micrófono elegido", ui.chevron(), s.busy() ? null : a::capture);
            rec.setContentDescription("Grabar nota protegida. Hasta 8 segundos");
            box.addView(rec);
        }
        box.addView(ui.chip(Tone.WARNING, Glyph.WARNING, "No evita fotos de la pantalla con otro equipo"), ui.margins(Ui.wrap(), 10, 0));
        return box;
    }

    // ------------------------------------------------------------------ capture (connected only)
    public record CaptureState(boolean recording, String status) {}
    public interface CaptureActions { void stop(); void cancel(); }

    public static LinearLayout captureSheet(Ui ui, CaptureState s, CaptureActions a) {
        LinearLayout box = ui.column(); box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(ui.heading(UmbraType.TITLE, s.recording() ? "Grabando" : "Nota lista"));
        TextView status = ui.text(UmbraType.BODY_SECONDARY, s.status()); status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(status, ui.margins(Ui.match(), 6, 10));
        if (s.recording()) box.addView(ui.button(Ui.ButtonKind.PRIMARY, "Detener", Glyph.STOP, a::stop));
        box.addView(ui.button(Ui.ButtonKind.GHOST, "Descartar", Glyph.CLOSE, a::cancel));
        return box;
    }

    // ------------------------------------------------------------------ viewer
    /**
     * @param status   observed state (domain/playback), never "visto"/"escuchado"
     * @param expires  object deadline label (F06: object expiry)
     * @param session  active-session limit label (F06: session deadline) — the domain enforces both
     * @param page     current page (PDF), 0-based; pages ≤ 1 hides navigation
     */
    public record ViewerState(Kind kind, String mode, String alias, String status, Tone tone, String expires, String session,
                              int page, int pages, boolean playing) {}
    public interface ViewerActions { void close(); void previous(); void next(); void emergency(); }
    /** Live status line updated in place (a remount would detach a playing video surface). */
    public static final class Handles { public TextView status; }

    /**
     * Viewer chrome around a presentation-owned surface ({@code frame}: protected frame view or secure
     * SurfaceView; null for voice notes). No share/save/export/print control exists on this screen.
     */
    public static Screen viewer(Ui ui, ViewerState s, View frame, ViewerActions a, Handles handles) {
        LinearLayout top = ui.column();
        String title = s.kind().label + " protegido";
        top.addView(ui.topBar(a::close, ui.titleBlock(title, ui.chip(Tone.NEUTRAL, Glyph.SHIELD, RestrictedPresentation.modeLabel(s.mode()))),
            ui.iconButton(Glyph.EMERGENCY_LOCK, "Bloqueo de emergencia", a::emergency)));
        LinearLayout body = ui.column();
        LinearLayout facts = ui.row(); facts.setGravity(Gravity.CENTER_VERTICAL);
        TextView state = ui.text(UmbraType.LABEL, s.status(), Ui.toneColor(s.tone()));
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (handles != null) handles.status = state;
        // A real phase in progress ("Abriendo…") gets an inline spinner; it is the domain's state, not progress.
        if (s.status() != null && s.status().endsWith("…")) {
            android.widget.ImageView spin = ui.spinner(UmbraColors.ACCENT_MUTED, 16);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)); sp.setMarginEnd(ui.dp(8)); facts.addView(spin, sp);
        }
        facts.addView(state, Ui.weight());
        facts.addView(ui.chip(Tone.NEUTRAL, Glyph.TIMER, s.session()));
        body.addView(facts, ui.margins(Ui.match(), 4, 4));
        body.addView(ui.text(UmbraType.CAPTION, "De " + s.alias() + " · caduca " + s.expires()), ui.margins(Ui.match(), 0, 8));
        if (frame != null) {
            FrameLayout holder = new FrameLayout(ui.context());
            holder.setBackground(ui.outlined(UmbraColors.BACKGROUND_TERTIARY, UmbraColors.OUTLINE, 12));
            holder.setClipToOutline(true);
            if (frame.getParent() instanceof android.view.ViewGroup old) old.removeView(frame);
            holder.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            int height = ui.dp(s.kind() == Kind.VIDEO ? 240 : 420);
            body.addView(holder, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height));
        } else {
            LinearLayout note = ui.column(); note.setGravity(Gravity.CENTER_HORIZONTAL); note.setPadding(0, ui.dp(24), 0, ui.dp(24));
            note.addView(ui.iconView(Glyph.MIC, s.playing() ? UmbraColors.ACCENT_MUTED : UmbraColors.TEXT_SECONDARY, 56));
            body.addView(note);
        }
        if (s.pages() > 1) {
            LinearLayout nav = ui.row(); nav.setGravity(Gravity.CENTER_VERTICAL);
            Button prev = ui.button(Ui.ButtonKind.SECONDARY, "Anterior", Glyph.BACK, a::previous);
            if (s.page() <= 0) ui.disabled(prev, "primera página");
            nav.addView(prev, Ui.weight());
            TextView pg = ui.text(UmbraType.LABEL, (s.page() + 1) + " / " + s.pages()); pg.setGravity(Gravity.CENTER);
            pg.setContentDescription("Página " + (s.page() + 1) + " de " + s.pages());
            nav.addView(pg, new LinearLayout.LayoutParams(ui.dp(72), LinearLayout.LayoutParams.WRAP_CONTENT));
            Button next = ui.button(Ui.ButtonKind.SECONDARY, "Siguiente", Glyph.CHEVRON, a::next);
            if (s.page() >= s.pages() - 1) ui.disabled(next, "última página");
            nav.addView(next, Ui.weight());
            body.addView(nav, ui.margins(Ui.match(), 8, 0));
        }
        LinearLayout bottom = ui.column();
        bottom.addView(ui.chip(Tone.NEUTRAL, Glyph.LOCK, "Sin exportar · sin compartir"), ui.margins(Ui.wrap(), 0, 6));
        bottom.addView(ui.button(Ui.ButtonKind.SECONDARY, "Cerrar", Glyph.CLOSE, a::close));
        return Screen.of(top, body, bottom);
    }
}
