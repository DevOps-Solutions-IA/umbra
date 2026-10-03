package app.umbra.media;

/** Test APK only: isolates both measured local stop requests from peer signaling. */
final class VideoStopDeliveryGate {
    private final String call, nonce;
    private final int generation;
    private boolean issued, released, peerStopApplied;
    private java.util.Set<String> stopEnvelopeIds=java.util.Set.of(), peerStopIds=java.util.Set.of();
    private final java.util.Set<String> receivedBeforeRelease=new java.util.HashSet<>();

    VideoStopDeliveryGate(String call,int generation,String nonce) {
        if(call==null || call.isEmpty() || generation<1 || nonce==null || nonce.isEmpty())
            throw new IllegalArgumentException("Invalid synthetic stop gate");
        this.call=call;this.generation=generation;this.nonce=nonce;
    }
    void requireAnnounced(String type,String queuedCall,int queuedGeneration,String envelopeId) {
        if(issued && matchesStop(type,queuedCall,queuedGeneration) && !stopEnvelopeIds.contains(envelopeId))
            throw new AssertionError("Synthetic stop inventory changed after issued receipt");
    }
    boolean defer(String type,String queuedCall,int queuedGeneration) {
        return issued && !released && matchesStop(type,queuedCall,queuedGeneration);
    }
    private static java.util.Set<String> boundedIds(java.util.Collection<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>128)throw new SecurityException("Invalid synthetic stop envelope set");
        var copy=new java.util.LinkedHashSet<String>();
        for(String id:ids)if(id==null || id.isEmpty() || !copy.add(id))
            throw new SecurityException("Duplicate or missing synthetic stop envelope");
        return java.util.Collections.unmodifiableSet(copy);
    }
    void issued(java.util.Collection<String> stopEnvelopeIds) {
        if(issued)throw new IllegalStateException("Synthetic local stop already issued");
        this.stopEnvelopeIds=boundedIds(stopEnvelopeIds);issued=true;
    }
    void release(boolean confirmed,int generation,String nonce,java.util.Collection<String> peerStopIds) {
        var peer=boundedIds(peerStopIds);
        if(!issued || !confirmed || this.generation!=generation || !this.nonce.equals(nonce) ||
                !java.util.Collections.disjoint(peer,stopEnvelopeIds) || (released && !peer.equals(this.peerStopIds)))
            throw new SecurityException("Synthetic stop release lacks matching local request");
        this.peerStopIds=peer;released=true;
        receivedBeforeRelease.retainAll(peer);
        peerStopApplied=receivedBeforeRelease.containsAll(peer);
    }
    void received(String envelopeId) {
        if(released) {
            if(peerStopIds.contains(envelopeId))receivedBeforeRelease.add(envelopeId);
            peerStopApplied=receivedBeforeRelease.containsAll(peerStopIds);
        } else if(issued) {
            // The host releases endpoints sequentially. A peer's released stop
            // may be applied here before this endpoint reads its own release.
            if(receivedBeforeRelease.size()>=128 && !receivedBeforeRelease.contains(envelopeId))
                throw new AssertionError("Synthetic stop barrier receive capacity exceeded");
            receivedBeforeRelease.add(envelopeId);
        }
    }
    boolean peerStopApplied() { return peerStopApplied; }
    java.util.Set<String> stopEnvelopeIds() { return stopEnvelopeIds; }
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
