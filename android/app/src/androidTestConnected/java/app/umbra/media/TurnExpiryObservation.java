package app.umbra.media;

import java.util.function.LongSupplier;

/** Test APK only: records clock observations without changing any returned clock value. */
final class TurnExpiryObservation implements LongSupplier {
    private final LongSupplier clock;
    private final long remaining;
    private boolean started;
    private long initial, firstExpiry=-1;
    private int reads;
    TurnExpiryObservation(LongSupplier clock,long remaining) { this.clock=clock;this.remaining=remaining; }
    @Override public synchronized long getAsLong() {
        long now=clock.getAsLong();
        if(!started) { initial=now;started=true; }
        if(reads<Integer.MAX_VALUE)reads++;
        long elapsed=now-initial;
        if(firstExpiry<0 && elapsed>=remaining)firstExpiry=elapsed;
        return now;
    }
    synchronized long[] snapshot(long now) {
        return new long[]{remaining,started?now-initial:-1,firstExpiry,reads};
    }
}
