package app.umbra.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Domain-separated challenge transcript, signed by libsignal identity keys (not encryption). */
public final class NearbyTranscript {
    private NearbyTranscript() {}
    public static byte[] encode(boolean signerIsDialer, String signer, byte[] signerNonce,
                                String verifier, byte[] verifierNonce) {
        if (signer == null || verifier == null || !signer.matches("[0-9a-f]{64}") || !verifier.matches("[0-9a-f]{64}") ||
            signer.equals(verifier) || signerNonce == null || verifierNonce == null ||
            signerNonce.length != 32 || verifierNonce.length != 32 || Arrays.equals(signerNonce, verifierNonce))
            throw new IllegalArgumentException("Invalid nearby transcript");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(240);
            DataOutputStream out = new DataOutputStream(bytes);
            out.write(Bytes.utf8("UMBRA-NEARBY-PROOF-v2\u0000"));
            out.writeByte(signerIsDialer ? 1 : 2);
            out.write(Bytes.utf8(signer)); out.write(signerNonce);
            out.write(Bytes.utf8(verifier)); out.write(verifierNonce);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
}
