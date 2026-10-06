package app.umbra.ui.model;

/** Product capabilities the interface can show. Availability is decided by {@link FeatureAvailability}. */
public enum Feature {
    DIRECT_MESSAGES("Mensajes 1:1"),
    FILES("Archivos"),
    PHOTO_METADATA_CLEANING("Fotos sin metadatos"),
    MESSAGE_REPLY("Responder a un mensaje"),
    LOCATION_SHARING("Compartir ubicación"),
    VOICE_CALLS("Llamadas de voz"),
    VIDEO_CALLS("Videollamadas"),
    VOICE_MODULATION("Modulación local de voz"),
    EMBEDDED_VIDEO_SURFACE("Video en la llamada"),
    GROUP_CHAT("Grupos"),
    DEVICE_LIST("Lista de dispositivos"),
    DEVICE_REVOCATION("Revocar dispositivos"),
    DEVICE_LINKING_WIZARD("Vincular dispositivos"),
    NEARBY_BLUETOOTH("Cercanía por Bluetooth"),
    RELAY_SYNC("Sincronización privada"),
    QR_SCAN("Escanear QR"),
    PAIRING_ONLINE("Vincular por QR o código"),
    PAIRING_FILE("Vincular por archivo"),
    VAULT_PASSWORD("Contraseña personal"),
    PRIVATE_ADMISSION("Admisión de dispositivos"),
    PRIVATE_STARTUP("Inicio sin red"),
    EMERGENCY_LOCK("Bloqueo de emergencia"),
    NOTIFICATION_PRIVACY("Contenido de notificaciones"),
    CLIPBOARD_PROTECTION("Protección del portapapeles"),
    UNREAD_COUNTERS("Contadores de no leídos"),
    CONVERSATION_MUTE("Silenciar conversaciones"),
    RESTRICTED_IMAGE("Foto protegida"),
    RESTRICTED_AUDIO("Nota de voz protegida"),
    RESTRICTED_VIDEO("Video protegido"),
    RESTRICTED_PDF("PDF protegido"),
    RESTRICTED_CAPTURE("Grabar nota protegida");

    public final String title;
    Feature(String title) { this.title = title; }
}
