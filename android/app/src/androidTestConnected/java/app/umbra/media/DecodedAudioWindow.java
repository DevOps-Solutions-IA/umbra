package app.umbra.media;

/** Test-only bounded observation sampled on the real decoded-audio callback, never the HTTPS loop. */
public final class DecodedAudioWindow {
    public record Result(long settleMillis,long observedMillis,int natural,int modified,int loud,int video,String failure) {}
    private final long requested,settleMin,settleMax,observeMin,observeMax;
    private long started=-1;
    private int natural,modified,loud,video;
    private volatile Result result;
    public DecodedAudioWindow(long requested,long settleMin,long settleMax,long observeMin,long observeMax) {
        if(requested<0 || settleMin<=0 || settleMax<settleMin || observeMin<=0 || observeMax<observeMin)
            throw new IllegalArgumentException("Invalid synthetic observation bounds");
        this.requested=requested;this.settleMin=settleMin;this.settleMax=settleMax;this.observeMin=observeMin;this.observeMax=observeMax;
    }
    /** Single callback writer; counters are monotonic and no PCM is retained. */
    public void sample(long now,int natural,int modified,int loud,int video) {
        if(result!=null)return;
        if(now<requested || natural<0 || modified<0 || loud<0 || video<0) {fail("Invalid callback counters");return;}
        if(started<0) {
            if(now-requested<settleMin)return;
            if(now-requested>settleMax) {fail("Decoded callback settling deadline exceeded");return;}
            started=now;this.natural=natural;this.modified=modified;this.loud=loud;this.video=video;return;
        }
        if(natural<this.natural || modified<this.modified || loud<this.loud || video<this.video) {fail("Decoded counters regressed");return;}
        if(now-started<observeMin)return;
        if(now-started>observeMax) {fail("Decoded callback observation deadline exceeded");return;}
        result=new Result(started-requested,now-started,natural-this.natural,modified-this.modified,loud-this.loud,video-this.video,"");
    }
    private void fail(String reason) {result=new Result(0,0,0,0,0,0,reason);}
    public Result result() {return result;}
}
