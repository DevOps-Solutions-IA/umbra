package app.umbra.content;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import app.umbra.privacy.ImagePreparation;

/** Preparation and decoding only. Presentation owner must secure its surface before calling render. */
public final class RestrictedImages {
    private RestrictedImages() {}
    public static RestrictedContentService.Prepared prepare(byte[] encoded,Runnable authorization) throws java.io.IOException {
        return new RestrictedContentService.Prepared(RestrictedPayload.Format.PNG,ImagePreparation.sanitize(encoded,authorization),authorization);
    }
    /** Framework bitmap lifetime is confined to an explicitly owned decoder, never a public file. */
    public static final class Decoder implements AutoCloseable {
        private final RestrictedContentService.Session session;
        private Bitmap bitmap;
        public Decoder(RestrictedContentService.Session session) throws Exception {
            this.session=session;
            if(session.format()!=RestrictedPayload.Format.PNG)throw RestrictedPayload.invalid();
            bitmap=session.decode(bytes->{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(!"image/png".equals(bounds.outMimeType) || bounds.outWidth<1 || bounds.outHeight<1 ||
                        bounds.outWidth>ImagePreparation.MAX_DIMENSION || bounds.outHeight>ImagePreparation.MAX_DIMENSION ||
                        (long)bounds.outWidth*bounds.outHeight>ImagePreparation.MAX_PIXELS)throw RestrictedPayload.invalid();
                Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                if(decoded==null)throw RestrictedPayload.invalid();return decoded;
            },Bitmap::recycle);
        }
        /** Does not return the bitmap; caller owns the already protected Canvas/surface. */
        public synchronized void render(android.graphics.Canvas canvas,android.graphics.Rect destination) throws Exception {
            session.use(()->canvas.drawBitmap(bitmap,null,destination,null));
        }
        @Override public synchronized void close() {session.close();}
    }
}
