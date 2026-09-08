import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Original public executors on cloned persisted fixtures; correctness only. */
public final class UnknownLabelOracle {
    static Object flag(Graph graph,String prefix)throws Exception {
        for(Method method:graph.getClass().getDeclaredMethods())if(method.getName().startsWith(prefix)&&method.getParameterCount()==0) {
            method.setAccessible(true);return method.invoke(graph);
        }
        throw new IllegalStateException("Missing state accessor "+prefix);
    }
    static List<Object> states(List<CypherGraph> sources)throws Exception {
        var output=new ArrayList<Object>();
        for(var source:sources)output.add(Map.of("id",source.getId(),
            "retained",flag(source.getGraph(),"isCallSiteStringIndexInitialized"),
            "mappedView",flag(source.getGraph(),"isMappedCallSiteStringIndexViewInitialized")));
        return output;
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        Gson json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        var specs=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        var output=new ArrayList<Object>();
        for(var input:specs) {
            var spec=input.getAsJsonObject();String name=spec.get("name").getAsString();
            var sources=new ArrayList<CypherGraph>();var record=new LinkedHashMap<String,Object>();
            record.put("spec",json.fromJson(spec,Map.class));record.put("name",name);
            String phase="load";
            try {
                int sourceIndex=0;
                for(var source:spec.getAsJsonArray("sources")) {
                    Graph graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0],name,"store"+sourceIndex++));
                    sources.add(new CypherGraph(source.getAsJsonObject().get("id").getAsString(),graph));
                }
                record.put("before",states(sources));phase="context";
                var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
                String query=spec.get("query").getAsString();phase="execute";
                var result=spec.get("cross").getAsBoolean()?
                    new CrossGraphCypherExecutor(sources,context,spec.get("scoped").getAsBoolean()).execute(query,Map.of()):
                    new CypherExecutor(sources.get(0).getGraph(),context).execute(query,Map.of());
                record.put("columns",result.getColumns());record.put("rows",result.getRows());
                var types=new ArrayList<Object>();
                for(var row:result.getRows()) {
                    var rowTypes=new LinkedHashMap<String,Object>();
                    for(var column:result.getColumns())rowTypes.put(column,row.get(column)==null?null:row.get(column).getClass().getName());
                    types.add(rowTypes);
                }
                record.put("types",types);record.put("outcome","SUCCESS");
            }catch(Throwable error) {
                record.put("outcome","FAILED");record.put("error",error.getClass().getSimpleName());
                record.put("qualifiedError",error.getClass().getName());record.put("message",error.getMessage());
                var stack=new ArrayList<String>();for(var frame:error.getStackTrace())stack.add(frame.toString());record.put("stack",stack);
                var causes=new ArrayList<Object>();
                for(Throwable cause=error.getCause();cause!=null;cause=cause.getCause()) {
                    var item=new LinkedHashMap<String,Object>();item.put("class",cause.getClass().getName());item.put("message",cause.getMessage());causes.add(item);
                }
                record.put("causes",causes);
            }finally {
                record.put("after",states(sources));for(var source:sources)((java.io.Closeable)source.getGraph()).close();
            }
            record.put("phase",phase);output.add(record);
        }
        Files.writeString(Path.of(args[2]),json.toJson(output)+"\n",StandardOpenOption.CREATE_NEW);
    }
}
