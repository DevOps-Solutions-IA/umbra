package app.umbra.pairing;

import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.*;
import java.util.Map;

/** Pairing invitation QR only. Safety QR is a distinct protocol and never accepted. */
public final class PairingQrCodec {
    public static final int MAX_CHARACTERS=1024, MAX_PIXELS=4_194_304;
    private PairingQrCodec() {}
    public static String decode(String value) throws PairingException {
        if(value==null) throw new PairingException(PairingException.Code.INVALID_FORMAT);
        if(value.length()>MAX_CHARACTERS) throw new PairingException(PairingException.Code.PAYLOAD_TOO_LARGE);
        try { return PairingService.validateInvitation(value); }
        catch(PairingException e) { throw e; }
        catch(Exception e) { throw new PairingException(PairingException.Code.INVALID_FORMAT); }
    }
    public static String encode(String signedInvitation) { return decode(signedInvitation); }
    public static BitMatrix render(String signedInvitation,int size) {
        if(size<256 || size>2048) throw new PairingException(PairingException.Code.INVALID_FORMAT);
        try { return new QRCodeWriter().encode(encode(signedInvitation),BarcodeFormat.QR_CODE,size,size,
                Map.of(EncodeHintType.ERROR_CORRECTION,com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)); }
        catch(WriterException e) { throw new PairingException(PairingException.Code.UNAVAILABLE); }
    }
    /** A bounded luminance plane supplied after caller's camera consent, not an acquisition API. */
    public static String decodeLuminance(byte[] plane,int width,int height) {
        if(plane==null || width<1 || height<1 || (long)width*height>MAX_PIXELS || plane.length!=(long)width*height)
            throw new PairingException(PairingException.Code.PAYLOAD_TOO_LARGE);
        try {
            var source=new PlanarYUVLuminanceSource(plane,width,height,0,0,width,height,false);
            return decode(new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)),
                    Map.of(DecodeHintType.POSSIBLE_FORMATS,java.util.List.of(BarcodeFormat.QR_CODE))).getText());
        } catch(ReaderException e) { throw new PairingException(PairingException.Code.INVALID_FORMAT); }
    }
}
