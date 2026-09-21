package app.umbra;

import app.umbra.calls.*;
import app.umbra.core.Bytes;
import app.umbra.protocol.Wire;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static app.umbra.calls.CallPayload.NetworkPolicy.*;

public class CallPolicyTest {
    @Test public void relayOnlyCannotDowngradeAndMissingTurnFails() {
        assertEquals(RELAY_ONLY,CallPayload.policy("RELAY_ONLY"));
        assertThrows(SecurityException.class,()->CallPayload.policy("DIRECT_ALLOWED"));
        assertThrows(SecurityException.class,()->CallPayload.effective(RELAY_ONLY,DIRECT_ALLOWED));
        assertThrows(SecurityException.class,()->new RelayOnlyContract(List.of(),"0".repeat(64)));
        assertThrows(SecurityException.class,()->new RelayOnlyContract(List.of("https://example.invalid:443"),"0".repeat(64)));
    }
    @Test public void failuresLatchNoFallbackAndConfigurationCannotChange() {
        for(var failure:RelayOnlyContract.Failure.values()) {
            var c=new RelayOnlyContract(List.of("turns:relay.example.invalid:5349?transport=tcp"),"0".repeat(64));
            c.check("0".repeat(64),RELAY_ONLY,1); c.failure(failure);
            assertThrows(SecurityException.class,()->c.check("0".repeat(64),RELAY_ONLY,2)); assertEquals(RELAY_ONLY,c.policy());
        }
        var c=new RelayOnlyContract(List.of("turn:relay.example.invalid:3478?transport=udp"),"0".repeat(64));
        assertThrows(SecurityException.class,()->c.check("1".repeat(64),RELAY_ONLY,2));
        assertThrows(SecurityException.class,()->c.check("0".repeat(64),RELAY_ONLY,2));
        var direct=new RelayOnlyContract(List.of("turn:relay.example.invalid:3478"),"0".repeat(64));
        assertThrows(SecurityException.class,()->direct.check("0".repeat(64),DIRECT_ALLOWED,2));
        assertThrows(SecurityException.class,direct::requireNativeAdapter);
    }
    @Test public void offlineCannotInviteOrReceiveDirectly() throws Exception {
        var d=new DeviceLinkingTest.Device("Synthetic offline policy");
        assertThrows(SecurityException.class,()->d.e.calls().receive(null,new JSONObject()));
        assertThrows(SecurityException.class,()->d.e.enqueueCall(null,new JSONObject(),List.of()));
        if(!CallPlatform.ENABLED) {
            assertThrows(SecurityException.class,()->d.e.calls().reviewInvite("0".repeat(64),RELAY_ONLY));
            assertThrows(SecurityException.class,()->d.e.calls().sessions());
        } else assertTrue(app.umbra.BuildConfig.ALLOW_RELAY);
    }
    @Test public void strictJsonDuplicateFieldsAndContextCanonicalization() throws Exception {
        assertThrows(IllegalArgumentException.class,()->Wire.parse(Bytes.utf8("{\"v\":1,\"v\":1}"),100));
        assertEquals(CallPayload.canonical(new JSONObject("{\"a\":1,\"b\":2}")),CallPayload.canonical(new JSONObject("{\"b\":2,\"a\":1}")));
        assertThrows(SecurityException.class,()->CallPayload.validate(new JSONObject(),Bytes.now()));
    }
}
