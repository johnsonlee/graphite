import io.johnsonlee.graphite.webgraph.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.core.CallSiteNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Development-only deterministic interleaving, using the unmodified pinned jar. */
public class IndexLifecycleProbe {
 public static void main(String[] args)throws Exception {
  Path fixture=Path.of(args[0]); GraphStore.INSTANCE.ensureNodeIndex(fixture);
  MappedWebGraphBackedGraph seed=(MappedWebGraphBackedGraph)GraphStore.INSTANCE.loadMapped(fixture);
  boolean prepared=seed.prepareCallSiteStringIndex$webgraph(()->{});
  System.out.println("prepared="+prepared);
  if (!prepared) {System.out.println("budgetDeniedNoIndex="+!seed.isCallSiteStringIndexInitialized$webgraph());seed.close();return;}
  if(!seed.persistPreparedCallSiteStringIndex$webgraph())throw new AssertionError("sidecar not persisted");seed.close();
  MappedWebGraphBackedGraph graph=(MappedWebGraphBackedGraph)GraphStore.INSTANCE.loadMapped(fixture);
  graph.prepareCallSiteStringIndex$webgraph(()->{});
  System.out.println("loadedFromPersistence="+graph.isCallSiteStringIndexLoadedFromPersistence$webgraph());
  CountDownLatch borrowed=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicBoolean stopped=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();
  GraphWorkConsumer work=()->{
   boolean candidate=Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(f->f.getClassName().equals("io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndex")&&f.getMethodName().equals("candidateStringIds"));
   if(candidate&&stopped.compareAndSet(false,true)) {borrowed.countDown();try {if(!resume.await(5,TimeUnit.SECONDS))throw new AssertionError("resume timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}
  };
  Thread borrower=new Thread(()->{try {
   System.out.println("aggregate="+graph.aggregateStringPropertyDisjunction(CallSiteNode.class,List.of(new StringPropertyPredicate("callee_name",null,StringMatchMode.CONTAINS,"cal")),null,work));
  }catch(Throwable e){failure.set(e);}},"borrower");
  borrower.start();
  if(!borrowed.await(5,TimeUnit.SECONDS)){resume.countDown();borrower.join();graph.close();throw new AssertionError("no in-use index callback");}
  System.out.println("borrowerPausedInsideIndex=true");
  graph.releaseStringPropertyDisjunctionCache();
  System.out.println("indexClosedWhileBorrowed="+!graph.isCallSiteStringIndexInitialized$webgraph());
  resume.countDown();borrower.join(5000);if(borrower.isAlive())throw new AssertionError("borrower did not finish");
  Throwable error=failure.get();if(error==null)throw new AssertionError("race failed to reproduce");
  System.out.println("borrowerFailure="+error);error.printStackTrace(System.out);
  if(!"Cannot grow a closed mapped CallSite string-index reservation".equals(error.getMessage()))throw new AssertionError(error);
  graph.close();
 }
}
