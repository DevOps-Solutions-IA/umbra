package app.umbra.content;

import org.junit.Test;
import static org.junit.Assert.*;

/** Codec-independent profile/ownership rejection. Actual decode remains Android acceptance. */
public class RestrictedVideoProfileTest {
    @Test public void dimensionsTimelineCapacityAndCleanupAreEnforced() {
        for(int[] bounds:new int[][]{{0,48},{64,0},{321,240},{320,241},{63,48},{64,47}})
            assertThrows(ContentException.class,()->new RestrictedVideo.Frames(bounds[0],bounds[1]));
        byte[] pixels=new byte[64*48*3/2];pixels[0]=25;
        try(var frames=new RestrictedVideo.Frames(64,48)) {
            assertThrows(ContentException.class,()->frames.add(new byte[1],0));
            assertThrows(ContentException.class,()->frames.add(pixels,1));
            frames.add(pixels,0);
            for(long time:new long[]{-1,0,65000,500001,RestrictedVideo.MAX_DURATION_US,Long.MAX_VALUE})
                assertThrows(ContentException.class,()->frames.add(new byte[pixels.length],time));
            for(int n=1;n<45;n++)frames.add(new byte[pixels.length],n*66666L);
            assertThrows(ContentException.class,()->frames.add(new byte[pixels.length],45*66666L));
        }
        assertEquals(0,pixels[0]);
    }
}
