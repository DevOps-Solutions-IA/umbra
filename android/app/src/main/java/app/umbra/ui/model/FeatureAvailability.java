package app.umbra.ui.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * Single source of truth for what the interface may present as working. A feature is AVAILABLE only
 * when a real engine/service implementation exists in this build; otherwise its UI is shown as
 * "UI preparada · backend pendiente" (or hidden when the flavor excludes it) and cannot be triggered.
 */
public final class FeatureAvailability {
    public enum Status { AVAILABLE, PENDING_BACKEND, NOT_IN_FLAVOR }

    private final Map<Feature, Status> status = new EnumMap<>(Feature.class);
    private final boolean connected;

    private FeatureAvailability(boolean connected) { this.connected = connected; }

    /**
     * @param connected   compile-time {@code BuildConfig.ALLOW_RELAY}
     * @param callPlatform compile-time {@code CallPlatform.ENABLED} (false in offline)
     */
    public static FeatureAvailability forBuild(boolean connected, boolean callPlatform) {
        FeatureAvailability a = new FeatureAvailability(connected);
        for (Feature f : Feature.values()) a.status.put(f, Status.PENDING_BACKEND);
        a.status.put(Feature.DIRECT_MESSAGES, Status.AVAILABLE);
        a.status.put(Feature.FILES, Status.AVAILABLE);
        a.status.put(Feature.LOCATION_SHARING, Status.AVAILABLE);
        a.status.put(Feature.DEVICE_LIST, Status.AVAILABLE);
        a.status.put(Feature.DEVICE_REVOCATION, Status.AVAILABLE);
        a.status.put(Feature.NEARBY_BLUETOOTH, Status.AVAILABLE);
        boolean calls = connected && callPlatform;
        Status media = calls ? Status.AVAILABLE : Status.NOT_IN_FLAVOR;
        a.status.put(Feature.VOICE_CALLS, media);
        a.status.put(Feature.VIDEO_CALLS, media);
        a.status.put(Feature.VOICE_MODULATION, media);
        a.status.put(Feature.EMBEDDED_VIDEO_SURFACE, calls ? Status.PENDING_BACKEND : Status.NOT_IN_FLAVOR);
        a.status.put(Feature.RELAY_SYNC, connected ? Status.AVAILABLE : Status.NOT_IN_FLAVOR);
        return a;
    }

    public Status status(Feature feature) { return status.get(feature); }
    public boolean available(Feature feature) { return status.get(feature) == Status.AVAILABLE; }
    /** Offline builds must not even show entry points for connected-only features. */
    public boolean visible(Feature feature) { return status.get(feature) != Status.NOT_IN_FLAVOR; }
    public boolean connected() { return connected; }

    public static String label(Status status) {
        return switch (status) {
            case AVAILABLE -> "Disponible";
            case PENDING_BACKEND -> "UI preparada · Backend pendiente";
            case NOT_IN_FLAVOR -> "No incluido en la edición offline";
        };
    }
    public String label(Feature feature) { return label(status(feature)); }
}
