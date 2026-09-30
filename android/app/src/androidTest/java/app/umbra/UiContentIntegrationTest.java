package app.umbra;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.content.ContentException;
import app.umbra.core.EmergencyLock;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import app.umbra.ui.design.ProtectedFrameView;
import app.umbra.ui.design.Ui;
import app.umbra.ui.flow.RestrictedFlow;
import app.umbra.ui.model.*;
import app.umbra.ui.model.RestrictedPresentation.Kind;
import app.umbra.ui.screens.ContentScreens;
import app.umbra.ui.screens.Screen;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * The Claude presentation path for protected content (UI flow {@link RestrictedFlow}, protected frame, viewer
 * screen) over the ACTUAL domain: Signal delivery, AES-GCM objects, SQLite, Android codecs and the emergency
 * coordinator, with synthetic in-process peers ({@link SqliteDeviceRecords}). The Activity's Android authentication
 * is not driven here (it needs a device credential); this is not a physical or hardware-Keystore acceptance.
 */
@RunWith(AndroidJUnit4.class)
public class UiContentIntegrationTest {
    private static final int W = 480, H = 320, BLUE = 0xff336699;

    private static byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888); bitmap.eraseColor(BLUE);
        try (var output = new ByteArrayOutputStream()) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); return output.toByteArray(); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        finally { bitmap.recycle(); }
    }
    private static byte[] pdf(int count) throws Exception {
        var document = new PdfDocument();
        try (var bytes = new ByteArrayOutputStream()) {
            for (int n = 0; n < count; n++) {
                var page = document.startPage(new PdfDocument.PageInfo.Builder(64, 48, n).create());
                page.getCanvas().drawColor(n % 2 == 0 ? Color.RED : Color.BLUE); document.finishPage(page);
            }
            document.writeTo(bytes); return bytes.toByteArray();
        } finally { document.close(); }
    }
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static Context themed() {
        Configuration c = new Configuration(context().getResources().getConfiguration());
        return new ContextThemeWrapper(context().createConfigurationContext(c), R.style.Theme_Umbra);
    }
    private static List<View> all(View root) {
        List<View> views = new ArrayList<>(); Deque<View> queue = new ArrayDeque<>(); queue.add(root);
        while (!queue.isEmpty()) { View v = queue.poll(); views.add(v); if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) queue.add(g.getChildAt(i)); }
        return views;
    }
    /** Sends through the UI send flow (reviewSend -> format adapter -> send with the same review) and delivers in-process. */
    private static String sendThroughUi(Engine from, Engine to, Kind kind, byte[] input, String mode, long session) throws Exception {
        var review = RestrictedFlow.reviewSend(from, to.id(), mode, 600, session);
        byte[] copy = input.clone();
        String id = RestrictedFlow.prepareAndSend(context(), from, review, kind, copy);
        assertTrue("the UI erases its caller-owned input copy", java.util.Arrays.equals(new byte[copy.length], copy));
        for (var row : from.outbox()) to.receive(row.getJSONObject("envelope"));
        return id;
    }
    private static RestrictedPresentation.Received listed(Engine receiver, Engine sender, String id) throws Exception {
        for (var st : receiver.restricted().received(sender.id()))
            if (st.id().equals(id)) return RestrictedPresentation.received(st.id(), st.format().name(), st.mode().name(), st.consumed(), st.expired(), "t");
        throw new AssertionError("not listed");
    }
    /** Mounts the real viewer screen with the frame on the UI thread and draws it; returns the root. */
    private static View mountViewer(Kind kind, String mode, ProtectedFrameView[] frame, Bitmap shown, int pages) {
        View[] out = new View[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Ui ui = new Ui(themed());
            frame[0] = new ProtectedFrameView(ui.context());
            Screen screen = ContentScreens.viewer(ui, new ContentScreens.ViewerState(kind, mode, "Bruno", "Abierto", Tone.ACCENT, "t", "Sesión limitada", 0, pages, false),
                frame[0], new ContentScreens.ViewerActions() { public void close() {} public void previous() {} public void next() {} public void emergency() {} }, new ContentScreens.Handles());
            LinearLayout root = screen.compose(ui);
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 2340);
            if (shown != null) frame[0].show(shown);
            out[0] = root;
        });
        return out[0];
    }
    private static int pixelOf(View view) {
        int[] color = new int[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Bitmap b = Bitmap.createBitmap(Math.max(1, view.getWidth()), Math.max(1, view.getHeight()), Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(b)); color[0] = b.getPixel(b.getWidth() / 2, b.getHeight() / 2); b.recycle();
        });
        return color[0];
    }
    private static void assertNoExportControls(View root) {
        for (View v : all(root)) if (v instanceof Button b) {
            String t = b.getText().toString().toLowerCase(Locale.ROOT);
            for (String forbidden : new String[]{"compartir", "guardar", "exportar", "imprimir", "reenviar", "copiar", "abrir con"})
                assertFalse("no " + forbidden + " control on a protected viewer", t.contains(forbidden));
        }
        for (View v : all(root)) if (v instanceof TextView t && !(v instanceof Button) && !Ui.technical(v)) assertNull(SpanishText.englishWord(t.getText().toString()));
    }

    @Test public void photoOnceOpensThroughTheUiFlowIntoAProtectedFrameAndIsConsumed() throws Exception {
        try (var ar = new SqliteDeviceRecords(); var br = new SqliteDeviceRecords()) {
            Engine a = new Engine(ar), b = new Engine(br); LocationAndroidTest.pair(a, b, ar, br);
            String id = sendThroughUi(a, b, Kind.PHOTO, png(), RestrictedPresentation.ONCE, 30);
            RestrictedPresentation.Received before = listed(b, a, id);
            assertTrue(before.canOpen()); assertEquals(Kind.PHOTO, before.kind()); assertEquals("Disponible", before.state());
            RestrictedFlow.Viewer viewer = RestrictedFlow.Viewer.open(b, RestrictedFlow.reviewOpen(b, id));
            try {
                assertEquals(Kind.PHOTO, viewer.kind);
                Bitmap frame = viewer.render(0, W, H, 1f, 0, 0);
                assertEquals(BLUE, frame.getPixel(W / 2, H / 2));
                Bitmap zoomed = viewer.render(0, W, H, 2f, 40, 0);   // zoom/pan inside the same session
                assertEquals(BLUE, zoomed.getPixel(W / 2, H / 2)); zoomed.recycle();
                frame.recycle();
                ProtectedFrameView[] view = new ProtectedFrameView[1];
                View root = mountViewer(Kind.PHOTO, RestrictedPresentation.ONCE, view, null, 1);
                // Same path as the Activity: render at the laid-out frame size on a worker, then show on the UI thread.
                Bitmap sized = viewer.render(0, view[0].getWidth(), view[0].getHeight(), 1f, 0, 0);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view[0].show(sized));
                assertEquals("the frame shown is the decoder output", BLUE, pixelOf(view[0]));
                assertNoExportControls(root);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(view[0]::clear);
                assertFalse("invalidation erases the presentation-owned frame", view[0].hasFrame());
                assertTrue(sized.isRecycled());
            } finally { viewer.close(); }
            viewer.closure().toCompletableFuture().get(3, TimeUnit.SECONDS);
            RestrictedPresentation.Received after = listed(b, a, id);
            assertFalse(after.canOpen()); assertEquals("Ya abierto", after.state());
            Exception again = assertThrows(ContentException.class, () -> RestrictedFlow.Viewer.open(b, RestrictedFlow.reviewOpen(b, id)));
            assertEquals("Ya se abrió.", FailurePresentation.text(again));
        }
    }

    @Test public void umbraOnlyReopensAfterTheSessionLimitButStaysNonExportable() throws Exception {
        try (var ar = new SqliteDeviceRecords(); var br = new SqliteDeviceRecords()) {
            Engine a = new Engine(ar), b = new Engine(br); LocationAndroidTest.pair(a, b, ar, br);
            String id = sendThroughUi(a, b, Kind.PHOTO, png(), RestrictedPresentation.UMBRA_ONLY, 1);
            RestrictedFlow.Viewer first = RestrictedFlow.Viewer.open(b, RestrictedFlow.reviewOpen(b, id));
            first.render(0, W, H, 1f, 0, 0).recycle();
            first.close(); first.closure().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertTrue("still openable: UMBRA_ONLY is not consumed", listed(b, a, id).canOpen());
            Thread.sleep(1_200); // the active-session deadline (F06) is distinct from the object expiry
            RestrictedFlow.Viewer second = RestrictedFlow.Viewer.open(b, RestrictedFlow.reviewOpen(b, id));
            try {
                Bitmap frame = second.render(0, W, H, 1f, 0, 0); assertEquals(BLUE, frame.getPixel(W / 2, H / 2)); frame.recycle();
                Thread.sleep(1_300);
                assertThrows("the session limit ends the presentation", SecurityException.class, second::check);
                assertThrows(SecurityException.class, () -> second.render(0, W, H, 1f, 0, 0));
            } finally { second.close(); }
            assertThrows("restricted buckets are never exported", ContentException.class, () -> b.get("restricted-object", id));
        }
    }

    @Test public void pdfNavigationStaysInsideOneOpening() throws Exception {
        try (var ar = new SqliteDeviceRecords(); var br = new SqliteDeviceRecords()) {
            Engine a = new Engine(ar), b = new Engine(br); LocationAndroidTest.pair(a, b, ar, br);
            String id = sendThroughUi(a, b, Kind.PDF, pdf(2), RestrictedPresentation.ONCE, 30);
            RestrictedFlow.Viewer viewer = RestrictedFlow.Viewer.open(b, RestrictedFlow.reviewOpen(b, id));
            try {
                assertEquals(2, viewer.pageCount());
                Bitmap page0 = viewer.render(0, W, H, 1f, 0, 0), page1 = viewer.render(1, W, H, 1f, 0, 0), back = viewer.render(0, W, H, 1.5f, 0, 0);
                assertEquals(Color.RED, page0.getPixel(W / 2, H / 2));
                assertEquals(Color.BLUE, page1.getPixel(W / 2, H / 2));
                assertEquals(Color.RED, back.getPixel(W / 2, H / 2));
                ProtectedFrameView[] view = new ProtectedFrameView[1];
                View root = mountViewer(Kind.PDF, RestrictedPresentation.ONCE, view, null, 2);
                Bitmap sized = viewer.render(1, view[0].getWidth(), view[0].getHeight(), 1f, 0, 0);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view[0].show(sized));
                assertEquals(Color.BLUE, pixelOf(view[0])); page1.recycle();
                boolean nav = false; for (View v : all(root)) if (v instanceof Button bt && "Siguiente".contentEquals(bt.getText())) nav = true;
                assertTrue("page navigation is offered", nav);
                assertNoExportControls(root);
                page0.recycle(); back.recycle();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(view[0]::clear);
            } finally { viewer.close(); }
            assertTrue("navigation did not create another opening", b.restricted().status(id).consumed());
        }
    }

    @Test public void lockDuringPresentationDeniesAndAStaleRenderIsRejected() throws Exception {
        try (var ar = new SqliteDeviceRecords(); var br = new SqliteDeviceRecords()) {
            Engine a = new Engine(ar), b = new Engine(br); LocationAndroidTest.pair(a, b, ar, br);
            String id = sendThroughUi(a, b, Kind.PHOTO, png(), RestrictedPresentation.ONCE, 30);
            RestrictedFlow.Viewer viewer = RestrictedFlow.Viewer.open(b, RestrictedFlow.reviewOpen(b, id));
            viewer.render(0, W, H, 1f, 0, 0).recycle();
            br.gate.lock();
            viewer.closure().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertThrows(SecurityException.class, () -> viewer.render(0, W, H, 1f, 0, 0));
            br.gate.unlock();
            assertThrows("a new unlock never revives the old session", SecurityException.class, () -> viewer.render(0, W, H, 1f, 0, 0));
            assertFalse("ONCE stays consumed", listed(b, a, id).canOpen());
        }
    }

    @Test public void emergencyDuringPresentationClosesItAndOnlyClosedAllowsNewAuthentication() throws Exception {
        try (var ar = new SqliteDeviceRecords(); var br = new SqliteDeviceRecords()) {
            Engine a = new Engine(ar), b = new Engine(br); LocationAndroidTest.pair(a, b, ar, br);
            String id = sendThroughUi(a, b, Kind.PHOTO, png(), RestrictedPresentation.UMBRA_ONLY, 30);
            RestrictedFlow.Viewer viewer = RestrictedFlow.Viewer.open(b, RestrictedFlow.reviewOpen(b, id));
            viewer.render(0, W, H, 1f, 0, 0).recycle();
            EmergencyLock.Status requested = b.emergencyLock();                  // same call as the UI button
            assertNotEquals(EmergencyLock.State.READY, requested.state());
            assertFalse(EmergencyPresentation.of(requested).allowsNewAuthentication() && requested.state() != EmergencyLock.State.CLOSED);
            assertThrows(SecurityException.class, () -> viewer.render(0, W, H, 1f, 0, 0));
            viewer.closure().toCompletableFuture().get(6, TimeUnit.SECONDS);
            long until = System.nanoTime() + 6_000_000_000L; EmergencyLock.Status status = b.emergency().status();
            while (!EmergencyPresentation.of(status).finished() && System.nanoTime() < until) { Thread.sleep(20); status = b.emergency().status(); }
            EmergencyPresentation shown = EmergencyPresentation.of(status);
            assertTrue(shown.finished());
            assertEquals(status.state() == EmergencyLock.State.CLOSED, shown.allowsNewAuthentication());
            boolean documents = false; for (var line : shown.lines()) if ("Contenido protegido".equals(line.subsystem())) documents = true;
            assertTrue("the open session is reported by the coordinator", documents);
            assertThrows("the gate stays denied without the new-authentication ticket", AccessGateDenied.class, () -> { try { br.gate.unlock(); } catch (app.umbra.core.AccessGate.LockedException e) { throw new AccessGateDenied(); } });
        }
    }
    private static final class AccessGateDenied extends RuntimeException {}
}
