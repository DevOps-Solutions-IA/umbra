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
        int observations=0;
        while(true) {
            long elapsed=clock.getAsLong()-requestedNanos;
            if(elapsed<0 || elapsed>=2_000_000_000L)
                throw timeout("Native stop worker did not become idle within closure budget",expected,elapsed,observations);
            if(currentWorker.get()!=expected || !expected.isAlive())
                throw new AssertionError("Native stop worker identity changed or exited");
            Thread.State state=expected.getState();
            StackTraceElement[] stack=expected.getStackTrace();observations++;
            if(executorIdle(state,stack)) {
                if(currentWorker.get()!=expected || !expected.isAlive())
                    throw new AssertionError("Native stop worker identity changed or exited");
                elapsed=clock.getAsLong()-requestedNanos;
                if(elapsed<0 || elapsed>=2_000_000_000L)
                    throw timeout("Native stop idle observation exceeded closure budget",expected,elapsed,observations);
                return;
            }
            elapsed=clock.getAsLong()-requestedNanos;
            if(elapsed<0 || elapsed>=2_000_000_000L)
                throw timeout("Native stop worker did not become idle within closure budget",expected,elapsed,observations);
            sleeper.pause(Math.min(50_000_000L,2_000_000_000L-elapsed));
        }
    }

    private static AssertionError timeout(String reason,Thread worker,long elapsed,int observations) {
        try {
            return new AssertionError(reason+"; elapsedNanos="+elapsed+"; observations="+observations+
                "; threadState="+worker.getState().name()+"; frames="+safeFrames(worker.getStackTrace()));
        } catch(RuntimeException unavailable) {
            return new AssertionError(reason+"; elapsedNanos="+elapsed+"; observations="+observations+
                "; diagnostic=UNAVAILABLE");
        }
    }
    static java.util.List<String> safeFrames(StackTraceElement[] stack) {
        var known=java.util.Set.of(
            "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take",
            "java.util.concurrent.ThreadPoolExecutor.getTask",
            "java.util.concurrent.ThreadPoolExecutor.runWorker",
            "java.util.concurrent.ThreadPoolExecutor$Worker.run", "java.lang.Thread.run",
            "java.lang.Thread.sleep", "java.lang.Object.wait", "sun.misc.Unsafe.park",
            "jdk.internal.misc.Unsafe.park", "java.util.concurrent.locks.LockSupport.park",
            "java.util.concurrent.locks.LockSupport.parkNanos",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.await",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos",
            "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitUninterruptibly",
            "org.webrtc.ThreadUtils.awaitUninterruptibly", "org.webrtc.ThreadUtils.invokeAtFrontUninterruptibly",
            "app.umbra.media.NativeVoiceSession.tick", "app.umbra.media.NativeVoiceSession.releaseVideo",
            "app.umbra.media.NativeVoiceSession.invalidateVideo", "app.umbra.media.NativeVoiceSession.stopVideoLocally",
            "app.umbra.media.NativeVideoCapture.close", "app.umbra.calls.CallService.stopVideo",
            "app.umbra.calls.CallService$MediaLease.snapshot", "app.umbra.lab.SqliteDeviceRecords.transaction");
        var frames=new java.util.ArrayList<String>();
        for(int i=0;i<Math.min(24,stack.length);i++) {
            String name=stack[i].getClassName()+"."+stack[i].getMethodName();
            frames.add(known.contains(name)?name:"OTHER_FRAME");
        }
        return java.util.List.copyOf(frames);
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
