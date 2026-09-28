package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import app.umbra.content.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import java.io.ByteArrayOutputStream;
import org.json.JSONObject;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/** Test APK only: real host force-stop AFTER consume commit and positive native render. */
public final class RestrictedRestartFixtureListener extends RunListener {
    private static void status(String result) {
        var status=new android.os.Bundle();status.putString("restrictedRestart",result);
        InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    @Override public void testRunStarted(Description description)throws Exception {
        String phase=InstrumentationRegistry.getArguments().getString("restrictedPhase","");
        if(phase.equals("prepare")) {
            // Not closed/reopened here: the runner must kill this process with its live decoder.
            var br=new SqliteDeviceRecords("restricted-restart",false);
            try(var ar=new SqliteDeviceRecords()) {
                var a=new Engine(ar);var b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
                Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff447799);
                var png=new ByteArrayOutputStream();
                try{if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,png))throw new AssertionError("Synthetic PNG missing");}
                finally{bitmap.recycle();}
                var review=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,60);
                String id=a.restricted().send(review,RestrictedImages.prepare(a,review,png.toByteArray(),true),true);
                JSONObject envelope=a.outbox().get(0).getJSONObject("envelope");b.receive(envelope);
                br.transaction(()->{br.put("synthetic-restart","envelope",Bytes.utf8(envelope.toString()));return null;});
                var session=b.restricted().open(b.restricted().reviewOpen(id),true);
                var decoder=new RestrictedImages.Decoder(session);
                Bitmap output=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
                decoder.render(new Canvas(output),new Rect(0,0,16,16));
                if(output.getPixel(8,8)!=0xff447799 || !b.restricted().status(id).consumed())throw new AssertionError("No positive consumed render");
                output.recycle();status("READY committed consumption and positive render; awaiting host force-stop");
                while(true)Thread.sleep(1000);
            }
        } else if(phase.equals("verify")) {
            // Synthetic SQLite adapter unlocks itself; this is NOT a production Keystore unlock test.
            try(var br=new SqliteDeviceRecords("restricted-restart",true)) {
                var b=new Engine(br);
                if(!b.initialized() || br.keys("restricted-state").size()!=1 || !br.keys("restricted-object").isEmpty())
                    throw new AssertionError("Consumed record or removed payload did not survive");
                String id=br.keys("restricted-state").get(0);
                b.receive(new JSONObject(Bytes.text(br.get("synthetic-restart","envelope"))));
                if(br.keys("restricted-state").size()!=1 || !br.keys("restricted-object").isEmpty() || !b.restricted().status(id).consumed())
                    throw new AssertionError("Duplicate restored consumed payload");
                try {b.restricted().open(b.restricted().reviewOpen(id),true);throw new AssertionError("Consumed object reopened");}
                catch(ContentException denied){if(denied.code()!=ContentException.Code.CONSUMED)throw denied;}
                status("PASS consumed PNG cannot reopen or revive after actual process force-stop");
            }
        } else throw new SecurityException("Specify synthetic restart phase");
    }
}
