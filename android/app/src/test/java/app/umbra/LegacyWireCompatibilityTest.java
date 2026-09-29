package app.umbra;

import app.umbra.content.*;
import app.umbra.core.*;
import app.umbra.crypto.SignalStore;
import app.umbra.protocol.Wire;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Test;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.message.*;
import static org.junit.Assert.*;

/** Executes the exact old wire parser, not an old APK or Android storage layer. */
public final class LegacyWireCompatibilityTest {
    private static final String SOURCE_SHA="ea9d801633b605f9b2cef27cb544683f9d26b80370f4f1463e75b75a76e1f845";
    @Test(timeout=30000) public void exactEmergencyBaseParserRejectsCurrentSignalRestrictedButAcceptsText()throws Exception {
        byte[] source;
        try(var input=getClass().getResourceAsStream("/legacy/ba75d329/Wire.java.txt")) {
            assertNotNull(input);source=input.readAllBytes();
        }
        assertEquals(SOURCE_SHA,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        Path directory=Files.createTempDirectory("umbra-legacy-wire-");Process child=null;
        try {
            Path parser=directory.resolve("Wire.java"),harness=directory.resolve("LegacyWireProbe.java");
            Files.write(parser,source);
            Files.write(harness,("""
                import app.umbra.protocol.Wire;
                import org.json.*;
                public final class LegacyWireProbe {
                    public static void main(String[] args)throws Exception {
                        byte[] input=System.in.readNBytes(2000001);
                        if(input.length>2000000)throw new AssertionError("Fixture input bound");
                        var fixtures=new JSONArray(new String(input,java.nio.charset.StandardCharsets.UTF_8));
                        if(fixtures.length()!=2)throw new AssertionError("Exact coverage required");
                        int rejected=0,accepted=0;
                        for(int n=0;n<fixtures.length();n++) {
                            var fixture=fixtures.getJSONObject(n);var content=fixture.getJSONObject("content");
                            if(content.getString("kind").equals("text")) {
                                Wire.content(content,fixture.getJSONObject("envelope"),fixture.getLong("now"),86400,262144);accepted++;
                            } else {
                                if(!content.getString("kind").equals("restricted"))throw new AssertionError("Wrong restricted fixture");
                                try {
                                    Wire.content(content,fixture.getJSONObject("envelope"),fixture.getLong("now"),86400,262144);
                                    throw new AssertionError("Legacy parser accepted restricted content");
                                } catch(SecurityException expected) {
                                    if(!"Unsupported content".equals(expected.getMessage()))throw expected;
                                    rejected++;
                                }
                            }
                        }
                        if(accepted!=1 || rejected!=1)throw new AssertionError("Incomplete compatibility check");
                        System.out.println("PASS legacy-exact-parser text=1 restricted-rejected=1");
                    }
                }
                """).getBytes(StandardCharsets.UTF_8));
            var classpath=new LinkedHashSet<String>();
            for(Class<?> dependency:new Class<?>[]{Bytes.class,JSONObject.class,SessionCipher.class})
                classpath.add(Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            String dependencies=String.join(java.io.File.pathSeparator,classpath);
            Path compilerLog=directory.resolve("compiler.txt");
            child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","javac").toString(),"-encoding","UTF-8",
                "-classpath",dependencies,"-d",directory.toString(),parser.toString(),harness.toString())
                .redirectErrorStream(true).redirectOutput(compilerLog.toFile()).start();
            assertTrue("Pinned JDK compiler exceeded bounded run",child.waitFor(10,TimeUnit.SECONDS));
            assertEquals("Exact legacy parser must compile: "+new String(Files.readAllBytes(compilerLog),StandardCharsets.UTF_8),0,child.exitValue());
            var pair=new RestrictedContentTest.Pair();
            var constructor=RestrictedContentService.Prepared.class.getDeclaredConstructor(RestrictedPayload.Format.class,byte[].class,Runnable.class);
            constructor.setAccessible(true);
            // Protocol fixture only: native image preparation/decoding is tested separately.
            try(var prepared=constructor.newInstance(RestrictedPayload.Format.PNG,new byte[]{1,2,3,4},pair.a.db.authorization())) {
                pair.a.e.restricted().send(pair.a.e.restricted().reviewSend(pair.b.e.id(),RestrictedPayload.Mode.ONCE,600,30),prepared,true);
            }
            pair.a.e.sendText(pair.b.e.id(),"synthetic legacy compatibility positive",600);
            var fixtures=new JSONArray();
            for(var queued:pair.a.e.outbox()) {
                var envelope=queued.getJSONObject("envelope");
                JSONObject content=pair.b.db.transaction(()->{
                    var cipher=new SessionCipher(new SignalStore(pair.b.db),
                        new SignalProtocolAddress(pair.b.e.id(),1),new SignalProtocolAddress(pair.a.e.id(),1));
                    byte[] encrypted=Bytes.unb64(envelope.getString("ct"));
                    byte[] padded=envelope.getInt("type")==CiphertextMessage.PREKEY_TYPE?
                        cipher.decrypt(new PreKeySignalMessage(encrypted)):cipher.decrypt(new SignalMessage(encrypted));
                    byte[] plain=null;
                    try{plain=Padding.unpad(padded);return Wire.parse(plain,Padding.MAX_CLEAR);}
                    finally{Arrays.fill(padded,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
                });
                Wire.content(content,envelope,Bytes.now(),86400,262144);
                fixtures.put(new JSONObject().put("content",content).put("envelope",envelope).put("now",Bytes.now()));
            }
            assertEquals(2,fixtures.length());
            Path output=directory.resolve("receipt.txt");
            child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                directory+java.io.File.pathSeparator+dependencies,"LegacyWireProbe")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try(var input=child.getOutputStream()){input.write(fixtures.toString().getBytes(StandardCharsets.UTF_8));}
            assertTrue("Legacy parser process exceeded bounded run",child.waitFor(10,TimeUnit.SECONDS));
            assertEquals("Legacy parser process failed",0,child.exitValue());
            assertEquals("PASS legacy-exact-parser text=1 restricted-rejected=1",new String(Files.readAllBytes(output),StandardCharsets.UTF_8).trim());
        } finally {
            if(child!=null && child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(3,TimeUnit.SECONDS));}
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
