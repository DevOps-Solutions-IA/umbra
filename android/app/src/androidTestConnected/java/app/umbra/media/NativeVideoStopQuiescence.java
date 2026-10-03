package app.umbra.media;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Test APK only: observes the known native executor becoming idle after local stop. */
final class NativeVideoStopQuiescence {
    private NativeVideoStopQuiescence() {}
    @FunctionalInterface interface Sleeper { void pause(long nanos) throws InterruptedException; }

    static void await(Thread expected, Supplier<Thread> currentWorker, long requestedNanos,
                      LongSupplier clock, Sleeper sleeper) throws InterruptedException {
        if(expected==null || requestedNanos<=0)throw new AssertionError("Missing native stop worker or request");
        while(true) {
            long elapsed=clock.getAsLong()-requestedNanos;
            if(elapsed<0 || elapsed>=2_000_000_000L)
                throw new AssertionError("Native stop worker did not become idle within closure budget");
            if(currentWorker.get()!=expected || !expected.isAlive())
                throw new AssertionError("Native stop worker identity changed or exited");
            Thread.State state=expected.getState();
            StackTraceElement[] stack=expected.getStackTrace();
            if(executorIdle(state,stack)) {
                if(currentWorker.get()!=expected || !expected.isAlive())
                    throw new AssertionError("Native stop worker identity changed or exited");
                elapsed=clock.getAsLong()-requestedNanos;
                if(elapsed<0 || elapsed>=2_000_000_000L)
                    throw new AssertionError("Native stop idle observation exceeded closure budget");
                return;
            }
            elapsed=clock.getAsLong()-requestedNanos;
            if(elapsed<0 || elapsed>=2_000_000_000L)
                throw new AssertionError("Native stop worker did not become idle within closure budget");
            sleeper.pause(Math.min(50_000_000L,2_000_000_000L-elapsed));
        }
    }

    static boolean executorIdle(Thread.State state, StackTraceElement[] stack) {
        if(state!=Thread.State.WAITING && state!=Thread.State.TIMED_WAITING)return false;
        // An arbitrary task waiting on a queue/HTTP/latch is not executor quiescence.
        // Require the scheduler queue directly feeding this worker's getTask loop.
        for(int i=0;i+4<stack.length;i++) {
            if(!frame(stack[i],"java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue","take"))continue;
            int next=i+1;
            // Java/Android may retain the generic BlockingQueue bridge frame.
            if(next<stack.length && frame(stack[next],"java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue","take"))next++;
            if(next+4!=stack.length)continue;
            if(frame(stack[next],"java.util.concurrent.ThreadPoolExecutor","getTask") &&
               frame(stack[next+1],"java.util.concurrent.ThreadPoolExecutor","runWorker") &&
               frame(stack[next+2],"java.util.concurrent.ThreadPoolExecutor$Worker","run") &&
               frame(stack[next+3],"java.lang.Thread","run"))return true;
        }
        return false;
    }
    private static boolean frame(StackTraceElement value,String owner,String method) {
        return owner.equals(value.getClassName()) && method.equals(value.getMethodName());
    }
}
