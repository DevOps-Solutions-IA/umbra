package app.umbra.content;

/** Invalid authenticated peer payload for rejection tests; absent from application APKs. */
public final class SyntheticDocuments {
    private SyntheticDocuments() {}
    public static RestrictedContentService.Prepared prepare(android.content.Context context,
            app.umbra.crypto.Engine engine,RestrictedContentService.Review review,int color)throws Exception {
        var document=new android.graphics.pdf.PdfDocument();byte[] source=null;
        try(var output=new java.io.ByteArrayOutputStream()) {
            for(int index=0;index<2;index++) {
                var page=document.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(32,24,index).create());
                page.getCanvas().drawColor(index==0?color:android.graphics.Color.WHITE);document.finishPage(page);
            }
            document.writeTo(output);source=output.toByteArray();
            return RestrictedDocuments.prepare(context,engine,review,source,true);
        }finally{document.close();if(source!=null)java.util.Arrays.fill(source,(byte)0);}
    }
    public static void observe(RestrictedContentService.Session session,int expectedColor)throws Exception {
        var bitmap=android.graphics.Bitmap.createBitmap(32,24,android.graphics.Bitmap.Config.ARGB_8888);
        try(var decoder=new RestrictedDocuments.Decoder(session)) {
            org.junit.Assert.assertEquals(2,decoder.pageCount());
            var canvas=new android.graphics.Canvas(bitmap);var rectangle=new android.graphics.Rect(0,0,32,24);
            decoder.render(0,canvas,rectangle);org.junit.Assert.assertEquals(expectedColor,bitmap.getPixel(12,12));
            decoder.render(1,canvas,rectangle);org.junit.Assert.assertEquals(android.graphics.Color.WHITE,bitmap.getPixel(12,12));
        }finally{bitmap.recycle();session.close();session.closure().toCompletableFuture().get(3,java.util.concurrent.TimeUnit.SECONDS);}
    }
    public static RestrictedContentService.Prepared invalidRaster(Runnable authorization) {
        return new RestrictedContentService.Prepared(RestrictedPayload.Format.PDF_PAGES,
            DocumentPages.pack(java.util.List.of(new byte[8])),authorization);
    }
}
