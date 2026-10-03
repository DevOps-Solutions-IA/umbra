"""Real scheduler ordering tests; not Android/R8 execution or historical attribution."""
from pathlib import Path
import subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'android/app/src/androidTestConnected/java/app/umbra/media/NativeVideoStopFence.java'
PRELUDE='''package app.umbra.media;
import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class Check {
 static class Session {
  final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1);
  final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
  final AtomicReference<Thread> owner=new AtomicReference<>();
  Session()throws Exception {worker.submit(()->owner.set(Thread.currentThread())).get(1,TimeUnit.SECONDS);}
  void close()throws Exception {worker.shutdownNow();watchdog.shutdownNow();if(!worker.awaitTermination(2,TimeUnit.SECONDS)||!watchdog.awaitTermination(2,TimeUnit.SECONDS))throw new AssertionError("Leak");}
 }
 static void check(boolean v){if(!v)throw new AssertionError("Assertion");}
 public static void main(String[] args)throws Exception {
'''
class NativeVideoStopFenceTest(unittest.TestCase):
 def run_java(self,body):
  with tempfile.TemporaryDirectory() as folder:
   p=Path(folder)/'Check.java';p.write_text(PRELUDE+body+'\n}}')
   subprocess.run(['javac','-d',folder,str(SOURCE),str(p)],check=True,capture_output=True)
   result=subprocess.run(['java','-cp',folder,'app.umbra.media.Check'],capture_output=True,text=True,timeout=10)
   self.assertEqual(0,result.returncode,result.stderr)
 def test_fifo_completes_prior_producer_without_requiring_future_work_idle(self):
  self.run_java('''
 var s=new Session();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
 var staleCompleted=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
 var laterEntered=new CountDownLatch(1);var laterRelease=new CountDownLatch(1);
 try {
  s.worker.submit(()->{entered.countDown();try{release.await();staleCompleted.set(true);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  check(entered.await(1,TimeUnit.SECONDS));long request=System.nanoTime();
  var waiter=new Thread(()->{try{NativeVideoStopFence.await(s,Session.class,s.owner.get(),s.owner::get,request,request+2_000_000_000L,System::nanoTime);}catch(Throwable error){failure.set(error);}});
  waiter.start();long until=System.nanoTime()+1_000_000_000L;
  while(s.worker.getQueue().isEmpty() && System.nanoTime()<until)Thread.sleep(1);
  check(!s.worker.getQueue().isEmpty());
  s.worker.submit(()->{laterEntered.countDown();try{laterRelease.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  release.countDown();check(laterEntered.await(1,TimeUnit.SECONDS));waiter.join(1000);
  check(!waiter.isAlive() && failure.get()==null && staleCompleted.get());
 }finally{release.countDown();laterRelease.countDown();s.close();}
''')
 def test_watchdog_marker_cannot_satisfy_blocked_worker(self):
  self.run_java('''
 var s=new Session();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
 try {
  s.worker.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
  check(entered.await(1,TimeUnit.SECONDS));long request=System.nanoTime();
  try{NativeVideoStopFence.await(s,Session.class,s.owner.get(),s.owner::get,request,request+100_000_000L,System::nanoTime);throw new AssertionError("False success");}
  catch(AssertionError expected){check(expected.getMessage().contains("exceeded scenario deadline"));}
  check(!s.worker.isShutdown() && !s.watchdog.isShutdown());
 }finally{release.countDown();s.close();}
''')
 def test_unknown_owner_and_wrong_thread_are_rejected(self):
  self.run_java('''
 var s=new Session();try {
  long request=System.nanoTime();
  for(boolean wrongClass:new boolean[]{true,false}) {
   try{NativeVideoStopFence.await(s,wrongClass?Check.class:Session.class,Thread.currentThread(),s.owner::get,request,request+1_000_000_000L,System::nanoTime);throw new AssertionError("Accepted invalid owner");}
   catch(AssertionError expected){check(expected.getMessage().equals("Invalid synthetic native stop fence"));}
  }
 }finally{s.close();}
''')

 def test_stale_thread_reference_and_exact_deadline_reject(self):
  self.run_java(''' 
 var s=new Session();try {
  long request=System.nanoTime();var reads=new AtomicInteger();
  try{NativeVideoStopFence.await(s,Session.class,s.owner.get(),()->reads.incrementAndGet()==1?s.owner.get():Thread.currentThread(),request,request+1_000_000_000L,System::nanoTime);throw new AssertionError("Stale worker accepted");}
  catch(AssertionError expected){check(expected.getMessage().equals("Native stop FIFO fence invalidated"));}
  var clockReads=new AtomicInteger();
  try{NativeVideoStopFence.await(s,Session.class,s.owner.get(),s.owner::get,100,2_000_000_100L,()->clockReads.incrementAndGet()==1?150:2_000_000_100L);throw new AssertionError("Deadline accepted");}
  catch(AssertionError expected){check(expected.getMessage().equals("Native stop FIFO fence invalidated"));}
 }finally{s.close();}
''')
 def test_missing_executor_inventory_and_static_executor_reject(self):
  self.run_java('''
 var s=new Session();try {
  class Invalid {final ExecutorService one=s.worker;}
  var bad=new Invalid();long request=System.nanoTime();
  try{NativeVideoStopFence.await(bad,Invalid.class,s.owner.get(),s.owner::get,request,request+1_000_000_000L,System::nanoTime);throw new AssertionError("Missing inventory accepted");}
  catch(AssertionError expected){check(expected.getMessage().equals("Unexpected synthetic executor inventory"));}
 }finally{s.close();}
''')
