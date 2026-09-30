package app.umbra.ui.screens;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.umbra.ui.design.UmbraColors;
import app.umbra.ui.design.UmbraType;
import app.umbra.ui.design.Ui;
import app.umbra.ui.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home: bottom navigation plus the Chats, Calls, Nearby and Settings destinations. */
public final class HomeScreens {
    private HomeScreens() {}

    public interface TabActions { void select(HomeTab tab); }

    public static View bottomNav(Ui ui, FeatureAvailability features, HomeTab current, TabActions a) {
        List<HomeTab> tabs = HomeTab.visible(features);
        List<Ui.NavItem> items = new ArrayList<>();
        for (HomeTab t : tabs) items.add(new Ui.NavItem(t.label, t.glyph));
        return ui.bottomNav(items, Math.max(0, tabs.indexOf(current)), index -> a.select(tabs.get(index)));
    }

    // ------------------------------------------------------------------ Chats
    public enum Filter { ALL, PEOPLE, GROUPS }
    public record IncomingCall(String callId, String alias) {}
    public record ChatsState(List<ConversationItem> conversations, boolean loading, Filter filter, FeatureAvailability features,
                             boolean offlineEdition, ConnectivityPresentation connectivity, AdmissionPresentation admission,
                             String transportNotice, IncomingCall incoming) {}
    public interface ChatsActions {
        void open(ConversationItem item); void newMessage(); void newGroup(); void addContact();
        void filter(Filter filter); void openIncoming(String callId); void networkDetails(); void admission();
    }

    public static Screen chats(Ui ui, ChatsState s, ChatsActions a, View nav) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(null, ui.titleBlock("Chats", ui.connectionChip(s.connectivity())),
            ui.iconButton(Glyph.PERSON_ADD, "Agregar contacto", a::addContact),
            ui.iconButton(Glyph.ADD, "Nuevo mensaje", a::newMessage)));
        if (s.incoming() != null)
            top.addView(ui.banner(Tone.ACCENT, Glyph.CALL, "Llamada de " + s.incoming().alias(), null,
                "Ver", () -> a.openIncoming(s.incoming().callId())));
        // Local chats stay available; admission only gates the realm's network and Nearby operations.
        if (s.admission() != null && !s.admission().admitted())
            top.addView(ui.banner(s.admission().tone(), s.admission().glyph(), s.admission().title(), null, "Admisión", a::admission));
        if (s.transportNotice() != null)
            top.addView(ui.banner(Tone.WARNING, Glyph.NETWORK_OFF, s.transportNotice(), null, "Detalles", a::networkDetails));
        top.addView(ui.segmented(new String[]{"Todos", "Personas", "Grupos"}, s.filter().ordinal(), null, i -> a.filter(Filter.values()[i])));

        if (s.loading()) return Screen.of(top, ui.skeleton(5), nav);
        List<ConversationItem> shown = new ArrayList<>();
        for (ConversationItem c : s.conversations())
            if (s.filter() == Filter.ALL || (s.filter() == Filter.GROUPS) == c.group()) shown.add(c);

        if (s.filter() == Filter.GROUPS && shown.isEmpty()) {
            LinearLayout empty = ui.emptyState(Glyph.GROUP, "Grupos", null, null, null);
            empty.addView(ui.pendingChip());
            empty.addView(ui.button(Ui.ButtonKind.SECONDARY, "Vista previa", Glyph.GROUP, a::newGroup));
            return Screen.of(top, empty, nav);
        }
        if (shown.isEmpty())
            return Screen.of(top, ui.emptyState(Glyph.CHAT, "Sin conversaciones", null, "Agregar contacto", a::addContact), nav);

        EditText search = ui.field("Buscar");
        search.setCompoundDrawablesRelative(ui.icon(Glyph.SEARCH, UmbraColors.TEXT_TERTIARY, 20), null, null, null);
        search.setCompoundDrawablePadding(ui.dp(10)); search.setSingleLine(true);
        search.setContentDescription("Buscar conversaciones por alias");
        List<ConversationItem> filtered = new ArrayList<>(shown);
        ListView list = Lists.of(ui, filtered, c -> conversationRow(ui, c, a), false);
        list.setContentDescription("Conversaciones");
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence t, int st, int c, int af) {}
            public void onTextChanged(CharSequence t, int st, int b, int c) {
                String q = t.toString().toLowerCase(Locale.ROOT); filtered.clear();
                for (ConversationItem item : shown) if (item.title().toLowerCase(Locale.ROOT).contains(q)) filtered.add(item);
                ((RowAdapter<?>) list.getAdapter()).notifyDataSetChanged();
            }
            public void afterTextChanged(Editable e) {}
        });
        top.addView(search);
        return Screen.list(top, list, nav);
    }

    public static View conversationRow(Ui ui, ConversationItem c, ChatsActions a) {
        LinearLayout trailing = ui.column(); trailing.setGravity(android.view.Gravity.END);
        if (c.timeLabel() != null && !c.timeLabel().isEmpty()) trailing.addView(ui.text(UmbraType.CAPTION, c.timeLabel()));
        if (c.unread() > 0) trailing.addView(ui.badge(c.unread()));
        if (c.muted()) trailing.addView(ui.iconView(Glyph.BELL_OFF, UmbraColors.TEXT_TERTIARY, 16));
        LinearLayout row = ui.listRow(ui.avatar(c.title(), c.group(), 48), c.title(), null, trailing, () -> a.open(c));
        LinearLayout texts = (LinearLayout) row.getChildAt(1);
        LinearLayout status = ui.row();
        if (!c.group()) {
            TrustPresentation p = TrustPresentation.of(c.trust());
            status.addView(ui.iconView(p.glyph(), Ui.toneColor(p.tone()), 14));
            TextView sub = ui.text(UmbraType.CAPTION, c.subtitle(), c.trust() == TrustLevel.VERIFIED ? UmbraColors.TEXT_SECONDARY : Ui.toneColor(p.tone()));
            sub.setPadding(ui.dp(6), 0, 0, 0); status.addView(sub, Ui.weight());
        } else {
            status.addView(ui.iconView(Glyph.GROUP, UmbraColors.TEXT_SECONDARY, 14));
            TextView sub = ui.text(UmbraType.CAPTION, c.subtitle()); sub.setPadding(ui.dp(6), 0, 0, 0); status.addView(sub, Ui.weight());
        }
        texts.addView(status, ui.margins(Ui.match(), 3, 0));
        row.setContentDescription(c.accessibilityLabel());
        return row;
    }

    // ------------------------------------------------------------------ Calls
    public record CallRow(String callId, String alias, CallPresentation state, String time, boolean video) {}
    public interface CallsActions { void open(CallRow row); void startFromChats(); }

    public static Screen calls(Ui ui, List<CallRow> rows, boolean activeSession, CallsActions a, View nav) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(null, ui.titleBlock("Llamadas", ui.chip(Tone.NEUTRAL, Glyph.SHIELD, "Solo verificados")), ui.helpButton(Help.CALL)));
        if (rows.isEmpty()) {
            return Screen.of(top, ui.emptyState(Glyph.CALL, "Sin llamadas", null, "Ir a chats", a::startFromChats), nav);
        }
        ListView list = Lists.of(ui, rows, r -> {
            LinearLayout trailing = ui.column();
            trailing.addView(ui.chip(r.state().tone(), r.state().incoming() ? Glyph.CALL : r.state().live() ? Glyph.MIC : Glyph.INFO, r.state().phase()));
            View row = ui.listRow(ui.avatar(r.alias(), false, 44), r.alias(), r.time(), trailing, () -> a.open(r));
            row.setContentDescription(r.alias() + ". " + r.state().phase() + (r.time() == null ? "" : ". " + r.time()));
            return row;
        }, false);
        if (activeSession) top.addView(ui.banner(Tone.SUCCESS, Glyph.MIC, "Llamada en curso", null, null, null));
        return Screen.list(top, list, nav);
    }

    // ------------------------------------------------------------------ Nearby (Bluetooth)
    /**
     * @param nearbyActive domain {@code isNearbySessionAllowed()}; the radio controls stay disabled until the
     *                     person explicitly starts Nearby, which never happens on open, unlock or connect.
     */
    public record NearbyState(String transportStatus, boolean offlineEdition, ConnectivityPresentation connectivity,
                              boolean nearbyActive, boolean admitted) {}
    public interface NearbyActions {
        void startNearby(); void stopNearby(); void listen(); void makeVisible(); void connectVerified();
        void enrollNew(); void systemSettings(); void networkSettings();
    }

    public static Screen nearby(Ui ui, NearbyState s, NearbyActions a, View nav) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(null, ui.titleBlock("Cerca", ui.chip(s.nearbyActive() ? Tone.OFFLINE : Tone.NEUTRAL,
            s.offlineEdition() ? Glyph.OFFLINE_BLUETOOTH : Glyph.BLUETOOTH, s.nearbyActive() ? "Cercanía activa" : "Cercanía detenida")), ui.helpButton(Help.NEARBY)));
        LinearLayout body = ui.column();
        if (s.nearbyActive()) {
            TextView st = ui.text(UmbraType.HEADING, s.transportStatus());
            st.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            body.addView(st, ui.margins(Ui.match(), 4, 8));
            body.addView(ui.button(Ui.ButtonKind.DESTRUCTIVE, "Detener cercanía", Glyph.STOP, a::stopNearby));
        } else {
            if (!s.admitted()) body.addView(ui.banner(Tone.WARNING, Glyph.DEVICE_PENDING, "Requiere admisión", null, null, null));
            Button start = ui.button(Ui.ButtonKind.PRIMARY, "Activar cercanía", Glyph.BLUETOOTH, a::startNearby);
            if (!s.admitted()) ui.disabled(start, "requiere admisión vigente");
            body.addView(start);
        }
        body.addView(ui.sectionHeader("Contactos verificados"));
        Button listen = ui.button(Ui.ButtonKind.SECONDARY, "Esperar contacto", Glyph.BLUETOOTH, a::listen);
        Button connect = ui.button(Ui.ButtonKind.SECONDARY, "Conectar contacto", Glyph.CHEVRON, a::connectVerified);
        body.addView(listen); body.addView(connect);
        body.addView(ui.sectionHeader("Contacto nuevo"));
        Button enroll = ui.button(Ui.ButtonKind.SECONDARY, "Vincular", Glyph.PERSON_ADD, a::enrollNew);
        Button visible = ui.button(Ui.ButtonKind.SECONDARY, "Visible 120 s", Glyph.EYE, a::makeVisible);
        body.addView(enroll); body.addView(visible);
        if (!s.nearbyActive()) for (Button b : new Button[]{listen, connect, enroll, visible}) ui.disabled(b, "activa cercanía");
        body.addView(ui.button(Ui.ButtonKind.GHOST, "Emparejar en Android", Glyph.SETTINGS, a::systemSettings));
        if (!s.offlineEdition()) {
            body.addView(ui.sectionHeader("Red"));
            body.addView(ui.listRow(ui.iconTile(s.connectivity().glyph(), s.connectivity().tone()), s.connectivity().title(), null, ui.chevron(), a::networkSettings));
        }
        return Screen.of(top, body, nav);
    }

    // ------------------------------------------------------------------ Settings root
    public interface SettingsActions { void open(SettingsSection section); void lockNow(); }

    public static Screen settings(Ui ui, String alias, ConnectivityPresentation connectivity, SettingsActions a, View nav) {
        LinearLayout top = ui.column();
        top.addView(ui.topBar(null, ui.titleBlock("Ajustes", ui.connectionChip(connectivity))));
        LinearLayout body = ui.column();
        LinearLayout me = ui.listRow(ui.avatar(alias, false, 52), alias, "Perfil", ui.chevron(), () -> a.open(SettingsSection.PROFILE));
        body.addView(me);
        body.addView(ui.divider());
        for (SettingsSection section : SettingsSection.values()) {
            if (section == SettingsSection.PROFILE) continue;
            body.addView(ui.listRow(ui.iconTile(section.glyph, Tone.NEUTRAL), section.title, section.subtitle, ui.chevron(), () -> a.open(section)));
        }
        body.addView(ui.button(Ui.ButtonKind.SECONDARY, "Bloquear ahora", Glyph.LOCK, a::lockNow));
        return Screen.of(top, body, nav);
    }
}
