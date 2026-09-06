import com.google.gson.*;
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import io.johnsonlee.graphite.cli.ExploreRoutes;
import java.nio.file.*;
import java.util.*;

/** No HTTP or performance measurement. Records full scoped/cross query output. */
public class EncounterOracle {
 static final List<Class<? extends Node>> TYPES=List.of(IntConstant.class,StringConstant.class,LongConstant.class,FloatConstant.class,DoubleConstant.class,BooleanConstant.class,NullConstant.class,EnumConstant.class,LocalVariable.class,FieldNode.class,ParameterNode.class,ReturnNode.class,CallSiteNode.class,AnnotationNode.class,ResourceValueNode.class,ResourceFileNode.class);
 static int id(Node node)throws Exception{return (Integer)node.getClass().getMethod("getId-4bBHiqc").invoke(node);}
 static List<Integer> ids(Graph g,Class<? extends Node> type)throws Exception {List<Integer> result=new ArrayList<>();for(Node n:kotlin.sequences.SequencesKt.toList(g.nodes(type)))result.add(id(n));return result;}
 public static void main(String[] args)throws Exception {
  String mode=args[1],prehash=args[2];
  // Allocating identities for Class objects earlier changes only HashMap key
  // hashing, without changing the persisted graph or any query expression.
  if(!prehash.equals("none")){List<Class<? extends Node>> types=new ArrayList<>(TYPES);if(prehash.equals("reverse"))Collections.reverse(types);for(Class<?> type:types)System.identityHashCode(type);}
  Path fixture=Path.of(args[0]);GraphStore.INSTANCE.ensureNodeIndex(fixture);
  Graph graph=GraphStore.INSTANCE.load(fixture,GraphStore.LoadMode.valueOf(mode));
  Map<String,Object> out=new LinkedHashMap<>();out.put("mode",mode);out.put("prehash",prehash);
  Map<String,Object> sources=new LinkedHashMap<>();sources.put("Node",ids(graph,Node.class));sources.put("ConstantNode",ids(graph,ConstantNode.class));for(Class<? extends Node> type:TYPES)sources.put(type.getSimpleName(),ids(graph,type));out.put("sources",sources);
  Map<String,Object> hashes=new LinkedHashMap<>();for(Class<?> type:TYPES)hashes.put(type.getSimpleName(),System.identityHashCode(type));out.put("classIdentityHashes",hashes);
  List<Object> observations=new ArrayList<>();
  String[] queries={"MATCH (n) RETURN id(n) AS id", "MATCH (n) RETURN id(n) AS id LIMIT 5", "MATCH (n) WHERE id(n)>=0 RETURN id(n) AS id", "MATCH (n:Node) RETURN id(n) AS id", "MATCH (n:Constant) RETURN id(n) AS id", "MATCH (n:IntConstant) RETURN id(n) AS id", "MATCH (n:StringConstant) RETURN id(n) AS id", "MATCH (n:CallSiteNode) RETURN id(n) AS id", "UNWIND [1,2] AS x MATCH (n) RETURN x,id(n) AS id", "OPTIONAL MATCH (n) RETURN id(n) AS id", "MATCH (n) WITH collect(id(n)) AS ids UNWIND ids AS id RETURN id", "MATCH (n) RETURN id(n) AS id ORDER BY id", "MATCH (n) WHERE n.callee_name='run' RETURN id(n) AS id", "MATCH (n:CallSiteNode) WHERE n.callee_name='run' RETURN id(n) AS id"};
  for(String query:queries)for(boolean cross:List.of(false,true)){
   Map<String,Object> row=new LinkedHashMap<>();row.put("query",query);row.put("cross",cross);
   try {CypherResult r=cross?new CrossGraphCypherExecutor(List.of(new CypherGraph("b",graph),new CypherGraph("a",graph))).execute(query):new CypherExecutor(graph).execute(query);row.put("columns",r.getColumns());row.put("rows",r.getRows());}catch(Throwable e){row.put("error",e.getClass().getSimpleName());row.put("message",e.getMessage());}observations.add(row);
  }
  out.put("queries",observations);
  Object rest=ExploreRoutes.class.getMethod("buildSubgraph-yNKzG_Y$explore",Graph.class,int.class,int.class).invoke(new ExploreRoutes(),graph,Integer.parseInt(args[3]),2);
  out.put("restSubgraph",rest);
  Files.writeString(Path.of(args[4]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(out)+"\n");
  if(graph instanceof java.io.Closeable)((java.io.Closeable)graph).close();
 }
}
