package app.umbra.ui.model;

/** Product capabilities the interface can show. Availability is decided by {@link FeatureAvailability}. */
public enum Feature {
    DIRECT_MESSAGES("Mensajes 1:1"),
    FILES("Archivos"),
    PHOTO_METADATA_CLEANING("Fotos sin metadatos (EXIF)"),
    MESSAGE_REPLY("Responder a un mensaje"),
    LOCATION_SHARING("Compartir ubicación"),
    VOICE_CALLS("Llamadas de voz"),
    VIDEO_CALLS("Videollamadas"),
    VOICE_MODULATION("Modulación local de voz"),
    EMBEDDED_VIDEO_SURFACE("Video integrado en la pantalla de llamada"),
    GROUP_CHAT("Grupos"),
    DEVICE_LIST("Lista de dispositivos"),
    DEVICE_REVOCATION("Revocar dispositivos"),
    DEVICE_LINKING_WIZARD("Asistente para vincular dispositivos"),
    NEARBY_BLUETOOTH("Conexión cercana por Bluetooth"),
    RELAY_SYNC("Sincronización por servidor privado"),
    QR_SCAN("Escanear QR con la cámara"),
    VAULT_PASSWORD("Contraseña personal de la bóveda"),
    PRIVATE_ADMISSION("Admisión privada de dispositivos"),
    PRIVATE_STARTUP("Inicio privado sin red"),
    EMERGENCY_LOCK("Bloqueo de emergencia"),
    NOTIFICATION_PRIVACY("Contenido de notificaciones"),
    CLIPBOARD_PROTECTION("Protección del portapapeles"),
    UNREAD_COUNTERS("Contadores de no leídos"),
    CONVERSATION_MUTE("Silenciar conversaciones");

    public final String title;
    Feature(String title) { this.title = title; }
}
