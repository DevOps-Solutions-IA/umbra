package app.umbra.ui.screens;

import android.view.Gravity;
import android.view.View;
import android.widget.*;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;

/**
 * Voice call, video call, incoming call, local voice modulator and per-direction video consent.
 * Every indicator reflects state reported by CallService / the media engine; no WebRTC wording.
 */
public final class CallScreens {
    private CallScreens() {}

    /**
     * @param mediaSession false while only signaling exists (microphone not yet authorized)
     * @param modulator    null when no media session (nothing to show)
     * @param video        null when no video has been negotiated
     * @param videoRequest true when the contact proposed video and our answer is pending (CallService video state REVIEW)
     */
    public record CallState(String callId, String alias, CallPresentation call, String duration, boolean mediaSession, boolean muted,
                            ModulatorPresentation modulator, boolean showModulator, VideoPresentation video, boolean videoRequest,
                            boolean videoMode, FeatureAvailability features) {}
    public interface CallActions {
        void minimize(); void authorizeMicrophone(); void mute(boolean mute); void audioOutput(); void toggleModulator();
        void modulated(); void natural(); void retryModulation(); void video(); void stopVideo(); void switchCamera();
        void showRemoteVideo(); void answerVideo(int choice); void hangUp();
    }
    /** Holder so the Activity can tick the duration without rebuilding the screen. */
    public static final class Handles { public TextView duration; }

    public static Screen call(Ui ui, CallState s, CallActions a, Handles handles) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::minimize, ui.titleBlock(s.videoMode() ? "Videollamada" : "Llamada de voz",
            ui.chip(Tone.NEUTRAL, Glyph.SHIELD_CHECK, "Cifrada · retransmisor autorizado"))));
        LinearLayout body = ui.column(); body.setGravity(Gravity.CENTER_HORIZONTAL);

        if (s.videoMode()) body.addView(videoStage(ui, s, a));
        else {
            body.addView(ui.avatar(s.alias(), false, 112), ui.margins(new LinearLayout.LayoutParams(ui.dp(112), ui.dp(112)), 24, 0));
        }
        TextView name = ui.heading(UmbraType.TITLE, s.alias()); name.setGravity(Gravity.CENTER);
        body.addView(name, ui.margins(Ui.match(), 14, 2));
        TextView phase = ui.text(UmbraType.BODY_SECONDARY, s.call().phase(), Ui.toneColor(s.call().tone())); phase.setGravity(Gravity.CENTER);
        phase.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(phase, Ui.match());
        TextView duration = ui.text(UmbraType.DISPLAY, s.duration() == null ? "" : s.duration()); duration.setGravity(Gravity.CENTER);
        duration.setTypeface(UmbraType.MONOSPACE.typeface()); duration.setContentDescription("Duración " + (s.duration() == null ? "" : s.duration()));
        duration.setVisibility(s.call().live() ? View.VISIBLE : View.GONE);
        body.addView(duration, Ui.match());
        if (handles != null) handles.duration = duration;

        // What is being transmitted right now: audio state is always explicit.
        LinearLayout indicators = ui.column(); indicators.setGravity(Gravity.CENTER_HORIZONTAL);
        if (s.mediaSession() && s.modulator() != null) {
            Tone tone = s.modulator().microphoneSilenced() ? Tone.NEUTRAL : s.modulator().modulatedActive() ? Tone.ACCENT : Tone.WARNING;
            Glyph glyph = s.modulator().microphoneSilenced() ? Glyph.MIC_OFF : s.modulator().modulatedActive() ? Glyph.VOICE : Glyph.MIC;
            TextView audio = ui.chip(tone, glyph, s.modulator().transmission()); audio.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            indicators.addView(audio);
        } else if (!s.call().terminal()) indicators.addView(ui.chip(Tone.NEUTRAL, Glyph.MIC_OFF, "Micrófono no autorizado · no se transmite audio"));
        if (s.video() != null) indicators.addView(ui.chip(s.video().cameraTone(), s.video().cameraTransmitting() ? Glyph.VIDEO : Glyph.VIDEO_OFF, s.video().camera()));
        body.addView(indicators, ui.margins(Ui.match(), 12, 8));

        if (s.videoRequest()) body.addView(videoConsent(ui, s.alias(), a));
        if (s.showModulator() && s.modulator() != null) body.addView(modulatorPanel(ui, s.modulator(), a));
        if (!s.mediaSession() && !s.call().terminal()) {
            body.addView(ui.banner(Tone.NEUTRAL, Glyph.INFO, s.call().phase(), s.call().detail(), null, null));
            body.addView(ui.button(Ui.ButtonKind.PRIMARY, "Autorizar micrófono", Glyph.MIC, a::authorizeMicrophone));
        }

        LinearLayout controls = ui.column();
        if (s.mediaSession()) {
            LinearLayout row = ui.row(); row.setGravity(Gravity.CENTER);
            row.addView(spaced(ui, ui.callControl(s.muted() ? Glyph.MIC_OFF : Glyph.MIC, s.muted() ? "Activar micrófono" : "Silenciar", s.muted() ? "Silenciado" : "Micrófono activo", s.muted(), false, true, () -> a.mute(!s.muted()))));
            row.addView(spaced(ui, ui.callControl(Glyph.SPEAKER, "Altavoz", "Elegir salida", false, false, true, a::audioOutput)));
            boolean modAvail = s.features().available(Feature.VOICE_MODULATION);
            String voiceState = s.modulator() == null ? null : s.modulator().modulatedActive() ? "Modulada" : s.modulator().busy() ? "Confirmando…" : s.modulator().microphoneSilenced() && !s.muted() ? "Falló" : "Natural";
            row.addView(spaced(ui, ui.callControl(Glyph.VOICE, "Voz", voiceState, s.modulator() != null && s.modulator().modulatedActive(), false, modAvail, a::toggleModulator)));
            if (s.features().visible(Feature.VIDEO_CALLS))
                row.addView(spaced(ui, ui.callControl(s.videoMode() ? Glyph.CAMERA_SWITCH : Glyph.VIDEO, s.videoMode() ? "Cambiar cámara" : "Video",
                    s.videoMode() ? null : "Requiere consentimiento", false, false, true, s.videoMode() ? a::switchCamera : a::video)));
            controls.addView(row, ui.margins(Ui.match(), 10, 10));
            if (s.videoMode()) {
                LinearLayout vrow = ui.row(); vrow.setGravity(Gravity.CENTER);
                vrow.addView(spaced(ui, ui.callControl(Glyph.VIDEO_OFF, "Apagar video", "El audio continúa", false, false, true, a::stopVideo)));
                controls.addView(vrow);
            }
        }
        LinearLayout endRow = ui.row(); endRow.setGravity(Gravity.CENTER);
        endRow.addView(ui.callControl(Glyph.CALL_END, s.call().terminal() ? "Cerrar" : "Colgar", null, false, true, true, a::hangUp));
        controls.addView(endRow, ui.margins(Ui.match(), 4, 4));
        return Screen.of(top, body, controls);
    }

    private static View spaced(Ui ui, LinearLayout control) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        control.setLayoutParams(p); return control;
    }

    /** Remote video area plus local preview state. The live surface opens from here (embedded surface pending). */
    private static View videoStage(Ui ui, CallState s, CallActions a) {
        FrameLayout stage = new FrameLayout(ui.context());
        stage.setBackground(ui.shape(UmbraColors.BACKGROUND_SECONDARY, 22));
        LinearLayout remote = ui.column(); remote.setGravity(Gravity.CENTER); remote.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        VideoPresentation v = s.video();
        remote.addView(ui.iconView(v != null && v.remoteVisible() ? Glyph.VIDEO : Glyph.VIDEO_OFF, UmbraColors.TEXT_SECONDARY, 36));
        TextView status = ui.text(UmbraType.LABEL, v == null ? "Video desactivado" : v.remote(), v == null ? UmbraColors.TEXT_SECONDARY : Ui.toneColor(v.remoteTone()));
        status.setGravity(Gravity.CENTER); remote.addView(status, ui.margins(Ui.match(), 8, 8));
        if (v != null && v.remoteVisible()) {
            Button open = ui.button(Ui.ButtonKind.SECONDARY, "Ver video recibido", Glyph.VIDEO, a::showRemoteVideo);
            remote.addView(open, Ui.wrap());
            remote.addView(ui.text(UmbraType.CAPTION, "Se abre en una vista protegida; integrarla aquí está pendiente."));
        }
        stage.addView(remote, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ui.dp(260)));
        LinearLayout local = ui.column(); local.setGravity(Gravity.CENTER);
        local.setBackground(ui.outlined(UmbraColors.SURFACE_ELEVATED, UmbraColors.OUTLINE, 14));
        boolean cam = v != null && v.cameraTransmitting();
        local.addView(ui.iconView(cam ? Glyph.VIDEO : Glyph.VIDEO_OFF, cam ? UmbraColors.ACCENT_MUTED : UmbraColors.TEXT_TERTIARY, 22));
        TextView lt = ui.text(UmbraType.CAPTION, cam ? "Tu cámara" : "Cámara apagada"); lt.setGravity(Gravity.CENTER); local.addView(lt);
        local.setContentDescription(cam ? "Vista previa: tu cámara está transmitiendo" : "Vista previa: tu cámara no transmite");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ui.dp(92), ui.dp(120), Gravity.TOP | Gravity.END);
        lp.topMargin = ui.dp(10); lp.setMarginEnd(ui.dp(10));
        stage.addView(local, lp);
        stage.setLayoutParams(ui.margins(Ui.match(), 8, 0));
        return stage;
    }

    /** "Bruno solicita activar video." with the three explicit answers. Nothing turns the camera on implicitly. */
    public static LinearLayout videoConsent(Ui ui, String alias, CallActions a) {
        LinearLayout c = ui.elevatedCard();
        LinearLayout h = ui.row(); h.addView(ui.iconTile(Glyph.VIDEO, Tone.ACCENT));
        LinearLayout t = ui.column(); t.setPadding(ui.dp(12), 0, 0, 0);
        t.addView(ui.heading(UmbraType.HEADING, VideoPresentation.requestHeadline(alias)));
        t.addView(ui.text(UmbraType.CAPTION, "Tu cámara solo se enciende si eliges compartirla."));
        h.addView(t, Ui.weight()); c.addView(h);
        c.addView(ui.button(Ui.ButtonKind.SECONDARY, VideoPresentation.CONSENT_REJECT, Glyph.CLOSE, () -> a.answerVideo(2)));
        c.addView(ui.button(Ui.ButtonKind.SECONDARY, VideoPresentation.CONSENT_RECEIVE, Glyph.EYE, () -> a.answerVideo(0)));
        c.addView(ui.button(Ui.ButtonKind.PRIMARY, VideoPresentation.CONSENT_SHARE, Glyph.VIDEO, () -> a.answerVideo(1)));
        c.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        return c;
    }

    /** VOZ panel: Natural / Modulada. "Modulada" is highlighted only after the engine confirms ON. */
    public static LinearLayout modulatorPanel(Ui ui, ModulatorPresentation m, CallActions a) {
        LinearLayout c = ui.elevatedCard();
        c.addView(ui.text(UmbraType.SECURITY_LABEL, "Voz"));
        int selected = m.selected() == ModulatorPresentation.Mode.NATURAL ? 0 : m.selected() == ModulatorPresentation.Mode.MODULATED ? 1 : -1;
        c.addView(ui.segmented(new String[]{"Natural", "Modulada"}, selected, new boolean[]{true, !m.busy()}, i -> {
            if (i == 0 && m.selected() != ModulatorPresentation.Mode.NATURAL) a.natural();
            else if (i == 1 && m.selected() != ModulatorPresentation.Mode.MODULATED) a.modulated();
        }));
        LinearLayout st = ui.row(); st.setGravity(Gravity.TOP);
        st.addView(ui.iconView(m.glyph(), Ui.toneColor(m.tone()), 20));
        LinearLayout tx = ui.column(); tx.setPadding(ui.dp(10), 0, 0, 0);
        TextView head = ui.text(UmbraType.LABEL, m.headline(), m.tone() == Tone.NEUTRAL ? UmbraColors.TEXT_PRIMARY : Ui.toneColor(m.tone()));
        head.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        tx.addView(head); tx.addView(ui.text(UmbraType.CAPTION, m.detail()));
        st.addView(tx, Ui.weight()); c.addView(st, ui.margins(Ui.match(), 6, 4));
        if (m.state() == ModulatorPresentation.EngineState.ERROR_MUTED)
            c.addView(ui.button(Ui.ButtonKind.SECONDARY, "Reintentar modulación", Glyph.RETRY, a::retryModulation));
        c.addView(ui.text(UmbraType.CAPTION, ModulatorPresentation.DISCLAIMER, UmbraColors.TEXT_TERTIARY), ui.margins(Ui.match(), 6, 0));
        return c;
    }

    // ------------------------------------------------------------------ incoming
    public record IncomingState(String callId, String alias, TrustPresentation trust, boolean video) {}
    public interface IncomingActions { void reject(); void answer(); void later(); }

    public static Screen incoming(Ui ui, IncomingState s, IncomingActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::later, ui.titleBlock(s.video() ? "Videollamada entrante" : "Llamada entrante", null)));
        LinearLayout body = ui.column(); body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(ui.avatar(s.alias(), false, 112), ui.margins(new LinearLayout.LayoutParams(ui.dp(112), ui.dp(112)), 32, 0));
        TextView name = ui.heading(UmbraType.DISPLAY, "Llamada de " + s.alias()); name.setGravity(Gravity.CENTER);
        body.addView(name, ui.margins(Ui.match(), 16, 6));
        LinearLayout badge = ui.row(); badge.setGravity(Gravity.CENTER); badge.addView(ui.trustBadge(s.trust())); body.addView(badge, Ui.match());
        body.addView(ui.banner(Tone.NEUTRAL, Glyph.SHIELD, "Tú decides qué se enciende",
            s.video() ? "Responder abre solo la señalización y luego el audio con tu confirmación. La cámara requiere un consentimiento aparte."
                      : "Responder no enciende tu micrófono hasta que lo autorices.", null, null), ui.margins(Ui.match(), 20, 0));
        LinearLayout controls = ui.row(); controls.setGravity(Gravity.CENTER);
        controls.addView(spaced(ui, ui.callControl(Glyph.CALL_END, "Rechazar", null, false, true, true, a::reject)));
        boolean allowed = s.trust().allowsCalls();
        controls.addView(spaced(ui, ui.callControl(Glyph.CALL, "Responder", allowed ? null : "Contacto no verificado", true, false, allowed, a::answer)));
        return Screen.of(top, body, controls);
    }
}
