import java.nio.*;import java.nio.file.*;import java.util.*;import com.google.gson.*;import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.*;import io.johnsonlee.graphite.webgraph.*;
public class TupleBadSIDOracle{
 public static void main(String[]args)throws Exception{
  Path source=Path.of(args[0]),root=Path.of(args[1]);Files.createDirectories(root);var all=new ArrayList<Object>();
  int only=args.length>3?Integer.parseInt(args[3]):-1;int at=0;for(int property=0;property<4;property++)for(int sid:List.of(-1,Integer.MAX_VALUE)){if(only<0||only==at)all.add(run(source,root,property,sid,false));at++;}if(only<0||only==8)all.add(run(source,root,0,-1,true));
  Files.writeString(Path.of(args[2]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(all)+"\n");
 }
 static Object run(Path source,Path root,int property,int sid,boolean badRead)throws Exception{
  Path dir=root.resolve("p"+property+"-"+sid+"-"+badRead);Files.createDirectories(dir);try(var files=Files.list(source)){for(Path f:files.toList())Files.copy(f,dir.resolve(f.getFileName()),StandardCopyOption.REPLACE_EXISTING);}
  byte[] data=Files.readAllBytes(dir.resolve("graph.nodedata"));var b=ByteBuffer.wrap(data);var offsets=ByteBuffer.wrap(Files.readAllBytes(dir.resolve("graph.nodeoffsets")));int at=(int)offsets.getLong(8+4095*8)-1;int count=b.getInt(at+13);int field=property<2?at+5+property*4:at+5+(4+count)*4+(property-2)*4;b.putInt(field,sid);if(badRead)b.putInt(at+13,1000000);Files.write(dir.resolve("graph.nodedata"),data);
  Graph g=GraphStore.INSTANCE.loadMapped(dir);new CypherExecutor(g).execute("MATCH (n) WHERE n.caller_name CONTAINS 'other' RETURN DISTINCT n.caller_name AS x LIMIT 1");Object i=ExactTupleOracle.field(g,"callSiteStringIndex");ExactTupleOracle.call(i,"clearQueryCaches",new Class<?>[0]);var out=new LinkedHashMap<String,Object>();out.put("property",property);out.put("sid",sid);out.put("badRead",badRead);out.put("before",ExactTupleOracle.state(i));Set<List<String>> values=new LinkedHashSet<>();for(int n=0;n<256;n++)values.add(List.of("other"+n));try{ExactTupleOracle.call(i,"distinctProjection",new Class<?>[]{List.class,List.class,int.class,Set.class,GraphWorkConsumer.class},ExactTupleOracle.PS,List.of("caller_name"),256,values,null);}catch(Exception x){out.put("errorClass",x.getClass().getSimpleName());out.put("error",x.getMessage());}out.put("after",ExactTupleOracle.state(i));((java.io.Closeable)g).close();return out;
 }
}
