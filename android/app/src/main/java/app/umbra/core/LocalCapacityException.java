package app.umbra.core;

/** A local resource failure, NOT a malicious packet. Do not ACK/delete the relay's copy. */
public final class LocalCapacityException extends IllegalStateException {
    public LocalCapacityException() { super("Almacenamiento o cola llenos; libera espacio antes de recibir más mensajes"); }
}
