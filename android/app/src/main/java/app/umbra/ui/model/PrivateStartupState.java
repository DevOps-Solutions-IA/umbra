package app.umbra.ui.model;

/**
 * Integration point for the future private startup (no network until unlocked). Not implemented:
 * {@link FeatureAvailability} keeps {@link Feature#PRIVATE_STARTUP} pending, so the UI only renders
 * {@link #UNAVAILABLE}. The dedicated security PR must supply the real state from the engine.
 */
public enum PrivateStartupState {
    /** No engine support in this build; nothing is claimed. */
    UNAVAILABLE,
    /** Reserved: vault locked and network stack not started (engine-reported only). */
    LOCKED_NO_NETWORK,
    /** Reserved: unlocked; network allowed by explicit user choice (engine-reported only). */
    UNLOCKED
}
