package app.umbra.ui.model;

/**
 * One row of the conversation list. Content previews are intentionally absent: the list shows who
 * and how trusted, not what was said. {@code unread}/{@code muted} stay 0/false until the engine
 * provides them (see {@link Feature#UNREAD_COUNTERS}, {@link Feature#CONVERSATION_MUTE}).
 */
public record ConversationItem(String id, String title, boolean group, TrustLevel trust, String subtitle,
                               String timeLabel, int unread, boolean muted, int members) {
    public static ConversationItem direct(String id, String alias, TrustLevel trust, String timeLabel) {
        TrustPresentation p = TrustPresentation.of(trust);
        String subtitle = switch (trust) {
            case VERIFIED -> "Verificado · cifrado de extremo a extremo";
            case UNVERIFIED -> "Verificación pendiente · envío bloqueado";
            case IDENTITY_CHANGED -> "La identidad cambió · verifica de nuevo";
            case BLOCKED -> p.label();
        };
        return new ConversationItem(id, alias, false, trust, subtitle, timeLabel, 0, false, 2);
    }
    public static ConversationItem group(String id, String name, int members, String timeLabel) {
        return new ConversationItem(id, name, true, TrustLevel.VERIFIED, members + " miembros", timeLabel, 0, false, members);
    }
    public String accessibilityLabel() {
        StringBuilder b = new StringBuilder(group ? "Grupo " : "Conversación con ").append(title).append(". ").append(subtitle);
        if (timeLabel != null && !timeLabel.isEmpty()) b.append(". ").append(timeLabel);
        if (unread > 0) b.append(". ").append(unread).append(unread == 1 ? " mensaje sin leer" : " mensajes sin leer");
        if (muted) b.append(". Silenciada");
        return b.toString();
    }
}
