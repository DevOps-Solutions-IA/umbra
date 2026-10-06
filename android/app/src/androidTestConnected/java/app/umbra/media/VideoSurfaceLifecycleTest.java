package app.umbra.media;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.ui.MainActivity;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android dialog lifecycle; does not claim camera, frames or codec execution. */
@RunWith(AndroidJUnit4.class)
public final class VideoSurfaceLifecycleTest {
    @Test public void trackingListenerCannotReplaceResourceCleanup() {
        var closed=new AtomicInteger();var tracked=new AtomicInteger();
        try(var activity=ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(owner->{
                var dialog=new MediaDialog(owner,closed::incrementAndGet);
                // Same listener replacement performed by MainActivity's dialog tracker.
                dialog.setOnDismissListener(ignored->tracked.incrementAndGet());
                dialog.show();dialog.dismiss();dialog.dismiss();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1,tracked.get());assertEquals(1,closed.get());
        }
    }
    @Test public void cancellationReleasesResourcesDespiteExternalDismissListener() {
        var closed=new AtomicInteger();
        try(var activity=ActivityScenario.launch(MainActivity.class)) {
            activity.onActivity(owner->{
                var dialog=new MediaDialog(owner,closed::incrementAndGet);
                dialog.setOnDismissListener(ignored->{});
                dialog.show();dialog.cancel();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1,closed.get());
        }
    }
}
