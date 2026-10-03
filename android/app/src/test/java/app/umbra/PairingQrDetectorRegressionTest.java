package app.umbra;

import app.umbra.crypto.Engine;
import app.umbra.pairing.*;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Standard ZXing regression. Public, permanently expired synthetic signature; no private key or live capability. */
public class PairingQrDetectorRegressionTest {
    private static String expiredRecipe() {
        String body=String.join("\n",
            "UMBRA-PAIR-INVITE-1",
            "AucKxaCAlEVM1THbYZfI73rvqp1sSqvoa-mSNVIwJr8",
            "Bd0vWtW128uNbp6T4mPQUPVJAiFvwZhEoWHZUqZYiely",
            "sBEyMomvciXUxWFphV8Rv5wk0tQtjdg6TKOatbVBR6M",
            "NAGUcH_FZO5e_hhzNb6K5i9oUZqV9q32B3rc69qWO3o",
            "1700000000",
            "1700000060",
            "1")+"\n";
        return "umbra:invite:1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))+
            "."+"mlHw_LD2V71J_eqr5wa2gSQ5cbu97Rw0rFQB_BzmWM6xjLSXp_Te-1Ux3A3KCerRg3szW5N2-bUXETf1f2anhw";
    }
    private static byte[] pixels(String text,int size) throws Exception {
        var matrix=new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,size,size,
            Map.of(EncodeHintType.ERROR_CORRECTION,com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M));
        byte[] result=new byte[size*size];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)result[y*size+x]=matrix.get(x,y)?0:(byte)255;
        return result;
    }
    private static BinaryBitmap bitmap(byte[] plane,int size) {
        return new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(plane,size,size,0,0,size,size,false)));
    }
    @Test public void defaultDetectorMissesValidSignedSymbolButExpiryStillRejectsIt() throws Exception {
        String expired=expiredRecipe();byte[] plane=pixels(expired,512);
        assertThrows(ReaderException.class,()->new QRCodeReader().decode(bitmap(plane,512)));
        assertTrue("Synthetic QR values redacted",expired.equals(new QRCodeReader().decode(bitmap(plane,512),
            Map.of(DecodeHintType.PURE_BARCODE,Boolean.TRUE)).getText()));
        assertEquals(PairingException.Code.EXPIRED,assertThrows(PairingException.class,()->PairingQrCodec.decode(expired)).code());
        assertEquals(PairingException.Code.EXPIRED,assertThrows(PairingException.class,
            ()->PairingQrCodec.decodeLuminance(plane,512,512)).code());
    }
    @Test public void boundedValidSignedCorpusRoundTripsOwnRenderedPixels() throws Exception {
        var db=new MemoryRecords();var engine=new Engine(db);engine.initialize("Synthetic QR corpus");AdmissionFixture.enroll(engine);
        var product=new PairingProduct(db);
        for(int sample=0;sample<32;sample++) {
            String invite=product.createPairing().delivery().payload();byte[] plane=pixels(invite,512);
            try {assertTrue("Synthetic QR values redacted",invite.equals(PairingQrCodec.decodeLuminance(plane,512,512)));}
            finally {Arrays.fill(plane,(byte)0);}
        }
    }
    @Test public void protocolSignatureAndMalformedPlanesRemainRejected() throws Exception {
        byte[] wrong=pixels("umbra:verify:1:synthetic-safety",512);
        assertEquals(PairingException.Code.INVALID_FORMAT,assertThrows(PairingException.class,
            ()->PairingQrCodec.decodeLuminance(wrong,512,512)).code());
        String expired=expiredRecipe();int dot=expired.indexOf('.');byte[] signature=Base64.getUrlDecoder().decode(expired.substring(dot+1));signature[0]^=1;
        byte[] corrupt=pixels(expired.substring(0,dot+1)+Base64.getUrlEncoder().withoutPadding().encodeToString(signature),512);
        assertEquals(PairingException.Code.INVALID_SIGNATURE,assertThrows(PairingException.class,
            ()->PairingQrCodec.decodeLuminance(corrupt,512,512)).code());
        byte[] occluded=pixels(expired,512);
        for(int y=0;y<512;y++)Arrays.fill(occluded,y*512,y*512+256,(byte)255);
        assertThrows(PairingException.class,()->PairingQrCodec.decodeLuminance(occluded,512,512));
        assertThrows(PairingException.class,()->PairingQrCodec.decodeLuminance(new byte[512*512],512,512));
        var random=new Random(0x51525245);byte[] noise=new byte[512*512];random.nextBytes(noise);
        assertThrows(PairingException.class,()->PairingQrCodec.decodeLuminance(noise,512,512));
        assertThrows(PairingException.class,()->PairingQrCodec.decodeLuminance(new byte[1],512,512));
    }
}
