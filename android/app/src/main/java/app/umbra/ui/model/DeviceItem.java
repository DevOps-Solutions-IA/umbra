package app.umbra.ui.model;

/**
 * A device from the signed roster the engine stores. Names and "last activity" are not part of the
 * roster, so they are never invented: devices are identified by a short fingerprint of their id.
 */
public record DeviceItem(String id, String title, String detail, boolean current, boolean active, boolean revocable) {
    public static DeviceItem of(String id, boolean current, boolean active, boolean administrator) {
        String shortId = Fingerprints.shortId(id);
        String title = current ? "Este dispositivo" : "Dispositivo " + shortId;
        String detail = (current ? "ID " + shortId + " · " : "") + (active ? "Autorizado" : "Revocado");
        return new DeviceItem(id, title, detail, current, active, administrator && active && !current);
    }
}
