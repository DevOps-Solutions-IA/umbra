package app.umbra.content;

import android.content.Context;
import android.graphics.*;
import app.umbra.crypto.Engine;
import java.util.*;

/** Static PDF-derived pages. No original PDF, URI, copy, print, text or export capability. */
public final class RestrictedDocuments {
    private RestrictedDocuments() {}
    public static RestrictedContentService.Prepared prepare(Context context,Engine engine,
            RestrictedContentService.Review review,byte[] pdf,boolean confirmed)throws Exception {
        return PdfPreparation.prepare(context,engine,review,pdf,confirmed);
    }
    public static final class Decoder implements AutoCloseable {
        private final RestrictedContentService.Session session;
        private final List<Bitmap> pages;
        public Decoder(RestrictedContentService.Session session)throws Exception {
            this.session=session;
            if(session.format()!=RestrictedPayload.Format.PDF_PAGES)throw RestrictedPayload.invalid();
            pages=session.decode(bytes->{
                var decoded=new ArrayList<Bitmap>();
                try {
                    for(var slice:DocumentPages.parse(bytes)) {
                        session.check();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                        BitmapFactory.decodeByteArray(bytes,slice.offset(),slice.length(),bounds);
                        if(!"image/png".equals(bounds.outMimeType) || bounds.outWidth<1 || bounds.outHeight<1 ||
                                bounds.outWidth>DocumentPages.MAX_EDGE || bounds.outHeight>DocumentPages.MAX_EDGE)throw RestrictedPayload.invalid();
                        Bitmap page=BitmapFactory.decodeByteArray(bytes,slice.offset(),slice.length());
                        if(page==null)throw RestrictedPayload.invalid();decoded.add(page);
                    }
                    return List.copyOf(decoded);
                }catch(Exception | Error failure){
                    try{recycle(decoded);}catch(RuntimeException cleanupFailure){session.cleanupFailed();}
                    throw failure;
                }
            },Decoder::recycle);
        }
        public int pageCount()throws Exception{session.check();return pages.size();}
        /** Navigation/zoom inside the already consumed session, on a protected caller-owned Canvas. */
        public void render(int page,Canvas canvas,Rect destination)throws Exception {
            if(page<0 || page>=pages.size() || canvas==null || destination==null || destination.width()<1 || destination.height()<1)
                throw RestrictedPayload.invalid();
            session.use(()->canvas.drawBitmap(pages.get(page),null,destination,null));
        }
        private static void recycle(List<Bitmap> pages) {
            boolean failed=false;
            for(Bitmap page:pages)try{page.recycle();}catch(RuntimeException failure){failed=true;}
            if(failed)throw new IllegalStateException("Document bitmap closure failed");
        }
        @Override public void close(){session.close();}
    }
}
