package app.umbra.devices;

import app.umbra.core.Bytes;
import java.util.*;
import org.signal.libsignal.protocol.IdentityKeyPair;

/** Immutable signed membership. Tombstones are never removed or reactivated. */
public final class DeviceRoster {
    public static final long MAX_TTL = 7 * 86400L;
    public static final int MAX_ACTIVE = 8, MAX_ENTRIES = 32;
    public record Member(String key, boolean active) {
        public String id() throws Exception { return DeviceTranscript.id(key); }
    }
    public final String transcript, rootKey, root, fingerprint;
    public final long version, expires;
    public final Map<String, Member> members;
    private DeviceRoster(String value, String[] fields, boolean fresh) throws Exception {
        transcript = value; rootKey = fields[1]; root = DeviceTranscript.id(rootKey);
        version = DeviceTranscript.number(fields[2]); expires = DeviceTranscript.number(fields[4]);
        if (fresh) DeviceTranscript.lifetime(fields[3], fields[4], MAX_TTL);
        Map<String, Member> parsed = new LinkedHashMap<>(); String previous = ""; int active = 0;
        for (String entry : fields[5].split(",", -1)) {
            String[] item = entry.split(":", -1);
            if (item.length != 2 || !(item[1].equals("A") || item[1].equals("R"))) throw new SecurityException("Invalid device member");
            Member member = new Member(DeviceTranscript.key(item[0]), item[1].equals("A")); String id = member.id();
            if (id.compareTo(previous) <= 0) throw new SecurityException("Unsorted or duplicate device");
            previous = id; parsed.put(id, member); if (member.active()) active++;
        }
        if (!parsed.containsKey(root) || parsed.size() > MAX_ENTRIES || active > MAX_ACTIVE ||
            (!parsed.get(root).active() && active != 0)) throw new SecurityException("Invalid device authority");
        members = Collections.unmodifiableMap(parsed);
        fingerprint = Bytes.sha256(Bytes.utf8(value));
    }
    public static DeviceRoster parse(String value) throws Exception { return new DeviceRoster(value, DeviceTranscript.parse(value, "roster", 5), true); }
    static DeviceRoster stored(String value) throws Exception { return new DeviceRoster(value, DeviceTranscript.parse(value, "roster", 5), false); }
    static DeviceRoster sign(IdentityKeyPair pair, long version, Map<String, Member> members) throws Exception {
        List<String> ids = new ArrayList<>(members.keySet()); Collections.sort(ids);
        List<String> entries = new ArrayList<>();
        for (String id : ids) {
            Member member = members.get(id);
            if (!id.equals(member.id())) throw new SecurityException("Device ID mismatch");
            entries.add(member.key() + ":" + (member.active() ? "A" : "R"));
        }
        long now = Bytes.now();
        return parse(DeviceTranscript.sign(pair, "roster", DeviceTranscript.encode(pair.getPublicKey().serialize()),
            "" + version, "" + now, "" + (now + MAX_TTL), String.join(",", entries)));
    }
    public boolean active(String id) { Member member = members.get(id); return member != null && member.active(); }
    public boolean retired() { return !active(root); }
}
