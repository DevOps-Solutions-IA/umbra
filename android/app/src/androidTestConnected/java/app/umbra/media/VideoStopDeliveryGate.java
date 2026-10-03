package app.umbra.media;

/** Test APK only: isolates both measured local stop requests from peer signaling. */
final class VideoStopDeliveryGate {
    private final String call, nonce;
    private final int generation;
    private boolean issued, released, peerStopApplied;
    private String stopEnvelopeId, peerStopId;
    private final java.util.Set<String> receivedBeforeRelease=new java.util.HashSet<>();

    VideoStopDeliveryGate(String call,int generation,String nonce) {
        if(call==null || call.isEmpty() || generation<1 || nonce==null || nonce.isEmpty())
            throw new IllegalArgumentException("Invalid synthetic stop gate");
        this.call=call;this.generation=generation;this.nonce=nonce;
    }
    boolean defer(String type,String queuedCall,int queuedGeneration) {
        return issued && !released && matchesStop(type,queuedCall,queuedGeneration);
    }
    void issued(String stopEnvelopeId) {
        if(stopEnvelopeId==null || stopEnvelopeId.isEmpty())throw new IllegalArgumentException("Missing synthetic stop envelope");
        if(issued)throw new IllegalStateException("Synthetic local stop already issued");
        this.stopEnvelopeId=stopEnvelopeId;issued=true;
    }
    void release(boolean confirmed,int generation,String nonce,String peerStopId) {
        if(!issued || !confirmed || this.generation!=generation || !this.nonce.equals(nonce) ||
                peerStopId==null || peerStopId.isEmpty() || peerStopId.equals(stopEnvelopeId) ||
                (released && !peerStopId.equals(this.peerStopId)))
            throw new SecurityException("Synthetic stop release lacks matching local request");
        this.peerStopId=peerStopId;released=true;
        peerStopApplied=peerStopApplied || receivedBeforeRelease.contains(peerStopId);
        receivedBeforeRelease.clear();
    }
    void received(String envelopeId) {
        if(released) {
            if(peerStopId.equals(envelopeId))peerStopApplied=true;
        } else if(issued) {
            // The host releases endpoints sequentially. A peer's released stop
            // may be applied here before this endpoint reads its own release.
            if(receivedBeforeRelease.size()>=128 && !receivedBeforeRelease.contains(envelopeId))
                throw new AssertionError("Synthetic stop barrier receive capacity exceeded");
            receivedBeforeRelease.add(envelopeId);
        }
    }
    boolean peerStopApplied() { return peerStopApplied; }
    String stopEnvelopeId() { return stopEnvelopeId; }
    boolean matchesStop(String type,String queuedCall,int queuedGeneration) {
        return type.equals("VIDEO_STOP") && call.equals(queuedCall) && generation==queuedGeneration;
    }
    boolean released() { return released; }
    int generation() { return generation; }
    String nonce() { return nonce; }

    static boolean withinBounds(long request,long invalidated,long lastCapture,long closed) {
        long invalidation=invalidated-request, closure=closed-request;
        long lastCallback=Math.max(0,lastCapture-request);
        return request>0 && invalidation>=0 && invalidation<=500_000_000L &&
            closure>=0 && closure<=2_000_000_000L && lastCallback<=1_500_000_000L;
    }
}
