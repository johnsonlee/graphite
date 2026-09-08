import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;

public final class AnnotationMappedOracle {
 public static void main(String[]args)throws Throwable {
  System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
  var sources=new ArrayList<CypherGraph>();var output=new ArrayList<Object>();Path root=Path.of(args[0]);
  String zero="MATCH (n) WHERE n.caller_name CONTAINS 'NeverPresentStringRangeControl' RETURN n.caller_name AS x LIMIT 1";
  String mixed="MATCH (n) WHERE n.caller_name CONTAINS 'NeverPresentStringRangeControl' OR n.name CONTAINS 'Audit' RETURN DISTINCT n.caller_class AS x, n.caller_name AS y LIMIT 1";
  try {
   for(int i=0;i<64;i++)sources.add(new CypherGraph(String.format("g%02d",i),GraphStore.INSTANCE.loadMapped(root.resolve(String.format("g%02d",i)))));
   for(String query:List.of(zero,mixed,mixed)) {
    var step=new LinkedHashMap<String,Object>();step.put("query",query);
    for(String when:List.of("before","after")) {
     if(when.equals("after"))try {
      var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
      var result=new CrossGraphCypherExecutor(sources,context,false).execute(query,Map.of());
      step.put("columns",result.getColumns());step.put("rows",result.getRows());
     }catch(Throwable error){IndexLifecycleOracle.error(step,error);}
     var states=new ArrayList<Object>();
     for(int i:new int[]{0,63})states.add(IndexLifecycleOracle.state(sources.get(i).getGraph(),root.resolve(String.format("g%02d",i))));
     step.put(when,states);
    }
    output.add(step);
   }
  }finally {for(var source:sources)((java.io.Closeable)source.getGraph()).close();}
  Files.writeString(Path.of(args[1]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(output)+"\n",StandardOpenOption.CREATE_NEW);
 }
}
