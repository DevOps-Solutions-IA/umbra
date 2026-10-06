package app.umbra.devices;

import app.umbra.core.Bytes;
import app.umbra.data.Records;
import org.json.JSONObject;

/** Engine-side membership enforcement, including legacy direct APIs and transport dequeue. */
public final class DevicePolicy {
    private DevicePolicy() {}
    private static JSONObject read(Records db, String bucket, String key) throws Exception {
        byte[] raw = db.get(bucket, key); return raw == null ? null : new JSONObject(Bytes.text(raw));
    }
    public static void authorize(Records db, String self, String peer) throws Exception {
        JSONObject own = read(db, "meta", "device-affiliation");
        if (own == null && !db.keys("device-roster").isEmpty()) throw new SecurityException("Device affiliation missing");
        if (own != null) {
            JSONObject ownIndex = read(db, "device-index", self);
            if (ownIndex == null || !ownIndex.getString("root").equals(own.getString("root"))) throw new SecurityException("Own device index missing");
            member(db, own.getString("root"), self, false);
        }
        JSONObject index = read(db, "device-index", peer);
        if (index != null) member(db, index.getString("root"), peer, true);
        else for (String root : db.keys("device-roster")) {
            JSONObject row = read(db, "device-roster", root);
            if (row.getJSONObject("active").has(peer)) throw new SecurityException("Peer device index missing");
        }
    }

    private static void member(Records db, String root, String device, boolean approval) throws Exception {
        JSONObject authority = read(db, "contact", root);
        if (authority != null && (authority.optBoolean("blocked") || authority.optBoolean("identityChanged")))
            throw new SecurityException("Device authority trust is suspended");
        JSONObject row = read(db, "device-roster", root);
        if (row == null || row.getLong("expires") <= Bytes.now() || row.optBoolean("retired") ||
            !row.getJSONObject("active").optBoolean(device) ||
            (approval && !row.getJSONObject("approved").optBoolean(device)))
            throw new SecurityException("Device membership unavailable, revoked or awaiting approval");
    }
}
