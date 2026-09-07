import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
public class WideHistoryOracle {
 public static void main(String[]args)throws Exception {
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var all=new ArrayList<Object>();
  for(String scenario:List.of("bad-first","bad-last","short-bad-first","route-bad-first"))for(String warm:List.of("none","distinct","zero-distinct")){
   var sources=new ArrayList<CypherGraph>();for(int i=0;i<40;i++){boolean bad=scenario.equals("bad-last")?i==39:i==0;sources.add(new CypherGraph("g"+i,GraphStore.INSTANCE.loadMapped(Path.of(args[0],bad?"bad-matched":"clean"))));}
   var item=new LinkedHashMap<String,Object>();item.put("scenario",scenario);item.put("warm",warm);item.put("initial",HistoryOracle.state(sources));
   String term=scenario.startsWith("short")?"oth":"other",prefix="MATCH (n) WHERE n.caller_name CONTAINS '"+term+"'",query=prefix+(scenario.startsWith("route")?" AND n.graphId='g0'":"")+" RETURN n.caller_name AS x LIMIT 1";
   if(!warm.equals("none")){String warmQuery=prefix.replace("'"+term+"'",warm.equals("zero-distinct")?"'absent'":"'"+term+"'")+" RETURN DISTINCT n.caller_name AS x LIMIT 1";item.put("warmResult",HistoryOracle.run(sources,true,warmQuery));}
   var targets=new ArrayList<Object>();for(int repeat=0;repeat<2;repeat++)targets.add(HistoryOracle.run(sources,true,query));item.put("targets",targets);all.add(item);
   for(var source:sources)((java.io.Closeable)source.getGraph()).close();
  }
  Files.writeString(Path.of(args[1]),json.toJson(all)+"\n");
 }
}
