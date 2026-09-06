import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;
import it.unimi.dsi.util.FrontCodedStringList;
import it.unimi.dsi.webgraph.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Tiny persisted correctness fixture/oracle. No benchmark or HTTP activity. */
public class TraversalOracle {
 static class TinyGraph extends ImmutableGraph {
  final int[][] targets=new int[8][];final int arcs;
  TinyGraph(int[][] edges){arcs=edges.length;for(int node=0;node<8;node++){final int id=node;targets[node]=Arrays.stream(edges).filter(e->e[0]==id).mapToInt(e->e[1]).toArray();}}
  public int numNodes(){return targets.length;}
  public long numArcs(){return arcs;}
  public int outdegree(int node){return targets[node].length;}
  public int[] successorArray(int node){return targets[node].clone();}
  public boolean randomAccess(){return true;}
  public ImmutableGraph copy(){return this;}
 }
 static void fixture(Path dir)throws Exception{
  Files.createDirectories(dir);
  int[][] edges={{0,0,0},{0,1,0},{0,2,25},{1,2,8},{1,3,2},{2,0,16},{2,3,11},{3,4,4},{3,5,12},{3,6,20},{3,7,28},{4,5,36},{5,3,0},{6,7,10},{7,7,1}};
  TinyGraph g=new TinyGraph(edges);
  byte[] labels=new byte[edges.length];int i=0;
  for(int[] edge:edges){labels[i++]=(byte)edge[2];}
  BVGraph.store(g,dir.resolve("forward").toString());Files.write(dir.resolve("graph.labels"),labels);
  try(ObjectOutputStream out=new ObjectOutputStream(Files.newOutputStream(dir.resolve("graph.strings")))){out.writeObject(new FrontCodedStringList(List.of("").iterator(),8,false));}
  try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(dir.resolve("graph.nodedata")))){out.writeInt(0x47524e03);out.writeInt(8);for(int n=0;n<8;n++){out.writeInt(n);out.writeByte(0);out.writeInt(100+n);}}
  try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(dir.resolve("graph.metadata")))){out.writeInt(0x47524d03);for(int n=0;n<8;n++)out.writeInt(0);}
  try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(dir.resolve("graph.comparisons")))){out.writeInt(0x47524303);out.writeInt(1);out.writeLong((2L<<32)|3);out.writeInt(1);out.writeInt(7);}
 }
 public static void main(String[] args)throws Exception{
  Path dir=Path.of(args[0]);if(args.length>1&&args[1].equals("generate")){fixture(dir);return;}
  Graph graph=GraphStore.INSTANCE.load(dir,GraphStore.LoadMode.EAGER);
  CypherExecutor scoped=new CypherExecutor(graph);
  CrossGraphCypherExecutor cross=new CrossGraphCypherExecutor(List.of(new CypherGraph("orders",graph),new CypherGraph("billing",graph)));
  Gson json=new GsonBuilder().serializeNulls().create();
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
  while((line=input.readLine())!=null){JsonObject spec=JsonParser.parseString(line).getAsJsonObject();String query=spec.get("query").getAsString();boolean qualified=spec.has("cross")&&spec.get("cross").getAsBoolean();Map<String,Object> result=new LinkedHashMap<>();result.put("query",query);result.put("cross",qualified);
   try{CypherResult rows=(qualified?cross.execute(query):scoped.execute(query));result.put("columns",rows.getColumns());result.put("rows",rows.getRows());}catch(Throwable error){result.put("error",error.getClass().getSimpleName());result.put("message",error.getMessage());}
   System.out.println(json.toJson(result));
  }
 }
}
