import com.google.gson.*;
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.cypher.*;
import java.nio.file.*;
import java.util.*;
public class AnnotationOracle {
 public static void main(String[]args)throws Exception {
  var constructor=AnnotationNode.class.getDeclaredConstructor(int.class,String.class,String.class,String.class,Map.class);constructor.setAccessible(true);
  var values=new LinkedHashMap<String,Object>();values.put("caller_class","Example");values.put("callee_name","callee");
  var builder=new DefaultGraph.Builder();builder.addNode((Node)constructor.newInstance(101,"Audit","Owner","member",values));var graph=builder.build();
  var output=new ArrayList<Object>();
  for(String query:List.of("MATCH (n) WHERE n.caller_class CONTAINS 'Exam' RETURN id(n) AS id,n.caller_class AS caller", "MATCH (n) WHERE toLower(toString(coalesce(n.caller_class,''))) CONTAINS 'exam' RETURN id(n) AS id,n.caller_class AS caller"))for(boolean cross:List.of(false,true)){
   var item=new LinkedHashMap<String,Object>();item.put("query",query);item.put("cross",cross);
   try{var result=cross?new CrossGraphCypherExecutor(List.of(new CypherGraph("a",graph),new CypherGraph("b",graph))).execute(query,Map.of()):new CypherExecutor(graph).execute(query,Map.of());item.put("columns",result.getColumns());item.put("rows",result.getRows());}catch(Throwable error){item.put("error",error.getClass().getSimpleName());item.put("message",error.getMessage());}output.add(item);
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(output)+"\n");
 }
}
