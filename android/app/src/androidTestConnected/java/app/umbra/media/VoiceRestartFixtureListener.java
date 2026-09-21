package app.umbra.media;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.calls.CallPayload;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/** Reopens committed synthetic SQLite AFTER the host proved death of the active media process. */
public final class VoiceRestartFixtureListener extends RunListener {
    @Override public void testRunStarted(Description ignored) throws Exception {
        try(var records=new SqliteDeviceRecords("voice-restart",true)) {
            Engine engine=new Engine(records,SystemClock::elapsedRealtime);
            var file=InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir().toPath().resolve("synthetic-voice-public.json");
            if(java.nio.file.Files.size(file)>32000) throw new AssertionError("Oversized synthetic identity receipt");
            String identity=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8)).getString("identity");
            if(!identity.equals(engine.id())) throw new AssertionError("Restart replaced the committed identity");
            var sessions=engine.calls().sessions();
            if(sessions.size()!=1 || !CallPayload.TERMINAL.contains(sessions.get(0).getString("state")))
                throw new AssertionError("Restart restored a usable call session");
            String id=sessions.get(0).getJSONObject("context").getString("callId");
            try { engine.calls().reviewMedia(id,"a".repeat(64));throw new AssertionError("Restart restored old media consent"); }
            catch(SecurityException expected) { /* A fresh invitation and local consent are mandatory. */ }
            if(!identity.equals(engine.id())) throw new AssertionError("Restart replaced the identity");
            for(var delivery:engine.outbox()) if(delivery.has("callSession"))
                throw new AssertionError("Restart retained expired call signaling deliveries");
            Bundle status=new Bundle();status.putString("voiceRestart","PASS SQLite terminal state; no old consent or call outbox; native media never opened");
            InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
        }
    }
}
