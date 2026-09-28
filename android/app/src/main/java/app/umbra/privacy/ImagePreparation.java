package app.umbra.privacy;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Decode/re-encode a bounded copy in memory. Never modifies the caller's original or creates files. */
public final class ImagePreparation {
    public static final int MAX_INPUT=4*1024*1024,MAX_OUTPUT=262144,MAX_DIMENSION=2048,MAX_PIXELS=4*1024*1024;
    private ImagePreparation() {}
    public static byte[] sanitize(byte[] encoded,Runnable authorization) throws IOException {
        authorization.run();
        if(encoded==null || encoded.length==0 || encoded.length>MAX_INPUT)throw new PrivacyException(PrivacyException.Code.LIMIT_EXCEEDED);
        Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded).asReadOnlyBuffer()),(decoder,info,source)->{
            authorization.run();
            if(!java.util.Set.of("image/jpeg","image/png").contains(info.getMimeType()) || info.isAnimated())
                throw new PrivacyException(PrivacyException.Code.INVALID_CONTENT);
            int width=info.getSize().getWidth(),height=info.getSize().getHeight();
            if(width<1 || height<1 || width>MAX_DIMENSION || height>MAX_DIMENSION || (long)width*height>MAX_PIXELS)
                throw new PrivacyException(PrivacyException.Code.LIMIT_EXCEEDED);
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            decoder.setOnPartialImageListener(error->false);
        });
        try(BoundedOutput output=new BoundedOutput()) {
            authorization.run();
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new PrivacyException(PrivacyException.Code.INVALID_CONTENT);
            authorization.run();return output.toByteArray();
        } finally { bitmap.recycle(); }
    }
    private static final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(byte[] data,int offset,int length) {
            if(length>MAX_OUTPUT-count)throw new PrivacyException(PrivacyException.Code.LIMIT_EXCEEDED);
            super.write(data,offset,length);
        }
        @Override public synchronized void write(int value) {
            if(count>=MAX_OUTPUT)throw new PrivacyException(PrivacyException.Code.LIMIT_EXCEEDED);super.write(value);
        }
        @Override public void close() { Arrays.fill(buf,(byte)0);reset(); }
    }
}
