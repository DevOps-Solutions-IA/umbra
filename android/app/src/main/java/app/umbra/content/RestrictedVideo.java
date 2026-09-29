package app.umbra.content;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.*;
import android.os.Looper;
import app.umbra.core.EmergencyLock;
import app.umbra.crypto.Engine;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small file-video import, not capture or WebRTC. Only locally re-encoded media is retained. */
public final class RestrictedVideo {
    public static final int MAX_WIDTH=320,MAX_HEIGHT=240,MAX_FRAMES=45;
    public static final long MAX_DURATION_US=3_000_000;
    private static final Semaphore SLOT=new Semaphore(1);
    private RestrictedVideo() {}
    public static RestrictedContentService.Prepared prepare(Context context,Engine engine,
            RestrictedContentService.Review review,byte[] input,boolean confirmed)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Preparation requires worker");
        if(input==null || input.length<1 || input.length>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
        var grant=engine.restricted().preparationAuthorization(review,confirmed);
        if(!SLOT.tryAcquire())throw new ContentException(ContentException.Code.BUSY);
        var operation=new Operation(grant);
        Runnable check=operation;
        var closed=new CompletableFuture<Void>();
        EmergencyLock.Registration registration=null;
        try {
            registration=engine.emergency().register(EmergencyLock.Subsystem.DOCUMENTS,()->{operation.cancelled.set(true);return closed;});
            byte[] result;
            try(var frames=decode(input,check);var track=encode(frames,check)) {
                byte[] audio=extractAudio(input,check),cleanAudio=null;
                try {
                    if(audio!=null) {
                        short[] pcm=RestrictedAudio.decode(audio,check);
                        try {
                            if(pcm.length>(RestrictedAudio.SAMPLE_RATE*3/1024-2)*1024)throw RestrictedPayload.invalid();
                            cleanAudio=RestrictedAudio.encodeBytes(pcm,check);
                        }finally{Arrays.fill(pcm,(short)0);}
                    }
                    result=mux(context,track,cleanAudio,check);
                }finally{if(audio!=null)Arrays.fill(audio,(byte)0);if(cleanAudio!=null)Arrays.fill(cleanAudio,(byte)0);}
            }
            try {check.run();return engine.restricted().retainPrepared(review,
                new RestrictedContentService.Prepared(RestrictedPayload.Format.AVC_MP4,result,operation.authorized));}
            catch(Exception | Error failure){Arrays.fill(result,(byte)0);throw failure;}
        } finally {
            operation.cancelled.set(true);
            if(operation.cleanupFailed) {
                closed.completeExceptionally(new IllegalStateException("Video cleanup unconfirmed"));
                engine.emergencyLock(); // Keeps authorization denied; no overlapping codec job.
            } else {
                closed.complete(null);if(registration!=null)registration.close();SLOT.release();
            }
        }
    }
    private static final class Operation implements Runnable {
        final Runnable authorized;final AtomicBoolean cancelled=new AtomicBoolean();
        final long started=System.nanoTime();volatile boolean cleanupFailed;
        Operation(app.umbra.data.Records.Work<Void> grant) {
            authorized=()->{try{grant.run();}catch(RuntimeException denied){throw denied;}
                catch(Exception denied){throw new ContentException(ContentException.Code.CONSENT_REQUIRED);}};
        }
        @Override public void run(){if(cancelled.get() || System.nanoTime()-started>20_000_000_000L)throw RestrictedPayload.invalid();authorized.run();}
    }
    static void markCleanupFailure(Runnable check){if(check instanceof Operation operation)operation.cleanupFailed=true;}
    private static void release(Runnable check,AutoCloseable... resources)throws Exception {
        boolean failed=false;
        for(var resource:resources)if(resource!=null)try{resource.close();}catch(Exception failure){failed=true;}
        if(failed){markCleanupFailure(check);throw new IllegalStateException("Video cleanup unconfirmed");}
    }

    static final class Frames implements AutoCloseable {
        final int width,height;final List<byte[]> pixels=new ArrayList<>();final List<Long> times=new ArrayList<>();
        Frames(int width,int height){dimensions(width,height);this.width=width;this.height=height;}
        void add(byte[] yuv,long time) {
            if(pixels.size()>=MAX_FRAMES || yuv.length!=width*height*3/2 || time<0 || time>=MAX_DURATION_US ||
                (times.isEmpty()?time!=0:time-times.get(times.size()-1)<66_000 || time-times.get(times.size()-1)>500_000))throw RestrictedPayload.invalid();
            pixels.add(yuv);times.add(time);
        }
        @Override public void close(){for(byte[] bytes:pixels)Arrays.fill(bytes,(byte)0);pixels.clear();times.clear();}
    }
    static void dimensions(int width,int height) {
        if(width<16 || height<16 || width>MAX_WIDTH || height>MAX_HEIGHT || width%2!=0 || height%2!=0)throw RestrictedPayload.invalid();
    }
    private static void videoFormat(MediaFormat format) {
        if(!MediaFormat.MIMETYPE_VIDEO_AVC.equals(format.getString(MediaFormat.KEY_MIME)) ||
                format.getInteger(MediaFormat.KEY_ROTATION,0)!=0)throw RestrictedPayload.invalid();
        dimensions(format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT));
    }
    static void decodedFormat(String mime,int width,int height) {
        // A decoder emits raw pixels, not an AVC compressed track. Encoder/extractor
        // formats remain separately restricted to video/avc.
        if(!"video/raw".equals(mime))throw RestrictedPayload.invalid();dimensions(width,height);
    }
    private static int tracks(MediaExtractor extractor) {
        if(extractor.getTrackCount()<1 || extractor.getTrackCount()>2)throw RestrictedPayload.invalid();
        int video=-1,audio=-1;
        for(int n=0;n<extractor.getTrackCount();n++) {
            MediaFormat format=extractor.getTrackFormat(n);String mime=format.getString(MediaFormat.KEY_MIME);
            if(MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)){if(video!=-1)throw RestrictedPayload.invalid();videoFormat(format);video=n;}
            else if(MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)){if(audio!=-1)throw RestrictedPayload.invalid();RestrictedAudio.requireFormat(format);audio=n;}
            else throw RestrictedPayload.invalid();
        }
        if(video<0)throw RestrictedPayload.invalid();return video;
    }
    static Frames decode(byte[] input,Runnable check)throws Exception {
        check.run();
        if(input==null || input.length<24 || input.length>RestrictedPayload.MAX_BYTES ||
                input[4]!='f' || input[5]!='t' || input[6]!='y' || input[7]!='p')throw RestrictedPayload.invalid();
        long box=Integer.toUnsignedLong(ByteBuffer.wrap(input,0,4).getInt());
        if(box<16 || box>input.length)throw RestrictedPayload.invalid();
        var extractor=new MediaExtractor();MediaCodec codec=null;Frames frames=null;boolean success=false;
        try(var source=new MemoryMediaSource(input,check)) {
            extractor.setDataSource(source);int video=tracks(extractor);var format=extractor.getTrackFormat(video);
            frames=new Frames(format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT));extractor.selectTrack(video);
            codec=MediaCodec.createByCodecName("c2.android.avc.decoder");
            if(!codec.getCodecInfo().isSoftwareOnly())throw RestrictedPayload.invalid();
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec.configure(format,null,null,0);codec.start();
            boolean ended=false,done=false;int packets=0;long previous=-1;var info=new MediaCodec.BufferInfo();
            while(!done) {
                check.run();
                if(!ended) {
                    int i=codec.dequeueInputBuffer(1000);
                    if(i>=0) {
                        ByteBuffer buffer=Objects.requireNonNull(codec.getInputBuffer(i));buffer.clear();long time=extractor.getSampleTime();int count=0;
                        if(time<0){ended=true;time=Math.max(0,previous);}
                        else {
                            if(++packets>MAX_FRAMES || time>=MAX_DURATION_US || (previous<0?time!=0:time<=previous) ||
                                    (extractor.getSampleFlags()&~MediaExtractor.SAMPLE_FLAG_SYNC)!=0)throw RestrictedPayload.invalid();
                            long size=extractor.getSampleSize();if(size<1 || size>RestrictedPayload.MAX_BYTES || size>buffer.remaining())throw RestrictedPayload.invalid();
                            count=extractor.readSampleData(buffer,0);if(count!=size)throw RestrictedPayload.invalid();previous=time;extractor.advance();
                        }
                        codec.queueInputBuffer(i,0,count,time,ended?MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                    }
                }
                int i=codec.dequeueOutputBuffer(info,1000);
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    var output=codec.getOutputFormat();
                    decodedFormat(output.getString(MediaFormat.KEY_MIME),output.getInteger(MediaFormat.KEY_WIDTH),output.getInteger(MediaFormat.KEY_HEIGHT));
                }
                else if(i>=0) {
                    try {
                        if(info.size>0) {
                            byte[] pixels=null;
                            try(var image=Objects.requireNonNull(codec.getOutputImage(i))) {
                                pixels=readImage(image,frames.width,frames.height);frames.add(pixels,info.presentationTimeUs);pixels=null;
                            }finally{if(pixels!=null)Arrays.fill(pixels,(byte)0);}
                        }
                        done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    }finally{codec.releaseOutputBuffer(i,false);}
                }
            }
            if(frames.pixels.size()<2)throw RestrictedPayload.invalid();
            long last=frames.times.get(frames.times.size()-1),before=frames.times.get(frames.times.size()-2);
            if(last+(last-before)>MAX_DURATION_US)throw RestrictedPayload.invalid();
            check.run();success=true;return frames;
        }finally {
            MediaCodec owned=codec;
            try{release(check,owned==null?null:owned::release,extractor::release);}
            catch(Exception failure){success=false;throw failure;}
            finally{if(!success && frames!=null)frames.close();}
        }
    }
    private static void imageBounds(Image image,int width,int height) {
        var crop=image.getCropRect();
        if(image.getFormat()!=ImageFormat.YUV_420_888 || image.getPlanes().length!=3 || crop.left!=0 || crop.top!=0 || crop.width()!=width || crop.height()!=height)
            throw RestrictedPayload.invalid();
    }
    static byte[] readImage(Image image,int width,int height) {
        imageBounds(image,width,height);byte[] yuv=new byte[width*height*3/2];int at=0;
        try {
            for(int p=0;p<3;p++) {
                var plane=image.getPlanes()[p];var buffer=plane.getBuffer();int base=buffer.position();int w=p==0?width:width/2,h=p==0?height:height/2;
                for(int y=0;y<h;y++)for(int x=0;x<w;x++)yuv[at++]=buffer.get(base+y*plane.getRowStride()+x*plane.getPixelStride());
            }
            return yuv;
        }catch(RuntimeException failure){Arrays.fill(yuv,(byte)0);throw RestrictedPayload.invalid();}
    }
    private static void writeImage(Image image,byte[] yuv,int width,int height) {
        imageBounds(image,width,height);int at=0;
        for(int p=0;p<3;p++) {
            var plane=image.getPlanes()[p];var buffer=plane.getBuffer();int base=buffer.position();int w=p==0?width:width/2,h=p==0?height:height/2;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)buffer.put(base+y*plane.getRowStride()+x*plane.getPixelStride(),yuv[at++]);
        }
    }
    static final class Packet {
        final byte[] bytes;final long time;final int flags;
        Packet(byte[] bytes,long time,int flags){this.bytes=bytes;this.time=time;this.flags=flags;}
    }
    static final class Track implements AutoCloseable {
        MediaFormat format;final List<Packet> packets=new ArrayList<>();int size;
        void add(byte[] bytes,long time,int flags) {
            if(packets.size()>=MAX_FRAMES || size+bytes.length>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
            size+=bytes.length;packets.add(new Packet(bytes,time,flags));
        }
        @Override public void close(){for(var p:packets)Arrays.fill(p.bytes,(byte)0);packets.clear();}
    }
    static Track encode(Frames frames,Runnable check)throws Exception {
        check.run();MediaCodec codec=MediaCodec.createByCodecName("c2.android.avc.encoder");Track track=new Track();boolean success=false;
        try {
            if(!codec.getCodecInfo().isSoftwareOnly())throw RestrictedPayload.invalid();
            MediaFormat format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,frames.width,frames.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE,160000);format.setInteger(MediaFormat.KEY_FRAME_RATE,15);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);format.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);
            format.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
            int queued=0;boolean ended=false,done=false;var info=new MediaCodec.BufferInfo();
            while(!done) {
                check.run();
                if(!ended) {
                    int i=codec.dequeueInputBuffer(1000);
                    if(i>=0) {
                        if(queued==frames.pixels.size()){codec.queueInputBuffer(i,0,0,frames.times.get(queued-1),MediaCodec.BUFFER_FLAG_END_OF_STREAM);ended=true;}
                        else {
                            try(var image=Objects.requireNonNull(codec.getInputImage(i))){writeImage(image,frames.pixels.get(queued),frames.width,frames.height);}
                            codec.queueInputBuffer(i,0,frames.width*frames.height*3/2,frames.times.get(queued),0);queued++;
                        }
                    }
                }
                int i=codec.dequeueOutputBuffer(info,1000);
                if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if(track.format!=null)throw RestrictedPayload.invalid();
                    var output=codec.getOutputFormat();videoFormat(output);
                    track.format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,frames.width,frames.height);
                    for(String name:new String[]{"csd-0","csd-1"}) {
                        var config=output.getByteBuffer(name);if(config==null || config.remaining()<1 || config.remaining()>4096)throw RestrictedPayload.invalid();
                        byte[] copy=new byte[config.remaining()];config.duplicate().get(copy);track.format.setByteBuffer(name,ByteBuffer.wrap(copy));
                    }
                }else if(i>=0) {
                    try {
                        if(info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                            if(info.size>RestrictedPayload.MAX_BYTES)throw RestrictedPayload.invalid();
                            ByteBuffer buffer=Objects.requireNonNull(codec.getOutputBuffer(i));buffer.position(info.offset);buffer.limit(info.offset+info.size);
                            byte[] packet=new byte[info.size];buffer.get(packet);
                            try{track.add(packet,info.presentationTimeUs,info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME);}
                            catch(RuntimeException failure){Arrays.fill(packet,(byte)0);throw failure;}
                        }
                        done=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    }finally{codec.releaseOutputBuffer(i,false);}
                }
            }
            if(track.format==null || track.packets.size()!=frames.pixels.size())throw RestrictedPayload.invalid();
            check.run();success=true;return track;
        }finally{try{release(check,codec::release);}catch(Exception failure){success=false;throw failure;}finally{if(!success)track.close();}}
    }
    static byte[] extractAudio(byte[] input,Runnable check)throws Exception {
        var extractor=new MediaExtractor();byte[] encoded=new byte[RestrictedPayload.MAX_BYTES];int written=0,frames=0;long previous=-1;
        try(var source=new MemoryMediaSource(input,check)) {
            extractor.setDataSource(source);tracks(extractor);int audio=-1;
            for(int n=0;n<extractor.getTrackCount();n++)if(MediaFormat.MIMETYPE_AUDIO_AAC.equals(extractor.getTrackFormat(n).getString(MediaFormat.KEY_MIME)))audio=n;
            if(audio<0)return null;extractor.selectTrack(audio);
            while(extractor.getSampleTime()>=0) {
                check.run();long time=extractor.getSampleTime(),size=extractor.getSampleSize();
                if(++frames>50 || time>=MAX_DURATION_US || (previous<0?time!=0:time<=previous || time-previous>65000) || size<1 || size>8184 || size+7>encoded.length-written ||
                        (extractor.getSampleFlags()&~MediaExtractor.SAMPLE_FLAG_SYNC)!=0)throw RestrictedPayload.invalid();
                RestrictedAudio.header(encoded,written,(int)size);written+=7;
                int count=extractor.readSampleData(ByteBuffer.wrap(encoded),written);if(count!=size)throw RestrictedPayload.invalid();
                written+=count;previous=time;extractor.advance();
            }
            if(frames<1)throw RestrictedPayload.invalid();return Arrays.copyOf(encoded,written);
        }finally{try{release(check,extractor::release);}finally{Arrays.fill(encoded,(byte)0);}}
    }
    static byte[] mux(Context context,Track video,byte[] audio,Runnable check)throws Exception {
        check.run();
        byte[] result=null;boolean success=false;
        MemoryMuxerDescriptor destination=null;
        try {
            destination=new MemoryMuxerDescriptor(context,check);
            var muxer=new MediaMuxer(destination.descriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaExtractor extractor=null;MemoryMediaSource source=null;
            try {
                int vi=muxer.addTrack(video.format),ai=-1;
                if(audio!=null) {
                    source=new MemoryMediaSource(audio,check);extractor=new MediaExtractor();extractor.setDataSource(source);
                    if(extractor.getTrackCount()!=1)throw RestrictedPayload.invalid();var format=extractor.getTrackFormat(0);RestrictedAudio.requireFormat(format);
                    ai=muxer.addTrack(format);extractor.selectTrack(0);
                }
                muxer.start();var info=new MediaCodec.BufferInfo();
                for(var packet:video.packets){check.run();info.set(0,packet.bytes.length,packet.time,packet.flags);muxer.writeSampleData(vi,ByteBuffer.wrap(packet.bytes),info);}
                if(extractor!=null) {
                    byte[] packet=new byte[8192];
                    try {
                        while(extractor.getSampleTime()>=0) {
                            check.run();int size=extractor.readSampleData(ByteBuffer.wrap(packet),0);
                            if(size<1 || size>packet.length)throw RestrictedPayload.invalid();
                            info.set(0,size,extractor.getSampleTime(),MediaCodec.BUFFER_FLAG_KEY_FRAME);muxer.writeSampleData(ai,ByteBuffer.wrap(packet),info);extractor.advance();
                        }
                    }finally{Arrays.fill(packet,(byte)0);}
                }
                muxer.stop();check.run();result=destination.copy();
            } finally {MediaExtractor owned=extractor;release(check,muxer::release,owned==null?null:owned::release,source);}
            release(check,destination);destination=null;check.run();success=true;return result;
        }finally {
            try{if(destination!=null)release(check,destination);}catch(Exception failure){success=false;throw failure;}
            finally{if(!success && result!=null)Arrays.fill(result,(byte)0);}
        }
    }
}
