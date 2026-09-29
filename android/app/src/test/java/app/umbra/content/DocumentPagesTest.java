package app.umbra.content;

import java.util.*;
import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Container framing only. Native PNG/PDF rendering requires Android acceptance separately. */
public class DocumentPagesTest {
    @Test public void canonicalPagesAndOriginalBytesRemainUnchanged() {
        byte[] first={1,2,3,4,5,6,7,8},second={8,7,6,5,4,3,2,1};
        byte[] packed=DocumentPages.pack(List.of(first,second));var slices=DocumentPages.parse(packed);
        assertEquals(2,slices.size());
        assertArrayEquals(first,Arrays.copyOfRange(packed,slices.get(0).offset(),slices.get(0).offset()+slices.get(0).length()));
        assertArrayEquals(second,Arrays.copyOfRange(packed,slices.get(1).offset(),slices.get(1).offset()+slices.get(1).length()));
        packed[slices.get(0).offset()]=0;assertEquals(1,first[0]);
    }
    @Test public void versionsLengthsCountsAndTrailingBytesFailClosed() {
        byte[] valid=DocumentPages.pack(List.of(new byte[8]));
        for(int at:new int[]{0,4,8}) {
            byte[] bad=valid.clone();ByteBuffer.wrap(bad).putInt(at,Integer.MAX_VALUE);
            assertThrows(ContentException.class,()->DocumentPages.parse(bad));
        }
        for(int n=0;n<valid.length;n++) {
            byte[] truncated=Arrays.copyOf(valid,n);assertThrows(ContentException.class,()->DocumentPages.parse(truncated));
        }
        assertThrows(ContentException.class,()->DocumentPages.parse(Arrays.copyOf(valid,valid.length+1)));
        assertThrows(ContentException.class,()->DocumentPages.pack(Collections.nCopies(5,new byte[8])));
        assertThrows(ContentException.class,()->DocumentPages.pack(List.of(new byte[RestrictedPayload.MAX_BYTES])));
        assertThrows(ContentException.class,()->DocumentPages.parse(null));
    }
    @Test public void boundedRandomInputNeverEscapesAsParserRuntimeCrash() {
        Random random=new Random(0x554d4252);
        for(int n=0;n<2000;n++) {
            byte[] input=new byte[random.nextInt(1024)];random.nextBytes(input);
            try {DocumentPages.parse(input);fail("Random bytes unexpectedly canonical");}
            catch(ContentException expected) {assertEquals(ContentException.Code.INVALID,expected.code());}
        }
    }
}
