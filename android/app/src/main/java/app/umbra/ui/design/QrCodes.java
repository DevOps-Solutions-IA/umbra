package app.umbra.ui.design;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

/**
 * The single production QR path (verification screen). ZXing is used only here, so the optimized app keeps
 * exactly the encoder this path needs and tests exercise this method instead of calling the library directly
 * (a separate test APK cannot rely on library classes that R8 renamed or removed from the app).
 */
public final class QrCodes {
    private QrCodes() {}
    public static final int SIZE = 320;

    /** Renders a public verification payload (never a secret) as a black-on-white QR bitmap. */
    public static Bitmap render(String payload, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }
}
