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
        var authorization=engine.admission().requestAuthorization();
        Path output=directory.resolve(stem+"-request.json"), temporary=directory.resolve(stem+"-request.tmp");
        Files.write(temporary,Bytes.utf8(new JSONObject().put("request",request.wire()).toString()));
        Files.move(temporary,output,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        Path result=directory.resolve(stem+"-credential.json");
        long deadline=System.nanoTime()+30_000_000_000L;
        while(!Files.exists(result)) {
            authorization.run();
            if(System.nanoTime()>=deadline) throw new IllegalStateException("Synthetic admission approval deadline");
            Thread.sleep(50);
        }
        JSONObject response=new JSONObject(Bytes.text(Files.readAllBytes(result)));
        String wire=response.getString("credential");
        var credential=app.umbra.admission.AdmissionCredential.decode(wire,engine.admission().getRealmInfo());
        // Model a caller waiting for notBefore, not a bypass/backdated credential. AVD and
        // host wall-clock seconds can straddle issuance. Production validation remains strict.
        long start=System.nanoTime();
        while(Bytes.now()<credential.notBefore()) {
            authorization.run();
            if(credential.notBefore()-Bytes.now()>2 || System.nanoTime()-start>=2_500_000_000L)
                throw new SecurityException("Synthetic approval clock mismatch");
            Thread.sleep(50);
        }
        authorization.run();
        engine.admission().installAdmissionCredential(wire);
        Files.delete(result); Files.deleteIfExists(output);
    }
}
