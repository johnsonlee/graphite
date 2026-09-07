import com.google.gson.*;
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import io.johnsonlee.graphite.cypher.*;
import it.unimi.dsi.util.FrontCodedStringList;
import it.unimi.dsi.fastutil.io.BinIO;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public class FindIdQueryOracle {
 static Path generate(Path root,String name,List<String> replacement,int sid)throws Exception{
  Path dir=root.resolve(name);DefaultGraph.Builder b=new DefaultGraph.Builder();TypeDescriptor a=new TypeDescriptor("aaa",List.of());MethodDescriptor m=new MethodDescriptor(a,"aaa",List.of(),a);
  b.addNode((CallSiteNode)CallSiteNode.class.getConstructors()[0].newInstance(7,m,m,Integer.valueOf(1),null,List.of(),null));GraphStore.INSTANCE.save(b.build(),dir,3,true);
  if(replacement!=null){
   BinIO.storeObject(new FrontCodedStringList(replacement.iterator(),4,false),dir.resolve("graph.strings").toString());
   Files.deleteIfExists(dir.resolve("graph.strings.identity"));Files.deleteIfExists(dir.resolve("graph.callsite-string-index"));Files.deleteIfExists(dir.resolve("graph.callsite-string-content.identity"));
   byte[] data=Files.readAllBytes(dir.resolve("graph.nodedata"));ByteBuffer buf=ByteBuffer.wrap(data);
   for(int offset:new int[]{13,17,25,29,33,41})buf.putInt(offset,sid);Files.write(dir.resolve("graph.nodedata"),data);
   MappedWebGraphBackedGraph graph=(MappedWebGraphBackedGraph)GraphStore.INSTANCE.loadMapped(dir);
   if(!graph.prepareCallSiteStringIndex$webgraph(()->{})||!graph.persistPreparedCallSiteStringIndex$webgraph())throw new AssertionError("index build failed");graph.close();
  }
  return dir;
 }
 public static void main(String[]args)throws Exception{
  Path root=Path.of(args[0]);Files.createDirectories(root);Path clean=generate(root,"clean",null,0);Path dup=generate(root,"duplicate",List.of("aaa","aaa","aaa"),0);Path unsorted=generate(root,"unsorted",List.of("bbb","aaa"),1);
  List<Map<String,Object>> outputs=new ArrayList<>();
  for(Path late:List.of(dup,unsorted)){
   Graph first=GraphStore.INSTANCE.loadMapped(clean),last=GraphStore.INSTANCE.loadMapped(late);List<CypherGraph> sources=new ArrayList<>();for(int i=0;i<9;i++)sources.add(new CypherGraph("g"+i,i==8?last:first));
   String query="MATCH (n:CallSite) WHERE n.caller_name CONTAINS 'a' RETURN DISTINCT n.caller_name AS x LIMIT 1";
   Map<String,Object> out=new LinkedHashMap<>();out.put("fixture",late.getFileName().toString());out.put("query",query);out.put("sources",9);
   try{CypherResult result=new CrossGraphCypherExecutor(sources).execute(query);out.put("columns",result.getColumns());out.put("rows",result.getRows());}catch(Throwable e){out.put("error",e.getClass().getSimpleName());out.put("message",e.getMessage());}
   outputs.add(out);((java.io.Closeable)first).close();((java.io.Closeable)last).close();
  }
  System.out.println(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(outputs));
 }
}
