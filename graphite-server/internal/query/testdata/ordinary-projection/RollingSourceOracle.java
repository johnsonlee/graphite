import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.file.*;import java.util.*;
public class RollingSourceOracle{
 public static void main(String[]args)throws Exception{
  var json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var all=new ArrayList<Object>();
  for(int count:List.of(8,40))for(String scenario:List.of("early-match-later-error","error-before-match","window-match-later-error","all-miss"))for(String projection:List.of("n.caller_name","substring(n.caller_name,0)")){
   var sources=new ArrayList<CypherGraph>();var fixtures=new ArrayList<String>();
   for(int i=0;i<count;i++){String fixture="nohit";if(scenario.equals("early-match-later-error")){if(i==1)fixture="clean";if(i==2)fixture="bad";}if(scenario.equals("error-before-match")){if(i==1)fixture="bad";if(i==2)fixture="clean";}if(scenario.equals("window-match-later-error")){if(i==6)fixture="clean";if(i==7)fixture="bad";}fixtures.add(fixture);sources.add(new CypherGraph("g"+i,GraphStore.INSTANCE.loadMapped(Path.of(args[0],fixture))));}
   var item=new LinkedHashMap<String,Object>();item.put("count",count);item.put("scenario",scenario);item.put("projection",projection);item.put("fixtures",fixtures);var targets=new ArrayList<Object>();
   String query="MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN "+projection+" AS x LIMIT 1";
   for(int repeat=0;repeat<3;repeat++)targets.add(HistoryOracle.run(sources,true,query));item.put("targets",targets);all.add(item);
   for(var source:sources)((java.io.Closeable)source.getGraph()).close();
  }
  Files.writeString(Path.of(args[1]),json.toJson(all)+"\n");
 }
}
