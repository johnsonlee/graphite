import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.core.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;import java.nio.*;import java.nio.file.*;import java.util.*;
public class CacheThresholdOracle{
 static long bytes(Graph graph)throws Exception{var field=graph.getClass().getDeclaredField("callSiteStringIndex");field.setAccessible(true);var index=field.get(graph);return index==null?0:(long)index.getClass().getMethod("getRetainedBytes").invoke(index);}
 static Map<String,Object> run(List<CypherGraph> sources,boolean cross,String query)throws Exception{var result=HistoryOracle.run(sources,cross,query);var sizes=new ArrayList<Long>();for(var source:sources)sizes.add(bytes(source.getGraph()));result.put("retainedBytes",sizes);return result;}
 public static void main(String[]args)throws Exception{
  Path fixtures=Path.of(args[0]);Files.createDirectories(fixtures);Graph reference=GraphStore.INSTANCE.load(Path.of(args[1]),GraphStore.LoadMode.EAGER);CallSiteNode template=kotlin.sequences.SequencesKt.toList(reference.nodes(CallSiteNode.class)).get(0);var caller=template.getCaller();caller=new MethodDescriptor(caller.getDeclaringClass(),"other"+"x".repeat(69995),List.of(),caller.getReturnType());var ctor=CallSiteNode.class.getConstructors()[0];
  for(String name:List.of("clean","bad")){var builder=new DefaultGraph.Builder();for(int i=0;i<2;i++)builder.addNode((Node)ctor.newInstance(i,caller,template.getCallee(),null,null,List.of(),null));GraphStore.INSTANCE.save(builder.build(),fixtures.resolve(name),3,true);}
  Path badData=fixtures.resolve("bad/graph.nodedata");byte[]raw=Files.readAllBytes(badData);ByteBuffer data=ByteBuffer.wrap(raw),offsets=ByteBuffer.wrap(Files.readAllBytes(fixtures.resolve("bad/graph.nodeoffsets")));for(int i=0;i<2;i++){int offset=(int)offsets.getLong(8+i*8)-1;int callerCount=data.getInt(offset+13),callee=offset+5+(4+callerCount)*4,calleeCount=data.getInt(callee+8);data.putInt(callee+12+calleeCount*4,Integer.MAX_VALUE);}Files.write(badData,raw);
  Graph a=GraphStore.INSTANCE.loadMapped(fixtures.resolve("clean")),b=GraphStore.INSTANCE.loadMapped(fixtures.resolve("bad"));var sources=List.of(new CypherGraph("a",a),new CypherGraph("b",b));var single=List.of(new CypherGraph("single",a));var results=new ArrayList<Object>();
  String prefix="MATCH (n) WHERE n.caller_name CONTAINS 'other'",probe=prefix+" RETURN n.caller_name AS x LIMIT 1";
  results.add(run(sources,true,prefix+" RETURN DISTINCT n.caller_name AS x LIMIT 1"));results.add(run(sources,true,probe));
  results.add(run(single,false,prefix+" RETURN n.caller_name AS a,n.caller_name AS b,n.caller_name AS c,n.caller_name AS d LIMIT 2"));results.add(run(sources,true,probe));
  for(int count=1;count<=33;count++){var columns=new ArrayList<String>();for(int i=0;i<count;i++)columns.add("n.callee_name AS x"+i);results.add(run(single,false,prefix+" RETURN "+String.join(",",columns)+" LIMIT 2"));}
  results.add(run(sources,true,probe));
  ((java.io.Closeable)a).close();((java.io.Closeable)b).close();Files.writeString(Path.of(args[2]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(results)+"\n");
 }
}
