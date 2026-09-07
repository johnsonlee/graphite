import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;import io.johnsonlee.graphite.graph.Graph;import io.johnsonlee.graphite.webgraph.GraphStore;import kotlin.sequences.Sequence;
import java.lang.reflect.*;import java.nio.file.*;import java.util.*;
public class StreamingOracle{
 static class Trace implements InvocationHandler{
  final Graph graph;final String source;final int failAt;int consumed,hasNext;
  final List<Object> ids=Collections.synchronizedList(new ArrayList<>());final Set<String> calls=Collections.synchronizedSet(new TreeSet<>()),stacks=Collections.synchronizedSet(new TreeSet<>());
  Trace(Graph graph,String source,int failAt){this.graph=graph;this.source=source;this.failAt=failAt;}
  public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
   calls.add(method.getName());Object result;try{result=method.invoke(graph,args);}catch(InvocationTargetException x){throw x.getCause();}
   if(result instanceof Sequence && (method.getName().equals("nodes")||method.getName().startsWith("nodesByString"))){Sequence<?> seq=(Sequence<?>)result;return (Sequence<Object>)()->new Iterator<Object>(){final Iterator<?> it=seq.iterator();public boolean hasNext(){hasNext++;return it.hasNext();}public Object next(){consumed++;for(var e:Thread.currentThread().getStackTrace())if(e.getClassName().contains("QueryPipeline"))stacks.add(e.getMethodName());if(consumed==failAt)throw new IllegalStateException("AUDIT_FETCH_"+source+"_"+consumed);Object node=it.next();try{var f=node.getClass().getDeclaredField("id");f.setAccessible(true);ids.add(f.get(node));}catch(Exception x){throw new RuntimeException(x);}return node;}};}return result;
  }
  static void collect(Class<?> c,Set<Class<?>> out){if(c==null)return;for(Class<?> i:c.getInterfaces()){if(Modifier.isPublic(i.getModifiers()))out.add(i);collect(i,out);}collect(c.getSuperclass(),out);}
  Graph proxy(boolean capabilities){Set<Class<?>> out=new LinkedHashSet<>();out.add(Graph.class);if(capabilities)collect(graph.getClass(),out);return(Graph)Proxy.newProxyInstance(Graph.class.getClassLoader(),out.toArray(Class<?>[]::new),this);}
  Map<String,Object> state(){var m=new LinkedHashMap<String,Object>();m.put("source",source);m.put("next",consumed);m.put("hasNext",hasNext);m.put("ids",ids);m.put("calls",calls);m.put("pipelineStack",stacks);return m;}
 }
 public static void main(String[] args)throws Exception{
  System.err.println("java.version="+System.getProperty("java.version")+"; os.arch="+System.getProperty("os.arch")+"; availableProcessors="+Runtime.getRuntime().availableProcessors());
  Gson gson=new GsonBuilder().serializeNulls().setPrettyPrinting().create();var cases=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();var all=new ArrayList<Object>();
  for(var raw:cases){var spec=raw.getAsJsonObject();for(boolean cross:List.of(false,true)){if(spec.has("crossOnly")&&!cross)continue;Graph graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0]));boolean cap=spec.has("capabilities")&&spec.get("capabilities").getAsBoolean();int fail=spec.has("failAt")?spec.get("failAt").getAsInt():0;var traces=new ArrayList<Trace>();var sources=new ArrayList<CypherGraph>();for(String id:cross?List.of("a","b"):List.of("local")){var trace=new Trace(graph,id,id.equals("a")?0:fail);traces.add(trace);sources.add(new CypherGraph(id,trace.proxy(cap)));}
   var out=new LinkedHashMap<String,Object>();String q=spec.get("query").getAsString();out.put("name",spec.get("name").getAsString());out.put("query",q);out.put("cross",cross);out.put("capabilities",cap);Map<String,Object> params=spec.has("params")?gson.fromJson(spec.get("params"),Map.class):Map.of();out.put("params",params);try{var result=cross?new CrossGraphCypherExecutor(sources).execute(q,params):new CypherExecutor(sources.get(0).getGraph()).execute(q,params);out.put("columns",result.getColumns());out.put("rows",result.getRows());}catch(Throwable x){out.put("error",x.getClass().getSimpleName());out.put("message",x.getMessage());}out.put("traces",traces.stream().map(Trace::state).toList());all.add(out);((java.io.Closeable)graph).close();
  }}
  String json=gson.toJson(all);var escaped=new StringBuilder();for(char c:json.toCharArray())if(Character.isSurrogate(c))escaped.append(String.format("\\u%04x",(int)c));else escaped.append(c);Files.writeString(Path.of(args[2]),escaped+"\n");
 }
}
