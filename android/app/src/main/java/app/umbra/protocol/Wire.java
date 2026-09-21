package app.umbra.protocol;

import app.umbra.core.*;
import java.util.*;
import org.json.JSONObject;

/** Rejects JSON type coercion, unsupported fields and ambiguous wire encodings. */
public final class Wire {
    private Wire() {}
    public static JSONObject parse(byte[] bytes, int maximum) throws Exception {
        return new JSONObject(StrictJson.object(bytes, maximum));
    }
    public static void fields(JSONObject object, String... expected) {
        Set<String> keys = new HashSet<>(); object.keys().forEachRemaining(keys::add);
        if (!keys.equals(new HashSet<>(Arrays.asList(expected)))) throw new SecurityException("Unexpected protocol fields");
    }
    public static String string(JSONObject object, String key, int maximum) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String s) || s.length() > maximum) throw new SecurityException("Invalid string field");
        return s;
    }
    public static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new SecurityException("Invalid integer field");
        return ((Number) value).longValue();
    }
    public static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw new SecurityException("Noncanonical identifier");
        return value;
    }
    public static String identity(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new SecurityException("Invalid identity");
        return value;
    }
    public static void envelope(JSONObject e, String recipient, long now, long maxTtl) throws Exception {
        fields(e, "v", "id", "from", "to", "type", "ct", "expires");
        if (integer(e, "v") != 1 || e.toString().length() > Framing.MAX_FRAME) throw new SecurityException("Invalid envelope");
        uuid(string(e, "id", 36)); String from = identity(string(e, "from", 64)), to = identity(string(e, "to", 64));
        if (!to.equals(recipient) || from.equals(to)) throw new SecurityException("Wrong recipient");
        long expires = integer(e, "expires"), type = integer(e, "type");
        if (expires <= now || expires - now > maxTtl + 300) throw new SecurityException("Expired envelope or clock skew");
        if (type != 2 && type != 3) throw new SecurityException("Unsupported ciphertext type");
        byte[] cipher = Bytes.unb64(string(e, "ct", 960_000));
        if (cipher.length < 16 || cipher.length > 720_000) throw new SecurityException("Invalid ciphertext length");
    }
    public static void content(JSONObject c, JSONObject envelope, long now, long maxTtl, int maxAttachment) throws Exception {
        String kind = string(c, "kind", 16);
        List<String> names = new ArrayList<>(List.of("v", "id", "from", "to", "created", "createdMs", "expires", "kind"));
        switch (kind) {
            case "text" -> names.add("text");
            case "location" -> names.add("location");
            case "file" -> { names.add("name"); names.add("data"); }
            case "receipt" -> names.add("ackFor");
            case "device-roster" -> names.add("roster");
            case "device-grant" -> { names.add("box"); names.add("token"); names.add("proof"); }
            default -> throw new SecurityException("Unsupported content");
        }
        long version = integer(c, "v");
        if(kind.equals("location") && version!=2) throw new SecurityException("Location requires authenticated logical context");
        if (version == 2) {
            if (kind.equals("receipt") || kind.startsWith("device-")) throw new SecurityException("Receipt version unsupported");
            names.addAll(List.of("logicalId", "logicalFrom", "logicalTo"));
            uuid(string(c, "logicalId", 36)); identity(string(c, "logicalFrom", 64)); identity(string(c, "logicalTo", 64));
        }
        fields(c, names.toArray(new String[0]));
        for (String key : List.of("id", "from", "to"))
            if (!string(c, key, 64).equals(envelope.getString(key))) throw new SecurityException("Envelope substitution");
        long created = integer(c, "created"), ms = integer(c, "createdMs"), expiry = integer(c, "expires");
        if ((version != 1 && version != 2) || expiry != integer(envelope, "expires") || created < now - maxTtl || created > now + 300 ||
            expiry <= created || expiry - created > maxTtl || ms < 0 || Math.abs(ms / 1000 - created) > 1)
            throw new SecurityException("Authenticated timestamp mismatch");
        if (kind.equals("location")) {
            if(!(c.get("location") instanceof JSONObject p)) throw new SecurityException("Invalid location object");
            app.umbra.location.LocationPayload.validate(p,now);
            if(expiry>p.getLong("ends") || expiry-created>120) throw new SecurityException("Location expiry mismatch");
        } else if (kind.equals("text")) {
            String text = string(c, "text", 16_000);
            if (text.trim().isEmpty() || Bytes.utf8(text).length > 16_000) throw new SecurityException("Invalid text");
        } else if (kind.equals("file")) {
            String name = string(c, "name", 120);
            if (!FileNames.safe(name)) throw new SecurityException("Unsafe filename");
            byte[] data = Bytes.unb64(string(c, "data", 350_000));
            try { if (data.length < 1 || data.length > maxAttachment) throw new SecurityException("Invalid attachment length"); }
            finally { Arrays.fill(data, (byte) 0); }
        } else if (kind.equals("device-roster")) {
            app.umbra.devices.DeviceRoster.parse(string(c, "roster", 32000));
        } else if (kind.equals("device-grant")) {
            uuid(string(c, "box", 36)); app.umbra.pairing.PairingService.token(string(c, "token", 43));
            if (Bytes.unb64(string(c, "proof", 88)).length != 64) throw new SecurityException("Invalid delegation proof");
        } else uuid(string(c, "ackFor", 36));
    }
}
