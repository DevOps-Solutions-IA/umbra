package app.umbra.pairing;

/** Public progress only. No invite, code, request, ack, key or transport capability. */
public record PairingSnapshot(String id,Role role,Phase phase,NextAction nextAction,
        long expiresInSeconds,PairingException.Code failure,String peerId,boolean verificationRequired) {
    public enum Role { INVITER, JOINER }
    public enum Phase { INVITE_CREATED, REQUEST_CREATED, ACK_CREATED, COMPLETE, EXPIRED, REVOKED, CANCELLED }
    public enum NextAction { SHARE_INVITATION, DELIVER_REQUEST, WAITING_FOR_PEER, DELIVER_ACK, VERIFY_IDENTITY, NONE }
}
