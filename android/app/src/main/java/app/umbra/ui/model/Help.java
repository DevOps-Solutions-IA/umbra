package app.umbra.ui.model;

import java.util.List;

/**
 * Explanations moved off the screens. A screen shows only its title, state, actions and one-line critical
 * warnings; the "ⓘ" button opens the matching topic as a short sheet. Every line is Spanish, at most one
 * sentence, and never contradicts what the domain enforces.
 */
public enum Help {
    ACCESS("Contraseña personal", List.of(
        "Se pide después del bloqueo de Android.",
        "Se usa tal como la escribes: sin recortar espacios.",
        "De 12 a 1024 bytes. El largo no garantiza fortaleza.",
        "No hay copia ni recuperación.",
        "Crear o cambiarla deja la bóveda bloqueada.")),
    AUTO_LOCK("Autobloqueo", List.of(
        "Vale solo mientras UMBRA siga abierta.",
        "Máximo 4 minutos.",
        "Salir de la app siempre bloquea.",
        "No es el bloqueo de emergencia.")),
    ADMISSION("Admisión", List.of(
        "Autoriza a este dispositivo en el entorno.",
        "Cada teléfono necesita la suya.",
        "Configurar el entorno no admite.",
        "Nada se envía solo: se comparten archivos.",
        "Estar admitido no verifica contactos.",
        "Otra autoridad se rechaza y se conserva la anterior.")),
    ADMIN("Administración", List.of(
        "Solo funciona en el teléfono que creó el entorno.",
        "El motor lo comprueba en cada operación.",
        "Compara las huellas con la persona antes de aprobar.",
        "La credencial dura como máximo 7 días.",
        "No hay recuperación ni rotación de la autoridad.")),
    REVOCATION("Revocación", List.of(
        "Impide nuevas sesiones cuando se conoce.",
        "Sin conexión, llega solo con el archivo firmado.",
        "No borra datos ya entregados.")),
    NETWORK("Red", List.of(
        "Desbloquear no conecta.",
        "Solo «Conectar» habilita la red.",
        "Si la red se pierde o cambia, no reconecta sola.",
        "Desconectar no bloquea la bóveda.",
        "Cercanía se activa aparte.")),
    NEARBY("Cercanía", List.of(
        "Bluetooth con un teléfono cercano.",
        "No escucha ni busca hasta que la actives.",
        "Un enlace a la vez; ambos con UMBRA abierta.",
        "Exige admisión vigente en ambos teléfonos.",
        "No es red de malla ni conexión a distancia.")),
    VERIFY("Verificación", List.of(
        "Comparen el código completo en persona.",
        "Sin verificar no se envían mensajes, ubicación ni llamadas.",
        "Si la identidad cambia, se bloquea el envío.",
        "El código no se copia al portapapeles.")),
    VOICE("Voz", List.of(
        "La modulación es local y cambia el timbre.",
        "No garantiza que no te reconozcan.",
        "«Modulada» solo aparece cuando el motor la confirma.",
        "Si falla, el micrófono queda silenciado.")),
    CALL("Llamada", List.of(
        "Micrófono y cámara piden confirmación aparte.",
        "El audio usa tu servidor autorizado.")),
    LOCATION("Ubicación", List.of(
        "Solo mientras UMBRA esté abierta y desbloqueada.",
        "Bloquear o salir la detiene.",
        "Antes de enviar verás los dispositivos que la recibirán.")),
    INVITATION("Invitaciones", List.of(
        "Se intercambian tres archivos: invitación, solicitud y confirmación.",
        "Un archivo no verifica a la persona.",
        "Los archivos exportados quedan fuera de la bóveda.")),
    DEVICES("Dispositivos", List.of(
        "Revocar es definitivo.",
        "El dispositivo deja de recibir mensajes nuevos.",
        "No borra lo que ya tenga guardado.")),
    STORAGE("Datos locales", List.of(
        "Todo se guarda cifrado en este teléfono.",
        "Sin copias de seguridad automáticas.",
        "La caducidad no impide copias del destinatario.")),
    PROTECTED("Contenido protegido", List.of(
        "Solo para un contacto verificado y admitido.",
        "Una vez: se consume al abrirse.",
        "Solo en UMBRA: se abre aquí hasta que caduque.",
        "No se exporta, comparte, reenvía ni imprime.",
        "Enviado significa en cola cifrada, no entregado.",
        "No impide fotos de la pantalla con otro equipo.")),
    EMERGENCY("Emergencia", List.of(
        "Oculta el contenido y cierra al instante.",
        "No pide contraseña.",
        "No avisa al servidor ni espera respuesta.",
        "Lo ya entregado no se retira.",
        "Para volver: desbloqueo completo.")),
    DEVELOPMENT("Versión de desarrollo", List.of(
        "Pendiente de auditoría independiente.",
        "No usar todavía para secretos reales."));

    public final String title;
    public final List<String> lines;
    Help(String title, List<String> lines) { this.title = title; this.lines = lines; }
}
