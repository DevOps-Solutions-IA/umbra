package app.umbra.accesslab;

import android.app.Activity;
import app.umbra.access.AccessSession;

/** Opt-in debug/vaultLab-only lifecycle driver. No UI, credential creation or authentication bypass. */
public final class AccessLifecycleHarness extends Activity {
    private AccessSession session;
    public void attach(AccessSession supplied) {
        if (session != null || supplied == null) throw new IllegalStateException("Invalid lab attachment");
        session = supplied;
    }
    public boolean attached() { return session != null; }
    @Override protected void onPause() {
        if (session != null) session.background();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (session != null) session.foreground();
    }
    // No saved-state support: recreation must never restore an access capability.
}
