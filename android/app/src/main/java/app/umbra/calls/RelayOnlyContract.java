package app.umbra.calls;

import app.umbra.core.Bytes;
import java.net.URI;
import java.util.*;

/** Immutable local contract for a FUTURE native adapter; never opens a socket. */
public final class RelayOnlyContract {
    public static final String FAILURE="No se pudo establecer la conexión privada mediante el retransmisor.";
    public enum Failure { MISSING_TURN, AUTHENTICATION, ALLOCATION, CONNECTIVITY, CONFIGURATION_CHANGED, ADAPTER_UNAVAILABLE }
    private final List<String> authorized; private final String revision;
    private boolean failed;
    public RelayOnlyContract(List<String> locallyAuthorizedTurnUrls,String configurationRevision) {
        if(locallyAuthorizedTurnUrls==null || locallyAuthorizedTurnUrls.isEmpty() || locallyAuthorizedTurnUrls.size()>4) throw new SecurityException(FAILURE);
        if(configurationRevision==null || !configurationRevision.matches("[a-f0-9]{64}")) throw new SecurityException(FAILURE);
        Set<String> unique=new HashSet<>();
        for(String value:locallyAuthorizedTurnUrls) {
            try {
                // RFC7065 opaque TURN URI, not an arbitrary URL supplied by the interlocutor.
                URI uri=new URI(value);
                if(!Set.of("turn","turns").contains(uri.getScheme()) || !uri.isOpaque() || uri.getFragment()!=null || value.length()>256) throw new SecurityException(FAILURE);
                String part=uri.getRawSchemeSpecificPart(); int query=part.indexOf('?'); String authority=query<0?part:part.substring(0,query);
                if(query>=0 && !Set.of("transport=udp","transport=tcp").contains(part.substring(query+1))) throw new SecurityException(FAILURE);
                URI endpoint=new URI("https://"+authority);
                if(endpoint.getHost()==null || endpoint.getUserInfo()!=null || !endpoint.getPath().isEmpty() || endpoint.getPort()<1 || endpoint.getPort()>65535 || !unique.add(value)) throw new SecurityException(FAILURE);
            } catch(java.net.URISyntaxException e) { throw new SecurityException(FAILURE); }
        }
        authorized=List.copyOf(locallyAuthorizedTurnUrls); revision=configurationRevision;
    }
    public List<String> authorizedTurnUrls() { return authorized; }
    public CallPayload.NetworkPolicy policy() { return CallPayload.NetworkPolicy.RELAY_ONLY; }
    public synchronized void check(String currentRevision,CallPayload.NetworkPolicy requested,int generation) {
        if(failed || !revision.equals(currentRevision) || requested!=CallPayload.NetworkPolicy.RELAY_ONLY || generation<1 || generation>4) {
            failed=true; throw new SecurityException(FAILURE);
        }
    }
    public synchronized void failure(Failure reason) { Objects.requireNonNull(reason); failed=true; }
    public synchronized boolean failed() { return failed; }
    /** No native adapter is shipped in this signaling delivery. Fail closed, including with valid TURN configuration. */
    public void requireNativeAdapter() { failure(Failure.ADAPTER_UNAVAILABLE); throw new SecurityException(FAILURE); }
    @Override public String toString() { return "RelayOnlyContract[local configuration redacted]"; }
}
