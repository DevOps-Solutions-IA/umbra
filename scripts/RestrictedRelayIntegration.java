package app.umbra.content;

import app.umbra.crypto.Engine;
import app.umbra.transport.RelayClient;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** JVM-only synthetic PNG producer. Android sanitizer/renderer are tested by the separate APK lab. */
public final class RestrictedRelayIntegration {
    private static void check(boolean result,String label) {
        if(!result)throw new AssertionError(label);
        System.out.println("PASS restricted HTTPS: "+label);
    }
    public static void run(Engine sender,Engine recipient,RelayClient relay,JSONObject recipientProfile,
                           JSONObject route,JSONObject senderProfile,JSONObject returnRoute) throws Exception {
        var bitmap=new java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB);
        for(int x=0;x<8;x++)for(int y=0;y<8;y++)bitmap.setRGB(x,y,0x336699);
        var output=new ByteArrayOutputStream();check(javax.imageio.ImageIO.write(bitmap,"png",output),"synthetic fixture encoded");
        int history=recipient.messages(sender.id()).size();
        String id=sender.restricted().send(sender.restricted().reviewSend(recipient.id(),RestrictedPayload.Mode.ONCE,600,30),
                new RestrictedContentService.Prepared(RestrictedPayload.Format.PNG,output.toByteArray()),true);
        JSONObject envelope=null;
        for(var queued:sender.outbox())if(id.equals(queued.optString("restrictedId")))envelope=queued.getJSONObject("envelope");
        if(envelope==null)throw new AssertionError("Missing restricted delivery");
        String immutable=envelope.toString();relay.send(route,envelope);relay.send(route,envelope);
        var page=relay.poll(recipientProfile,0).getJSONArray("messages");check(page.length()==1,"relay deduplicates immutable restricted delivery");
        var received=page.getJSONObject(0);recipient.receive(received);
        check(recipient.restricted().status(id).mode()==RestrictedPayload.Mode.ONCE,"Signal authenticates restricted descriptor");
        check(recipient.messages(sender.id()).size()==history,"restricted payload does not enter ordinary history");
        try {recipient.exportData(id);throw new AssertionError("Restricted export accepted");}
        catch(SecurityException expected) {check(true,"ordinary export rejects restricted object");}
        var session=recipient.restricted().open(recipient.restricted().reviewOpen(id),true);session.check();session.close();
        session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
        recipient.receive(received);
        try {recipient.restricted().open(recipient.restricted().reviewOpen(id),true);throw new AssertionError("Replay restored consumed object");}
        catch(ContentException expected) {check(expected.code()==ContentException.Code.CONSUMED,"duplicate Signal delivery cannot restore one-use grant");}
        relay.acknowledge(recipientProfile,received.getString("id"));
        for(var queued:recipient.outbox())relay.send(returnRoute,queued.getJSONObject("envelope"));
        var receipts=relay.poll(senderProfile,0).getJSONArray("messages");
        for(int i=0;i<receipts.length();i++){var receipt=receipts.getJSONObject(i);sender.receive(receipt);relay.acknowledge(senderProfile,receipt.getString("id"));}
        check(sender.outbox().isEmpty(),"authenticated receipt removes restricted outbox");
        check(immutable.equals(envelope.toString()),"retry ciphertext remains immutable");
    }
}
