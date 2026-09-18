package app.umbra.core;

/** Capped backoff. Caller persists counters; retries must reuse the original ciphertext. */
public final class RetryPolicy {
    private RetryPolicy() {}
    public static long delaySeconds(int attempts, double randomUnit) {
        if (attempts < 0 || !Double.isFinite(randomUnit) || randomUnit < 0 || randomUnit >= 1)
            throw new IllegalArgumentException("Invalid retry state");
        long base = Math.min(300L, 5L << Math.min(attempts, 6));
        return Math.max(1, Math.min(300, (long) (base * (0.8 + 0.4 * randomUnit))));
    }
    public static long next(long now, int attempts, double randomUnit) {
        if (now < 0) throw new IllegalArgumentException("Invalid time");
        return Math.addExact(now, delaySeconds(attempts, randomUnit));
    }
}
