package app.umbra;

import app.umbra.ui.model.DeviceItem;
import org.junit.Test;
import static org.junit.Assert.*;

/** Devices are shown from the signed roster only; revocation never targets the current device. */
public class UiDevicePresentationTest {
    @Test public void devicesAreNeverGivenInventedNames() {
        DeviceItem current = DeviceItem.of("abcdef1234", true, true, true);
        assertEquals("Este dispositivo", current.title());
        assertFalse("the current device is not revocable from itself", current.revocable());
        DeviceItem other = DeviceItem.of("99887766aa", false, true, true);
        assertEquals("Dispositivo 99887766", other.title());
        assertTrue(other.revocable());
        assertFalse(DeviceItem.of("99887766aa", false, true, false).revocable());
        assertFalse(DeviceItem.of("99887766aa", false, false, true).revocable());
        assertTrue(DeviceItem.of("99887766aa", false, false, true).detail().contains("Revocado"));
    }
}
