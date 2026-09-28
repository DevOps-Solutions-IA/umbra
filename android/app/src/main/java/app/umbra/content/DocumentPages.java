package app.umbra.content;

import java.nio.ByteBuffer;
import java.util.List;

/** Internal v1 static PDF-derived page pack; no PDF actions, paths or external resources. */
final class DocumentPages {
    static final int MAX_PAGES=4,MAX_EDGE=768;
    private static final int MAGIC=0x55504731;
    record Slice(int offset,int length) {}
    private DocumentPages() {}
    static byte[] pack(List<byte[]> pages) {
        if(pages==null || pages.isEmpty() || pages.size()>MAX_PAGES)throw RestrictedPayload.invalid();
        long size=8;
        for(byte[] page:pages) {
            if(page==null || page.length<8)throw RestrictedPayload.invalid();
            size+=4L+page.length;
            if(size>RestrictedPayload.MAX_BYTES)throw new ContentException(ContentException.Code.CAPACITY);
        }
        ByteBuffer out=ByteBuffer.allocate((int)size);
        out.putInt(MAGIC).putInt(pages.size());
        for(byte[] page:pages)out.putInt(page.length).put(page);
        return out.array();
    }
    static List<Slice> parse(byte[] bytes) {
        if(bytes==null || bytes.length<20 || bytes.length>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
        ByteBuffer in=ByteBuffer.wrap(bytes);
        if(in.getInt()!=MAGIC)throw RestrictedPayload.invalid();
        int count=in.getInt();if(count<1 || count>MAX_PAGES)throw RestrictedPayload.invalid();
        var result=new java.util.ArrayList<Slice>(count);
        for(int i=0;i<count;i++) {
            if(in.remaining()<4)throw RestrictedPayload.invalid();
            int length=in.getInt();
            if(length<8 || length>in.remaining())throw RestrictedPayload.invalid();
            result.add(new Slice(in.position(),length));in.position(in.position()+length);
        }
        if(in.hasRemaining())throw RestrictedPayload.invalid();
        return List.copyOf(result);
    }
}
