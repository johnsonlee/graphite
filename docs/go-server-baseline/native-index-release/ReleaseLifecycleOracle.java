import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;

/** Real main operations; tiny fixtures establish correctness only. */
public final class ReleaseLifecycleOracle {
 public static void main(String[] args)throws Throwable {
  System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad", "lazy");
  var all=new ArrayList<Object>();
  for(String scenario:List.of("persisted-scoped", "persisted-prepared", "built-prepared")) {
   Path dir=Path.of(args[0],scenario);
   Graph graph=GraphStore.INSTANCE.loadMapped(dir);
   var record=new LinkedHashMap<String,Object>();record.put("scenario",scenario);
   record.put("loaded",IndexLifecycleOracle.state(graph,dir));
   var steps=new ArrayList<Object>();record.put("steps",steps);
   try {
    for(String action:List.of(scenario.equals("persisted-scoped")?"query":"prepare",
                              "release", "release", "clear", "prepare", "release", "query")) {
     var step=new LinkedHashMap<String,Object>();step.put("action",action);
     step.put("before",IndexLifecycleOracle.state(graph,dir));
     try {
      switch(action) {
       case "prepare": step.put("prepared",IndexLifecycleOracle.prepare(graph));break;
       case "release": IndexLifecycleOracle.invoke(graph,"releaseStringPropertyDisjunctionCache");break;
       case "clear": IndexLifecycleOracle.invoke(graph,"clearStringPropertyIndexes");break;
       case "query":
        var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
        var result=new CrossGraphCypherExecutor(List.of(new CypherGraph("g",graph)),context,true)
          .execute("MATCH (n) WHERE n.caller_name CONTAINS $term RETURN n.caller_name AS x LIMIT 1",Map.of("term","other"));
        step.put("columns",result.getColumns());step.put("rows",result.getRows());break;
       default: throw new IllegalArgumentException(action);
      }
     }catch(Throwable e){IndexLifecycleOracle.error(step,e);}
     step.put("after",IndexLifecycleOracle.state(graph,dir));steps.add(step);
    }
   }finally {((java.io.Closeable)graph).close();}
   record.put("finalIndexFile",IndexLifecycleOracle.indexFile(dir));all.add(record);
  }
  Files.writeString(Path.of(args[1]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(all)+"\n",StandardOpenOption.CREATE_NEW);
 }
}
