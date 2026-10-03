package app.umbra;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Test-only observation before explicit consent. Never connects or requests a network. */
public final class StartupNetworkReadiness {
    private StartupNetworkReadiness() {}
    public enum Stage { INITIAL, RECOVERY }
    public enum Outcome { READY, TIMEOUT, FAILED }
    public record Receipt(Stage stage, long elapsedMillis, boolean defaultNetworkPresent, Outcome outcome) {}
    @FunctionalInterface public interface Denial { void check() throws Exception; }
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    @FunctionalInterface public interface Observer { void record(Receipt receipt) throws Exception; }

    public static void await(Stage stage, LongSupplier clock, BooleanSupplier available,
                             Denial denied, Sleeper sleeper, Observer observer) throws Exception {
        java.util.Objects.requireNonNull(stage);
        long began=clock.getAsLong(),elapsed=0;
        boolean present=false;
        Outcome outcome=Outcome.FAILED;
        Throwable originalFailure=null;
        try {
            while(true) {
                // Readiness is never permission. Preserve rejection on every observation.
                denied.check();
                present=available.getAsBoolean();
                elapsed=clock.getAsLong()-began;
                if(elapsed<0)throw new AssertionError("Lab readiness clock moved backwards");
                if(elapsed>=15_000) {
                    outcome=Outcome.TIMEOUT;
                    throw new AssertionError("Lab default network unavailable within readiness budget");
                }
                if(present) {outcome=Outcome.READY;return;}
                sleeper.sleep(Math.min(50,15_000-elapsed));
            }
        } catch(Exception | Error failure) {
            originalFailure=failure;
            throw failure;
        } finally {
            try {observer.record(new Receipt(stage,elapsed,present,outcome));}
            catch(Exception | Error diagnosticFailure) {
                if(originalFailure==null)throw diagnosticFailure;
                originalFailure.addSuppressed(diagnosticFailure);
            }
        }
    }
}
