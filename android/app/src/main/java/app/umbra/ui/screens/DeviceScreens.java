package app.umbra.ui.screens;

import android.view.Gravity;
import android.widget.*;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import java.util.List;

/** Devices (signed roster) and the location sharing sheet. */
public final class DeviceScreens {
    private DeviceScreens() {}

    public record ContactDevices(String alias, int approved) {}
    /**
     * @param rosterConfigured false when this identity has no signed device roster yet
     * @param problem          human message when the stored roster could not be read (never hidden)
     */
    public record DevicesState(List<DeviceItem> own, boolean rosterConfigured, String problem, List<ContactDevices> contacts, FeatureAvailability features) {}
    public interface DevicesActions { void back(); void revoke(DeviceItem device); void add(); }

    public static Screen devices(Ui ui, DevicesState s, DevicesActions a) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(a::back, ui.titleBlock("Dispositivos", null), ui.helpButton(Help.DEVICES)));
        LinearLayout body = ui.column();
        if (s.problem() != null) body.addView(ui.errorState(ErrorPresentation.of(ErrorKind.GENERIC).withBody(s.problem()), null, false));
        if (!s.rosterConfigured())
            body.addView(ui.banner(Tone.NEUTRAL, Glyph.INFO, "Solo este dispositivo", null, null, null));
        for (DeviceItem d : s.own()) {
            LinearLayout trailing = ui.column(); trailing.setGravity(Gravity.END);
            Glyph state = d.current() ? Glyph.DEVICE_CURRENT : d.active() ? Glyph.DEVICE_AUTHORIZED : Glyph.DEVICE_REVOKED;
            Tone tone = d.current() ? Tone.ACCENT : d.active() ? Tone.VERIFIED : Tone.BLOCKED;
            trailing.addView(ui.chip(tone, state, d.current() ? "Este dispositivo" : d.active() ? "Autorizado" : "Revocado"));
            LinearLayout row = ui.listRow(ui.iconTile(state, d.current() ? Tone.ACCENT : Tone.NEUTRAL), d.title(), d.detail(), trailing, null);
            body.addView(row);
            if (d.revocable()) {
                Button revoke = ui.button(Ui.ButtonKind.DESTRUCTIVE, "Revocar", Glyph.DEVICE_REVOKED, () -> a.revoke(d));
                body.addView(revoke);
                if (!s.features().available(Feature.DEVICE_REVOCATION)) {
                    ui.disabled(revoke, "próximamente");
                    body.addView(ui.pendingChip());
                }
            }
            body.addView(ui.divider());
        }
        Button add = ui.button(Ui.ButtonKind.SECONDARY, "Agregar", Glyph.ADD, a::add);
        if (!s.features().available(Feature.DEVICE_LINKING_WIZARD)) { ui.disabled(add, "próximamente"); body.addView(add); body.addView(ui.pendingChip()); }
        else body.addView(add);
        if (!s.contacts().isEmpty()) {
            body.addView(ui.sectionHeader("De tus contactos"));
            for (ContactDevices c : s.contacts())
                body.addView(ui.listRow(ui.avatar(c.alias(), false, 40), c.alias(),
                    c.approved() < 0 ? "Sin lista aprobada" : c.approved() + (c.approved() == 1 ? " dispositivo" : " dispositivos"), null, null));
        }
        return Screen.of(top, body, null);
    }

    // ------------------------------------------------------------------ location sheet
    public record LocationSheetState(String alias, boolean live, LocationShareDraft.Precision precision, int durationIndex) {}
    public interface LocationActions { void live(boolean live); void precision(LocationShareDraft.Precision p); void duration(int index); void review(String lat, String lon); void stopAll(); }

    /** Precision, live/one-off, duration and a plain summary before the engine review. */
    public static LinearLayout locationSheet(Ui ui, LocationSheetState s, LocationActions a) {
        LinearLayout box = ui.column();
        LinearLayout head = ui.row(); head.addView(ui.heading(UmbraType.TITLE, "Ubicación"), Ui.weight()); head.addView(ui.helpButton(Help.LOCATION));
        box.addView(head, ui.margins(Ui.match(), 0, 8));
        box.addView(ui.segmented(new String[]{"Un punto", "En vivo"}, s.live() ? 1 : 0, null, i -> a.live(i == 1)));
        box.addView(ui.sectionHeader("Precisión"));
        for (LocationShareDraft.Precision p : LocationShareDraft.Precision.values()) {
            if (s.live() && p == LocationShareDraft.Precision.MANUAL) continue;
            boolean on = p == s.precision();
            RadioButton radio = new RadioButton(ui.context()); radio.setChecked(on); radio.setClickable(false);
            radio.setButtonTintList(android.content.res.ColorStateList.valueOf(UmbraColors.ACCENT_MUTED));
            radio.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout row = ui.listRow(radio, p.label, p.detail, ui.iconView(precisionGlyph(p), on ? UmbraColors.ACCENT_MUTED : UmbraColors.TEXT_SECONDARY, 24), () -> a.precision(p));
            row.setStateDescription(on ? "Seleccionado" : "No seleccionado");
            box.addView(row);
        }
        EditText lat = null, lon = null;
        if (s.precision() == LocationShareDraft.Precision.MANUAL) {
            lat = ui.field("4.6097"); lon = ui.field("-74.0817");
            lat.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            lon.setInputType(lat.getInputType());
            box.addView(ui.labeledField("Latitud", lat)); box.addView(ui.labeledField("Longitud", lon));
        }
        long seconds = LocationShareDraft.SINGLE_POINT_SECONDS;
        if (s.live()) {
            box.addView(ui.sectionHeader("Duración"));
            box.addView(ui.segmented(new String[]{"15 min", "1 hora", "8 horas"}, s.durationIndex(), null, a::duration));
            seconds = LocationShareDraft.LIVE_DURATIONS[s.durationIndex()];
        }
        LocationShareDraft draft = new LocationShareDraft(s.precision(), s.live(), seconds);
        LinearLayout summary = ui.card();
        for (String[] line : draft.summary(s.alias(), 0)) {
            summary.addView(ui.text(UmbraType.SECURITY_LABEL, line[0]), ui.margins(Ui.match(), 6, 0));
            summary.addView(ui.text(UmbraType.BODY, line[1]));
        }
        box.addView(summary);
        final EditText flat = lat, flon = lon;
        box.addView(ui.button(Ui.ButtonKind.PRIMARY, "Revisar", Glyph.LOCATION,
            () -> a.review(flat == null ? null : flat.getText().toString(), flon == null ? null : flon.getText().toString())));
        box.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Detener todo", Glyph.LOCATION_OFF, a::stopAll));
        return box;
    }

    /** Distinct glyph per precision so the options are not four identical pins. */
    public static Glyph precisionGlyph(LocationShareDraft.Precision p) {
        return switch (p) {
            case PRECISE -> Glyph.LOCATION_PRECISE;
            case APPROXIMATE -> Glyph.LOCATION_APPROX;
            case ZONE -> Glyph.LOCATION_ZONE;
            case MANUAL -> Glyph.LOCATION;
        };
    }
}
