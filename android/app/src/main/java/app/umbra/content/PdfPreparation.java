package app.umbra.content;

import android.content.*;
import android.os.*;
import app.umbra.core.EmergencyLock;
import app.umbra.crypto.Engine;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One bounded, explicitly authorized parser process at a time; no vault lock across IPC. */
final class PdfPreparation {
    private static final Semaphore SLOT=new Semaphore(1);
    private PdfPreparation() {}
    static RestrictedContentService.Prepared prepare(Context context,Engine engine,
            RestrictedContentService.Review review,byte[] input,boolean confirmed)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("PDF preparation requires worker");
        var lease=engine.restricted().preparationAuthorization(review,confirmed);
        Runnable check=()->{try{lease.run();}catch(RuntimeException denied){throw denied;}catch(Exception denied){throw new ContentException(ContentException.Code.CONSENT_REQUIRED);}};
        check.run();if(!SLOT.tryAcquire())throw new ContentException(ContentException.Code.BUSY);
        Job job=null;
        try {
            job=new Job(context.getApplicationContext(),check);
            final Job owned=job;
            EmergencyLock.Registration registration=null;
            try {
                registration=engine.emergency().register(EmergencyLock.Subsystem.DOCUMENTS,()->{owned.cancel();return owned.closed;});
                job.start(input);
                byte[] pages=job.execute();
                try {check.run();DocumentPages.parse(pages);return engine.restricted().retainPrepared(review,new RestrictedContentService.Prepared(RestrictedPayload.Format.PDF_PAGES,pages,check));}
                catch(Exception | Error failure){Arrays.fill(pages,(byte)0);throw failure;}
            } finally {
                try {job.close();if(registration!=null)registration.close();}
                catch(Exception cleanupFailure){engine.emergencyLock();throw cleanupFailure;}
            }
        } finally {
            if(job==null || (job.closed.isDone() && !job.closed.isCompletedExceptionally()))SLOT.release();
        }
    }
    private static final class Job implements AutoCloseable {
        final Context context;final Runnable authorization;
        final AtomicBoolean cancelled=new AtomicBoolean();
        final CompletableFuture<Void> closed=new CompletableFuture<>(),dead=new CompletableFuture<>();
        final CompletableFuture<byte[]> result=new CompletableFuture<>();
        final AtomicReference<Messenger> endpoint=new AtomicReference<>();
        final HandlerThread callbacks=new HandlerThread("umbra-document-result");
        MemoryDocumentDescriptor source;
        Handler handler;Messenger receiver;
        boolean claimed;
        volatile boolean bound;
        final AtomicBoolean connected=new AtomicBoolean();
        final java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Void>> deaths=new java.util.concurrent.ConcurrentLinkedQueue<>();
        final ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder binder) {
                boolean first=connected.compareAndSet(false,true);
                CompletableFuture<Void> death=first?dead:new CompletableFuture<>();deaths.add(death);
                try {
                    binder.linkToDeath(()->death.complete(null),0);
                    Messenger remote=new Messenger(binder);
                    // BIND_AUTO_CREATE can recreate an idle service after its intentional
                    // exit. Never send the document twice or reuse a prior native generation.
                    if(!first){remote.send(Message.obtain(null,RestrictedPdfService.CANCEL));return;}
                    endpoint.set(remote);
                    if(cancelled.get()){cancel();return;}
                    authorization.run();
                    Message request=Message.obtain(null,RestrictedPdfService.RENDER);request.replyTo=receiver;
                    Bundle data=new Bundle();data.putParcelable("input",source.descriptor);request.setData(data);
                    endpoint.get().send(request);
                }catch(Exception failure){if(!binder.isBinderAlive())death.complete(null);result.completeExceptionally(RestrictedPayload.invalid());cancel();}
            }
            @Override public void onServiceDisconnected(ComponentName name){/* Actual Binder death confirms closure. */}
            @Override public void onBindingDied(ComponentName name){cancel();}
            @Override public void onNullBinding(ComponentName name){dead.complete(null);result.completeExceptionally(RestrictedPayload.invalid());}
        };
        Job(Context context,Runnable authorization) {
            this.context=context;this.authorization=authorization;
        }
        void start(byte[] input)throws Exception {
            authorization.run();if(cancelled.get())throw RestrictedPayload.invalid();
            source=new MemoryDocumentDescriptor(input,authorization);source.open(context);
            if(cancelled.get())throw RestrictedPayload.invalid();callbacks.start();
            handler=new Handler(callbacks.getLooper(),message->{
                byte[] pages=null;
                try {
                    if(message.what!=RestrictedPdfService.RESULT || message.arg1!=1 || message.arg2!=1 || cancelled.get() ||
                            message.sendingUid==android.os.Process.myUid() ||
                            (Build.VERSION.SDK_INT>=34 && !android.os.Process.isIsolatedUid(message.sendingUid)))throw RestrictedPayload.invalid();
                    Bundle data=message.getData();if(!data.keySet().equals(Set.of("pages")))throw RestrictedPayload.invalid();
                    pages=data.getByteArray("pages");DocumentPages.parse(pages);authorization.run();
                    if(!result.complete(pages)){Arrays.fill(pages,(byte)0);}return true;
                }catch(Exception failure){if(pages!=null)Arrays.fill(pages,(byte)0);result.completeExceptionally(RestrictedPayload.invalid());return true;}
            });
            receiver=new Messenger(handler);
        }
        byte[] execute()throws Exception {
            authorization.run();
            bound=context.bindIsolatedService(new Intent(context,RestrictedPdfService.class),Context.BIND_AUTO_CREATE,
                java.util.UUID.randomUUID().toString().replace("-",""),task->handler.post(task),connection);
            if(!bound)throw RestrictedPayload.invalid();
            long started=System.nanoTime();
            while(!result.isDone() || !dead.isDone()) {
                authorization.run();
                if(cancelled.get() || System.nanoTime()-started>12_000_000_000L)throw RestrictedPayload.invalid();
                Thread.sleep(10);
            }
            try {byte[] value=result.get();claimed=true;return value;}catch(ExecutionException invalid){throw RestrictedPayload.invalid();}
        }
        void cancel() {
            cancelled.set(true);if(source!=null)source.invalidate();Messenger remote=endpoint.get();
            if(remote!=null)try{remote.send(Message.obtain(null,RestrictedPdfService.CANCEL));}
                catch(RemoteException gone){/* Binder death, not send failure, confirms process closure. */}
        }
        @Override public void close()throws Exception {
            cancel();boolean failed=false;
            if(bound) {
                // Stop automatic rebinding before waiting for parser deaths.
                try{context.unbindService(connection);}catch(RuntimeException failure){failed=true;}
                var drained=new CompletableFuture<Void>();
                if(!handler.post(()->drained.complete(null)))failed=true;
                try{drained.get(1,TimeUnit.SECONDS);}catch(Exception unconfirmed){failed=true;}
                long deadline=System.nanoTime()+2_000_000_000L;
                if(deaths.isEmpty() && !dead.isDone())failed=true;
                for(var death:deaths)try{death.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
                    catch(Exception unconfirmed){failed=true;}
            }
            try{if(source!=null)source.close();}catch(Exception failure){failed=true;}
            callbacks.quitSafely();
            try{callbacks.join(2000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed=true;}
            if(callbacks.isAlive())failed=true;
            // A result not returned to Prepared must not survive a failed/expired operation.
            if((failed || !claimed) && result.isDone() && !result.isCompletedExceptionally()) {
                byte[] abandoned=result.getNow(null);if(abandoned!=null)Arrays.fill(abandoned,(byte)0);
            }
            if(failed){closed.completeExceptionally(new IllegalStateException("PDF closure unconfirmed"));throw new IllegalStateException("PDF closure unconfirmed");}
            closed.complete(null);
        }
    }
}
