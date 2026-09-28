package app.umbra.content;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Narrow Android AAC-LC note preparation, RAM only. No capture or permission request here. */
public final class RestrictedAudio {
    public static final int SAMPLE_RATE=16000,MAX_FRAMES=156;
    private RestrictedAudio() {}
    static void requireFormat(MediaFormat format) {
        if(!MediaFormat.MIMETYPE_AUDIO_AAC.equals(format.getString(MediaFormat.KEY_MIME)) ||
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=1 || format.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=SAMPLE_RATE)
            throw RestrictedPayload.invalid();
        ByteBuffer config=format.getByteBuffer("csd-0");
        if(config==null || config.remaining()!=2 || (config.get(config.position())&255)!=0x14 || (config.get(config.position()+1)&255)!=0x08)
            throw RestrictedPayload.invalid();
    }
    /** Decode and re-encode: container metadata and AAC ancillary bytes are not copied. */
    public static RestrictedContentService.Prepared prepare(byte[] encoded,Runnable authorization)throws Exception {
        short[] pcm=decode(encoded,authorization);
        try{return encode(pcm,authorization);}finally{Arrays.fill(pcm,(short)0);}
    }
    /** Bounded native decode; only internal codec adapters/tests may access temporary PCM. */
    static short[] decode(byte[] encoded,Runnable authorization)throws Exception {
        authorization.run();MediaExtractor extractor=new MediaExtractor();
        android.media.MediaCodec codec=null;short[] pcm=new short[SAMPLE_RATE*9];
        int samples=0,frames=0;long previous=-1,started=System.nanoTime();
        boolean inputEnded=false,outputEnded=false;
        try(var source=new MemoryMediaSource(encoded,authorization)) {
            extractor.setDataSource(source);
            if(extractor.getTrackCount()!=1)throw RestrictedPayload.invalid();
            MediaFormat format=extractor.getTrackFormat(0);requireFormat(format);extractor.selectTrack(0);
            codec=android.media.MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format,null,null,0);codec.start();
            var info=new android.media.MediaCodec.BufferInfo();
            while(!outputEnded) {
                authorization.run();if(System.nanoTime()-started>10_000_000_000L)throw RestrictedPayload.invalid();
                if(!inputEnded) {
                    int index=codec.dequeueInputBuffer(1000);
                    if(index>=0) {
                        ByteBuffer input=java.util.Objects.requireNonNull(codec.getInputBuffer(index));input.clear();
                        long timestamp=extractor.getSampleTime();int count=0,flags=0;
                        if(timestamp<0){inputEnded=true;flags=android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM;timestamp=Math.max(0,previous);}
                        else {
                            if(++frames>MAX_FRAMES || (previous>=0 && (timestamp<=previous || timestamp-previous>65000)) ||
                                    (extractor.getSampleFlags()&~MediaExtractor.SAMPLE_FLAG_SYNC)!=0)throw RestrictedPayload.invalid();
                            previous=timestamp;long size=extractor.getSampleSize();
                            if(size<1 || size>8184 || size>input.remaining())throw RestrictedPayload.invalid();
                            count=extractor.readSampleData(input,0);if(count!=size)throw RestrictedPayload.invalid();extractor.advance();
                        }
                        codec.queueInputBuffer(index,0,count,timestamp,flags);
                    }
                }
                int index=codec.dequeueOutputBuffer(info,1000);
                if(index==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output=codec.getOutputFormat();
                    if(output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)!=1 || output.getInteger(MediaFormat.KEY_SAMPLE_RATE)!=SAMPLE_RATE ||
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING,android.media.AudioFormat.ENCODING_PCM_16BIT)!=android.media.AudioFormat.ENCODING_PCM_16BIT)
                        throw RestrictedPayload.invalid();
                } else if(index>=0) {
                    try {
                        if(info.size%2!=0 || info.size/2>pcm.length-samples)throw RestrictedPayload.invalid();
                        ByteBuffer output=java.util.Objects.requireNonNull(codec.getOutputBuffer(index)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        output.position(info.offset);output.limit(info.offset+info.size);
                        while(output.remaining()>=2)pcm[samples++]=output.getShort();
                        outputEnded=(info.flags&android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    } finally {codec.releaseOutputBuffer(index,false);}
                }
            }
            authorization.run();if(samples<1024)throw RestrictedPayload.invalid();return Arrays.copyOf(pcm,samples);
        } finally {
            try{if(codec!=null)codec.release();}finally{extractor.release();Arrays.fill(pcm,(short)0);}
        }
    }
    /** Internal capture/test codec input; no public PCM getter or persisted recording. */
    static RestrictedContentService.Prepared encode(short[] pcm,Runnable authorization)throws Exception {
        if(pcm.length<1024 || pcm.length>SAMPLE_RATE*9)throw RestrictedPayload.invalid();
        authorization.run();
        android.media.MediaCodec codec=android.media.MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        byte[] encoded=new byte[RestrictedPayload.MAX_BYTES];int written=0,queued=0,frames=0;
        // AAC-LC encodes 1024-sample access units. Do not rely on an encoder
        // emitting an incomplete PCM frame at EOS; append only zero alignment samples.
        int alignedSamples=((pcm.length+1023)/1024)*1024;
        long started=System.nanoTime();boolean inputEnded=false,outputEnded=false;
        try {
            MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,SAMPLE_RATE,1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE,24000);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2048);
            codec.configure(format,null,null,android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
            var info=new android.media.MediaCodec.BufferInfo();
            while(!outputEnded) {
                authorization.run();if(System.nanoTime()-started>10_000_000_000L)throw RestrictedPayload.invalid();
                if(!inputEnded) {
                    int index=codec.dequeueInputBuffer(1000);
                    if(index>=0) {
                        ByteBuffer input=java.util.Objects.requireNonNull(codec.getInputBuffer(index)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        input.clear();int count=Math.min(Math.min(1024,input.remaining()/2),alignedSamples-queued);
                        if(count==0 && queued<alignedSamples)throw RestrictedPayload.invalid();
                        for(int i=0;i<count;i++)input.putShort(queued+i<pcm.length?pcm[queued+i]:(short)0);
                        long timestamp=queued*1_000_000L/SAMPLE_RATE;queued+=count;inputEnded=count==0;
                        codec.queueInputBuffer(index,0,count*2,timestamp,inputEnded?android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM:0);
                    }
                }
                int index=codec.dequeueOutputBuffer(info,1000);
                if(index==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)requireFormat(codec.getOutputFormat());
                else if(index>=0) {
                    try {
                        if(info.size>0 && (info.flags&android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                            if(++frames>MAX_FRAMES || info.size>8184 || info.size+7>encoded.length-written)throw RestrictedPayload.invalid();
                            ByteBuffer output=java.util.Objects.requireNonNull(codec.getOutputBuffer(index));
                            output.position(info.offset);output.limit(info.offset+info.size);
                            header(encoded,written,info.size);written+=7;output.get(encoded,written,info.size);written+=info.size;
                        }
                        outputEnded=(info.flags&android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    } finally {codec.releaseOutputBuffer(index,false);}
                }
            }
            if(frames<1)throw RestrictedPayload.invalid();authorization.run();
            return new RestrictedContentService.Prepared(RestrictedPayload.Format.AAC_ADTS,Arrays.copyOf(encoded,written));
        } finally {codec.release();Arrays.fill(encoded,(byte)0);}
    }

    /** ISO ADTS header for the fixed AAC-LC/16kHz/mono profile, one raw access unit. */
    static void header(byte[] target,int at,int payload) {
        int length=payload+7;
        target[at]=(byte)0xff;target[at+1]=(byte)0xf1;target[at+2]=(byte)0x60;
        target[at+3]=(byte)(0x40|(length>>11));target[at+4]=(byte)(length>>3);
        target[at+5]=(byte)(((length&7)<<5)|0x1f);target[at+6]=(byte)0xfc;
    }
}
