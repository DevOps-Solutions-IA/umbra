package app.umbra;

import app.umbra.crypto.Engine;
import app.umbra.core.Bytes;
import java.nio.file.*;
import org.json.JSONObject;

/** Test APK/JVM harness only. Device seed never leaves Records. Host sees a public signed request. */
public final class AdmissionLab {
    private AdmissionLab() {}
    public static void provision(Engine engine,Path directory,String stem,String realm) throws Exception {
        if(!stem.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Synthetic exchange name");
        engine.admission().installRealmConfig(realm,true);
        var request=engine.admission().createAdmissionRequest();
        Path output=directory.resolve(stem+"-request.json"), temporary=directory.resolve(stem+"-request.tmp");
        Files.write(temporary,Bytes.utf8(new JSONObject().put("request",request.wire()).toString()));
        Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        Path result=directory.resolve(stem+"-credential.json");
        long deadline=System.nanoTime()+30_000_000_000L;
        while(!Files.exists(result)) {
            if(System.nanoTime()>=deadline) throw new IllegalStateException("Synthetic admission approval deadline");
            Thread.sleep(50);
        }
        JSONObject response=new JSONObject(Bytes.text(Files.readAllBytes(result)));
        engine.admission().installAdmissionCredential(response.getString("credential"));
        Files.delete(result); Files.deleteIfExists(output);
    }
}
