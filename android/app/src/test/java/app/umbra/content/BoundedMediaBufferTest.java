package app.umbra.content;

import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedMediaBufferTest {
    @Test public void seekOverwriteAndIndependentCopyRemainBounded() {
        try(var buffer=new BoundedMediaBuffer()) {
            buffer.write(4,2,new byte[]{5,6});buffer.write(0,4,new byte[]{1,2,3,4});buffer.write(1,1,new byte[]{9});
            assertArrayEquals(new byte[]{1,9,3,4,5,6},buffer.copy());
            byte[] copy=buffer.copy();copy[0]=0;assertEquals(1,buffer.copy()[0]);
            byte[] target=new byte[3];assertEquals(2,buffer.read(4,3,target));assertArrayEquals(new byte[]{5,6,0},target);
            assertEquals(0,buffer.read(Long.MAX_VALUE,3,target));
        }
    }
    @Test public void invalidOffsetsExhaustionAndClosedBufferFail() {
        var buffer=new BoundedMediaBuffer();byte[] block=new byte[RestrictedPayload.MAX_BYTES];
        assertThrows(ContentException.class,()->buffer.write(-1,1,block));
        assertThrows(ContentException.class,()->buffer.write(Long.MAX_VALUE,1,block));
        assertThrows(ContentException.class,()->buffer.write(block.length,1,block));
        assertThrows(ContentException.class,()->buffer.write(0,2,new byte[1]));
        assertThrows(ContentException.class,buffer::copy);
        for(int i=0;i<8;i++)buffer.write(0,block.length,block);
        assertThrows(ContentException.class,()->buffer.write(0,1,block));
        buffer.close();buffer.close();assertThrows(ContentException.class,buffer::copy);
        assertThrows(ContentException.class,()->buffer.write(0,0,block));
    }
}
