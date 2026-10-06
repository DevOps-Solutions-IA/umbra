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
    @Test public void multiPageTruncationAndExactCapacityBoundariesFailClosed() {
        byte[] valid=DocumentPages.pack(List.of(new byte[8],new byte[13],new byte[21],new byte[34]));
        assertEquals(4,DocumentPages.parse(valid).size());
        for(int size=0;size<valid.length;size++) {
            byte[] truncated=Arrays.copyOf(valid,size);
            assertThrows("prefix="+size,ContentException.class,()->DocumentPages.parse(truncated));
        }
        for(int at:new int[]{8,20,37,62}) {
            for(int length:new int[]{Integer.MIN_VALUE,-1,0,7,valid.length,Integer.MAX_VALUE}) {
                byte[] mutated=valid.clone();ByteBuffer.wrap(mutated).putInt(at,length);
                assertThrows("offset="+at+" length="+length,ContentException.class,()->DocumentPages.parse(mutated));
            }
        }
        byte[] maximum=DocumentPages.pack(List.of(new byte[RestrictedPayload.MAX_BYTES-12]));
        assertEquals(RestrictedPayload.MAX_BYTES,maximum.length);
        assertEquals(maximum.length-12,DocumentPages.parse(maximum).get(0).length());
        assertThrows(ContentException.class,()->DocumentPages.parse(Arrays.copyOf(maximum,maximum.length+1)));
        ContentException capacity=assertThrows(ContentException.class,
                ()->DocumentPages.pack(List.of(new byte[RestrictedPayload.MAX_BYTES-11])));
        assertEquals(ContentException.Code.CAPACITY,capacity.code());
    }
    @Test public void fixedSeedStructuredMutationsRejectOrDescribeExactBoundedSlices() {
        final long seed=0x554d425241L;Random random=new Random(seed);
        byte[] valid=DocumentPages.pack(List.of(new byte[8],new byte[16],new byte[24],new byte[32]));
        for(int iteration=0;iteration<1024;iteration++) {
            byte[] input=valid.clone();
            for(int n=0,count=1+random.nextInt(4);n<count;n++)
                input[random.nextInt(input.length)]^=(byte)(1+random.nextInt(255));
            String context="seed="+seed+" iteration="+iteration;
            try {
                var slices=DocumentPages.parse(input);
                assertTrue(context,slices.size()>=1 && slices.size()<=4);
                int end=8;
                for(var slice:slices) {
                    assertEquals(context,end+4,slice.offset());assertTrue(context,slice.length()>=8);
                    assertTrue(context,slice.offset()<=input.length-slice.length());
                    end=slice.offset()+slice.length();
                }
                assertEquals(context,input.length,end);
            } catch(ContentException rejected) {assertEquals(context,ContentException.Code.INVALID,rejected.code());}
        }
    }
}
