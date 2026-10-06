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
    private static void stage(String name) {
        var status=new android.os.Bundle();
        status.putString("restrictedRestartStage",name);
        status.putLong("restrictedRestartElapsedMs",android.os.SystemClock.elapsedRealtime());
        InstrumentationRegistry.getInstrumentation().sendStatus(0,status);
    }
    @Override public void testRunStarted(Description description)throws Exception {
        String phase=InstrumentationRegistry.getArguments().getString("restrictedPhase","");
        if(phase.equals("prepare")) {
            stage("PREPARE_ENTER");
            // Not closed/reopened here: the runner must kill this process with its live decoder.
            var br=new SqliteDeviceRecords("restricted-restart",false);
            try(var ar=new SqliteDeviceRecords()) {
                var a=new Engine(ar);var b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
                stage("PNG_PREPARE_ENTER");
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
                output.recycle();
                stage("PNG_PREPARE_DONE");
                // Independent formats share the same persisted consume contract. No audible
                // playback: observe synthetic AAC after the real native decoder instead.
                stage("AUDIO_PREPARE_ENTER");
                var audioReview=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,60);
                String audioId;
                try(var prepared=SyntheticRestrictedAudio.sanitizedTone(a,audioReview)) {
                    audioId=a.restricted().send(audioReview,prepared,true);
                }
                stage("AUDIO_PREPARE_DONE");
                stage("PDF_PREPARE_ENTER");
                var pdfReview=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,60);
                String pdfId;
                try(var prepared=SyntheticDocuments.prepare(InstrumentationRegistry.getInstrumentation().getTargetContext(),a,pdfReview,0xff447799)) {
                    pdfId=a.restricted().send(pdfReview,prepared,true);
                }
                stage("PDF_PREPARE_DONE");
                var videoReview=a.restricted().reviewSend(b.id(),RestrictedPayload.Mode.ONCE,600,60);
                String videoId;
                stage("VIDEO_PREPARE_ENTER");
                try(var prepared=SyntheticRestrictedVideo.prepare(InstrumentationRegistry.getInstrumentation().getTargetContext(),a,videoReview)) {
                    stage("VIDEO_PREPARE_DONE");
                    stage("VIDEO_SEND_ENTER");
                    videoId=a.restricted().send(videoReview,prepared,true);
                    stage("VIDEO_SEND_DONE");
                }
                stage("RECEIVE_ENTER");
                for(var row:a.outbox()) {
                    var wire=row.getJSONObject("envelope");b.receive(wire);
                    br.transaction(()->{br.put("synthetic-restart",wire.getString("id"),Bytes.utf8(wire.toString()));return null;});
                }
                stage("RECEIVE_DONE");
                stage("AUDIO_OBSERVE_ENTER");
                var audioSession=b.restricted().open(b.restricted().reviewOpen(audioId),true);
                var observation=SyntheticRestrictedAudio.observe(audioSession);
                if(observation.samples()<16000 || observation.rms()<1000 || observation.targetEnergy()<=observation.otherEnergy()*10)
                    throw new AssertionError("No positive synthetic AAC decode");
                stage("AUDIO_OBSERVE_DONE");
                stage("PDF_RENDER_ENTER");
                var pdfSession=b.restricted().open(b.restricted().reviewOpen(pdfId),true);
                var pdfDecoder=new RestrictedDocuments.Decoder(pdfSession);
                Bitmap page=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888);
                try {
                    pdfDecoder.render(0,new Canvas(page),new Rect(0,0,32,24));
                    if(page.getPixel(12,12)!=0xff447799)throw new AssertionError("No positive PDF render");
                }finally{page.recycle();}
                stage("PDF_RENDER_DONE");
                stage("VIDEO_OBSERVE_ENTER");
                var videoSession=b.restricted().open(b.restricted().reviewOpen(videoId),true);
                SyntheticRestrictedVideo.observeAndClose(videoSession);
                stage("VIDEO_OBSERVE_DONE");
                if(br.keys("restricted-state").size()!=4 || !br.keys("restricted-object").isEmpty())
                    throw new AssertionError("Four consumed formats required before force-stop");
                status("READY formats=PNG,AAC_ADTS,PDF_PAGES,AVC_MP4 committed consumption and positive decode/render; awaiting host force-stop");
                while(true)Thread.sleep(1000);
            }
        } else if(phase.equals("verify")) {
            // Synthetic SQLite adapter unlocks itself; this is NOT a production Keystore unlock test.
            try(var br=new SqliteDeviceRecords("restricted-restart",true)) {
                var b=new Engine(br);
                if(!b.initialized() || br.keys("restricted-state").size()!=4 || !br.keys("restricted-object").isEmpty())
                    throw new AssertionError("Consumed record or removed payload did not survive");
                var formats=java.util.EnumSet.noneOf(RestrictedPayload.Format.class);
                for(String key:br.keys("synthetic-restart"))
                    b.receive(new JSONObject(Bytes.text(br.get("synthetic-restart",key))));
                if(br.keys("restricted-state").size()!=4 || !br.keys("restricted-object").isEmpty())
                    throw new AssertionError("Duplicate restored consumed payload");
                for(String id:br.keys("restricted-state")) {
                    var item=b.restricted().status(id);formats.add(item.format());
                    if(!item.consumed())throw new AssertionError("Consumption not durable");
                    try {b.restricted().open(b.restricted().reviewOpen(id),true);throw new AssertionError("Consumed object reopened");}
                    catch(ContentException denied){if(denied.code()!=ContentException.Code.CONSUMED)throw denied;}
                }
                if(!formats.equals(java.util.EnumSet.of(RestrictedPayload.Format.PNG,RestrictedPayload.Format.AAC_ADTS,RestrictedPayload.Format.PDF_PAGES,RestrictedPayload.Format.AVC_MP4)))
                    throw new AssertionError("Wrong durable format inventory");
                status("PASS formats=PNG,AAC_ADTS,PDF_PAGES,AVC_MP4 cannot reopen or revive after actual process force-stop");
            }
        } else throw new SecurityException("Specify synthetic restart phase");
    }
}
