package app.umbra.media;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Test APK only. A FIFO marker on the actual worker, not a global executor-idle wait.
 * No field names/keep rules, authorization changes, interrupts or executor shutdowns. */
final class NativeVideoStopFence {
    private NativeVideoStopFence() {}
    static long await(Object session,Class<?> sessionType,Thread expected,Supplier<Thread> currentWorker,
                      long requestedNanos,long deadlineNanos,LongSupplier clock) throws Exception {
        if(session==null || session.getClass()!=sessionType || expected==null || expected==Thread.currentThread()
                || currentWorker.get()!=expected || !expected.isAlive() || requestedNanos<=0 || deadlineNanos<=requestedNanos)
            throw new AssertionError("Invalid synthetic native stop fence");
        var executors=Collections.newSetFromMap(new IdentityHashMap<ExecutorService,Boolean>());
        for(var field:sessionType.getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers()) || !ExecutorService.class.isAssignableFrom(field.getType()))continue;
            field.setAccessible(true); // App-owned test observation; access failure propagates.
            Object value=field.get(session);
            if(value instanceof ExecutorService executor)executors.add(executor);
        }
        // NativeVoiceSession owns exactly the worker and watchdog; never traverse an object graph.
        if(executors.size()!=2)throw new AssertionError("Unexpected synthetic executor inventory");
        var reached=new CountDownLatch(1);var matched=new AtomicReference<ExecutorService>();
        var futures=new ArrayList<Future<?>>();
        try {
            for(var executor:executors)futures.add(executor.submit(()->{
                if(Thread.currentThread()==expected) {matched.set(executor);reached.countDown();}
            }));
            long remaining=deadlineNanos-clock.getAsLong();
            if(remaining<=0 || !reached.await(remaining,TimeUnit.NANOSECONDS))
                throw new AssertionError("Native stop FIFO fence exceeded scenario deadline");
            long observed=clock.getAsLong();
            if(observed<requestedNanos || observed>=deadlineNanos || matched.get()==null
                    || currentWorker.get()!=expected || !expected.isAlive())
                throw new AssertionError("Native stop FIFO fence invalidated");
            return observed-requestedNanos;
        } finally {
            for(var future:futures)future.cancel(false); // Only these lab markers, never native work.
        }
    }
}
