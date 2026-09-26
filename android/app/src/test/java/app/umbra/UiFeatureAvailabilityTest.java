package app.umbra;

import app.umbra.ui.model.*;
import app.umbra.ui.model.FeatureAvailability.Status;
import org.junit.Test;
import static org.junit.Assert.*;

/** No capability is presented as working without a real implementation in the build. */
public class UiFeatureAvailabilityTest {
    private static final Feature[] PENDING_SECURITY = {Feature.GROUP_CHAT, Feature.VAULT_PASSWORD, Feature.PRIVATE_ADMISSION,
        Feature.PRIVATE_STARTUP, Feature.EMERGENCY_LOCK, Feature.NOTIFICATION_PRIVACY, Feature.CLIPBOARD_PROTECTION,
        Feature.PHOTO_METADATA_CLEANING, Feature.QR_SCAN, Feature.MESSAGE_REPLY, Feature.DEVICE_REVOCATION, Feature.DEVICE_LINKING_WIZARD};

    @Test public void plannedSecurityFeaturesAreNeverAvailable() {
        for (FeatureAvailability a : new FeatureAvailability[]{FeatureAvailability.forBuild(true, true), FeatureAvailability.forBuild(false, false)})
            for (Feature f : PENDING_SECURITY) {
                assertFalse(f.name(), a.available(f));
                assertEquals(f.name(), "UI preparada · Backend pendiente", a.label(f));
            }
    }
    @Test public void offlineExcludesInternetMediaAndRelay() {
        FeatureAvailability offline = FeatureAvailability.forBuild(false, false);
        for (Feature f : new Feature[]{Feature.VOICE_CALLS, Feature.VIDEO_CALLS, Feature.VOICE_MODULATION, Feature.RELAY_SYNC, Feature.EMBEDDED_VIDEO_SURFACE}) {
            assertEquals(f.name(), Status.NOT_IN_FLAVOR, offline.status(f));
            assertFalse(f.name(), offline.visible(f));
        }
        assertTrue(offline.available(Feature.NEARBY_BLUETOOTH));
        assertTrue(offline.available(Feature.DIRECT_MESSAGES));
        assertFalse(offline.connected());
    }
    @Test public void connectedExposesImplementedMediaOnly() {
        FeatureAvailability connected = FeatureAvailability.forBuild(true, true);
        assertTrue(connected.available(Feature.VOICE_CALLS));
        assertTrue(connected.available(Feature.VIDEO_CALLS));
        assertTrue(connected.available(Feature.VOICE_MODULATION));
        assertTrue(connected.available(Feature.RELAY_SYNC));
        assertEquals(Status.PENDING_BACKEND, connected.status(Feature.EMBEDDED_VIDEO_SURFACE));
    }
    @Test public void connectedWithoutCallPlatformDoesNotShowCalls() {
        FeatureAvailability a = FeatureAvailability.forBuild(true, false);
        assertFalse(a.visible(Feature.VOICE_CALLS));
    }
    @Test public void privateStartupHasNoClaimedState() {
        assertEquals(PrivateStartupState.UNAVAILABLE, PrivateStartupState.values()[0]);
    }
}
