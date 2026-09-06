import com.google.gson.*;
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import io.johnsonlee.graphite.cypher.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.lang.reflect.Method;

/** Calls pinned main functions and executes tiny actual-writer graph queries. No timing. */
public class TrigramOracle {
 static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
 static int[] units(String s){return s.chars().toArray();}
 static Method reflect(String name,Class<?>... args)throws Exception {Method m=Class.forName("io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexKt").getDeclaredMethod(name,args);m.setAccessible(true);return m;}
 static MethodDescriptor method(String c,String n){return new MethodDescriptor(new TypeDescriptor(c,List.of()),n,List.of(),new TypeDescriptor("void",List.of()));}
 static void write(Path p,Object v)throws Exception{Files.writeString(p,JSON.toJson(v)+"\n");}
 public static void main(String[] args)throws Exception {
  Path dir=Path.of(args[0]);JsonObject input=JsonParser.parseString(Files.readString(dir.resolve("inputs.json"))).getAsJsonObject();
  List<String> values=new ArrayList<>();for(JsonElement v:input.getAsJsonArray("values"))values.add(v.getAsString());
  Method hash=reflect("callSiteTrigramHash",String.class,int.class),signature=reflect("callSiteTrigramSignature",String.class),eligible=reflect("canUseLowercaseTrigramPostings",StringPropertyPredicate.class),sigEligible=reflect("requiresTrigramSignature",StringPropertyPredicate.class);
  List<Object> primitives=new ArrayList<>();for(String value:values){Map<String,Object> row=new LinkedHashMap<>();String lower=value.toLowerCase(Locale.ROOT);row.put("units",units(value));row.put("lowerUnits",units(lower));row.put("signatureHelper",Long.toUnsignedString((Long)signature.invoke(null,value),16));List<Integer> hashes=new ArrayList<>();for(int i=0;i+2<lower.length();i++)hashes.add((Integer)hash.invoke(null,lower,i));row.put("lowerHashes",hashes);List<Object> decisions=new ArrayList<>();for(StringMatchMode mode:StringMatchMode.values())for(boolean wrapped:List.of(false,true)){StringPropertyPredicate p=new StringPropertyPredicate("caller_name",wrapped?StringValueTransform.LOWERCASE:null,mode,value);decisions.add(Map.of("mode",mode.name(),"lower",wrapped,"postings",eligible.invoke(null,p),"signature",sigEligible.invoke(null,p)));}row.put("eligibility",decisions);primitives.add(row);}write(dir.resolve("primitives.json"),primitives);
  DefaultGraph.Builder builder=new DefaultGraph.Builder();int i=0;for(String value:values){int id=7+i*11;String[] props={"neutralClass","neutralMethod","neutralTarget","neutralInvoke"};props[i%4]=value;builder.addNode((Node)CallSiteNode.class.getConstructors()[0].newInstance(id,method(props[0],props[1]),method(props[2],props[3]),i+1,null,List.of(),null));i++;}builder.addNode((Node)IntConstant.class.getConstructors()[0].newInstance(999,123,null));
  Path fixture=dir.resolve("store");GraphStore.INSTANCE.save(builder.build(),fixture,3,true);
  MappedWebGraphBackedGraph graph=(MappedWebGraphBackedGraph)GraphStore.INSTANCE.loadMapped(fixture);
  if(!graph.prepareCallSiteStringIndex$webgraph(()->{}))throw new AssertionError("index unavailable");var persisted=MappedWebGraphBackedGraph.class.getDeclaredField("callSiteStringIndexLoadedFromPersistence");persisted.setAccessible(true);if(!persisted.getBoolean(graph))throw new AssertionError("main rebuilt index");
  StringTable table=StringTable.Companion.load(fixture);List<Object> strings=new ArrayList<>();for(i=0;i<table.size();i++)strings.add(Map.of("id",i,"units",units(table.get(i)),"lowerUnits",units(table.get(i).toLowerCase(Locale.ROOT))));write(dir.resolve("strings.json"),strings);
  List<Object> nodes=new ArrayList<>();for(CallSiteNode node:kotlin.sequences.SequencesKt.toList(graph.nodes(CallSiteNode.class))){String[] fields={node.getCaller().getDeclaringClass().getClassName(),node.getCaller().getName(),node.getCallee().getDeclaringClass().getClassName(),node.getCallee().getName()};List<Integer> ids=new ArrayList<>();for(String field:fields)ids.add(table.findId$webgraph(field));nodes.add(Map.of("id",CallSiteNode.class.getMethod("getId-4bBHiqc").invoke(node),"stringIds",ids));}write(dir.resolve("nodes.json"),nodes);
  List<String> signatures=new ArrayList<>();Map<Integer,List<Integer>> trigrams=new TreeMap<>();try(DataInputStream in=new DataInputStream(Files.newInputStream(fixture.resolve("graph.callsite-string-index")))){in.readInt();in.readInt();int count=in.readInt(),calls=in.readInt();in.skipNBytes(32);int[] unique=new int[4];for(i=0;i<4;i++)unique[i]=in.readInt();int postings=in.readInt();in.readLong();for(int n:unique)in.skipNBytes(8L*n+4L*calls);for(i=0;i<count;i++)signatures.add(Long.toUnsignedString(in.readLong(),16));for(i=0;i<postings;i++){long pair=in.readLong();trigrams.computeIfAbsent((int)(pair>>32),k->new ArrayList<>()).add((int)pair);}write(dir.resolve("index.json"),Map.of("signatures",signatures,"trigrams",trigrams,"mainAcceptedPersisted",true));}
  List<Object> results=new ArrayList<>();for(JsonElement elem:input.getAsJsonArray("cases")){JsonObject c=elem.getAsJsonObject();String query=c.get("query").getAsString();Map<String,Object> params=JSON.fromJson(c.get("params"),Map.class);for(boolean cross:List.of(false,true)){Map<String,Object> result=new LinkedHashMap<>();result.put("name",c.get("name").getAsString());result.put("cross",cross);try{CypherResult r=cross?new CrossGraphCypherExecutor(List.of(new CypherGraph("a",graph),new CypherGraph("b",graph))).execute(query,params):new CypherExecutor(graph).execute(query,params);result.put("columns",r.getColumns());result.put("rows",r.getRows());}catch(Throwable t){result.put("error",t.getClass().getSimpleName());result.put("message",t.getMessage());}results.add(result);}}write(dir.resolve("queries.json"),results);graph.close();
 }
}
