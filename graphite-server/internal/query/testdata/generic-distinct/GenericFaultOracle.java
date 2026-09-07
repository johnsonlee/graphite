import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.nio.file.*;
import java.util.*;
public class GenericFaultOracle {
 static void emit(Map<String,Object> out){String json=new GsonBuilder().serializeNulls().disableHtmlEscaping().serializeSpecialFloatingPointValues().create().toJson(out);StringBuilder text=new StringBuilder();for(char c:json.toCharArray()){if(c>127)text.append(String.format("\\u%04x",(int)c));else text.append(c);}System.out.println(text);}
 public static void main(String[] args)throws Exception{
  List<String> queries=new Gson().fromJson(Files.readString(Path.of(args[1])),List.class);
  for(String mode:List.of("MAPPED")){
   Graph graph=GraphStore.INSTANCE.load(Path.of(args[0]),GraphStore.LoadMode.valueOf(mode));
   for(int count:List.of(1,2,9,40))for(String q:queries){
    Map<String,Object> out=new LinkedHashMap<>();out.put("mode",mode);out.put("sources",count);out.put("query",q);
    try{CypherResult r;if(count==1)r=new CypherExecutor(graph).execute(q);else{List<CypherGraph> gs=new ArrayList<>();for(int i=0;i<count;i++)gs.add(new CypherGraph(i==0?"z-first":String.format("a-%02d",i),graph));r=new CrossGraphCypherExecutor(gs).execute(q);}out.put("columns",r.getColumns());out.put("rows",r.getRows());}catch(Throwable t){out.put("error",t.getClass().getSimpleName());out.put("message",t.getMessage());}
    emit(out);
   }
   if(graph instanceof AutoCloseable)((AutoCloseable)graph).close();
  }
 }
}
