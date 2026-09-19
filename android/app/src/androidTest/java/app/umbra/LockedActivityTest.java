package app.umbra;

import android.app.KeyguardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.ui.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Locked UI lifecycle on a disposable device with no device PIN; does not unlock the production vault. */
@RunWith(AndroidJUnit4.class)
public class LockedActivityTest {
    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView text && expected.contentEquals(text.getText())) return true;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) if (containsText(group.getChildAt(i), expected)) return true;
        return false;
    }
    private static void requireSyntheticDevice() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Only a debug application may run this fixture", BuildConfig.DEBUG);
        assertFalse("Use a disposable device without a PIN; do not alter a user's device credentials",
            context.getSystemService(KeyguardManager.class).isDeviceSecure());
        assertFalse("Use clean synthetic app data", context.getDatabasePath("umbra.db").exists());
    }
    private static void assertLocked(MainActivity activity) {
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertTrue(containsText(activity.getWindow().getDecorView(), "Bóveda bloqueada"));
        assertFalse(activity.getDatabasePath("umbra.db").exists());
    }
    @Test public void launchUsesSecureWindowAndRemainsLockedWithoutDeviceCredential() {
        requireSyntheticDevice();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(LockedActivityTest::assertLocked);
        }
    }
    @Test public void recreationKeepsLockedUiAndDoesNotInitializeIdentity() {
        requireSyntheticDevice();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.recreate(); scenario.onActivity(LockedActivityTest::assertLocked);
        }
    }
    @Test public void backgroundAndResumeRemainLocked() {
        requireSyntheticDevice();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(LockedActivityTest::assertLocked);
        }
    }
}
