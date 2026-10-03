"""Controlled executor ordering only; historical native interleaving remains inferred."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'android/app/src/androidTestConnected/java/app/umbra/media'

PRELUDE = '''package app.umbra.media;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class Check {
 static void check(boolean value) {if(!value)throw new AssertionError("Harness assertion");}
 static void reject(Runnable action) {try {action.run();}catch(AssertionError expected){return;}throw new AssertionError("Accepted invalid worker");}
 static StackTraceElement f(String owner,String method) {return new StackTraceElement(owner,method,"Synthetic.java",1);}
 public static void main(String[] args) throws Exception {
'''
RACE = '''
 var executor=new ScheduledThreadPoolExecutor(1);
 executor.schedule(()->{},1,TimeUnit.DAYS);
 var captured=new CountDownLatch(1);var release=new CountDownLatch(1);var emitted=new CountDownLatch(1);
 var known=new AtomicReference<Thread>();var confirmed=new AtomicBoolean(true);
 var ids=new CopyOnWriteArrayList<String>();
 executor.execute(()->{
  known.set(Thread.currentThread());boolean retained=confirmed.get();captured.countDown();
  try {check(release.await(2,TimeUnit.SECONDS));if(retained)ids.add("async-stop");}
  catch(InterruptedException e){throw new AssertionError(e);}finally{emitted.countDown();}
 });
 check(captured.await(2,TimeUnit.SECONDS));
 confirmed.set(false);ids.add("foreground-stop");
'''


class NativeVideoStopQuiescenceTest(unittest.TestCase):
    def run_java(self, body, *, helper=True):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'Check.java';source.write_text(PRELUDE+body+'\n}}')
            sources=[str(SOURCE/'VideoStopDeliveryGate.java')]
            if helper:sources.append(str(SOURCE/'NativeVideoStopQuiescence.java'))
            subprocess.run(['javac','-d',folder,*sources,str(source)],check=True,capture_output=True)
            return subprocess.run(['java','-cp',folder,'app.umbra.media.Check'],capture_output=True,text=True,timeout=10)

    def test_controlled_red_snapshot_before_async_stop_loses_inventory(self):
        result=self.run_java(RACE+'''
 try {
  var gate=new VideoStopDeliveryGate("call",2,"nonce");gate.issued(ids);
  release.countDown();check(emitted.await(2,TimeUnit.SECONDS));
  gate.requireAnnounced("VIDEO_STOP","call",2,"async-stop");
 } finally {release.countDown();executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);}
''',helper=False)
        self.assertNotEqual(0,result.returncode)
        self.assertIn('Synthetic stop inventory changed after issued receipt',result.stderr)

    def test_idle_fence_waits_for_retained_snapshot_emission_then_inventories_all(self):
        result=self.run_java(RACE+'''
 try {
  long requested=System.nanoTime();int[] pauses={0};
  NativeVideoStopQuiescence.await(known.get(),known::get,requested,System::nanoTime,nanos->{
   pauses[0]++;release.countDown();check(emitted.await(1,TimeUnit.SECONDS));TimeUnit.NANOSECONDS.sleep(nanos);
  });
  check(pauses[0]>0 && ids.equals(List.of("foreground-stop","async-stop")));
  var gate=new VideoStopDeliveryGate("call",2,"nonce");gate.issued(ids);
  for(String id:ids)gate.requireAnnounced("VIDEO_STOP","call",2,id);
  check(gate.ownStopCount()==2);
 } finally {release.countDown();executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);}
''')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_wrong_or_dead_worker_fails_without_sleep(self):
        result=self.run_java('''
 var dead=new Thread(()->{});dead.start();dead.join();
 for(Thread worker:new Thread[]{null,dead,Thread.currentThread()}) {
  reject(()->{try {NativeVideoStopQuiescence.await(worker,()->dead,1,()->2,
   nanos->{throw new AssertionError("Unexpected sleep");});}catch(InterruptedException e){throw new AssertionError(e);}});
 }
''')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_busy_worker_expires_at_original_request_budget_without_extension(self):
        result=self.run_java(RACE+'''
 try {
  long[] clock={1_000_000_001L};int[] pauses={0};
  try {
   NativeVideoStopQuiescence.await(known.get(),known::get,1L,()->clock[0],nanos->{
    check(nanos==50_000_000L);clock[0]+=nanos;pauses[0]++;
   });
   throw new AssertionError("Busy worker accepted");
  } catch(AssertionError expected) {check(expected.getMessage().contains("closure budget"));}
  check(clock[0]==2_000_000_001L && pauses[0]==20 && ids.equals(List.of("foreground-stop")));
 } finally {release.countDown();executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS);}
''')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_only_actual_executor_queue_stack_qualifies_not_http_or_nested_queue(self):
        result=self.run_java('''
 var idle=new StackTraceElement[]{f("java.util.concurrent.locks.LockSupport","parkNanos"),
  f("java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue","take"),
  f("java.util.concurrent.ThreadPoolExecutor","getTask"),
  f("java.util.concurrent.ThreadPoolExecutor","runWorker"),
  f("java.util.concurrent.ThreadPoolExecutor$Worker","run"),f("java.lang.Thread","run")};
 check(NativeVideoStopQuiescence.executorIdle(Thread.State.TIMED_WAITING,idle));
 check(NativeVideoStopQuiescence.executorIdle(Thread.State.WAITING,idle));
 check(!NativeVideoStopQuiescence.executorIdle(Thread.State.RUNNABLE,idle));
 var http=idle.clone();http[1]=f("com.android.okhttp.HttpEngine","readResponse");
 check(!NativeVideoStopQuiescence.executorIdle(Thread.State.WAITING,http));
 var nested=idle.clone();nested[2]=f("app.synthetic.Task","run");
 check(!NativeVideoStopQuiescence.executorIdle(Thread.State.WAITING,nested));
 check(!NativeVideoStopQuiescence.executorIdle(Thread.State.TERMINATED,idle));
''')
        self.assertEqual(0,result.returncode,result.stderr)


if __name__=='__main__':unittest.main()
