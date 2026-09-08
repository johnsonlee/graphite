import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual public constructor and execution; no substituted engine, no timing. */
public final class SourceConstructorOracle {
    static Object flag(Graph graph,String prefix)throws Exception {
        for(Method method:graph.getClass().getDeclaredMethods())if(method.getName().startsWith(prefix)&&method.getParameterCount()==0) {
            method.setAccessible(true);return method.invoke(graph);
        }
        throw new IllegalStateException("Missing state accessor "+prefix);
    }
    static List<Object> states(List<Graph> graphs)throws Exception {
        var states=new ArrayList<Object>();
        for(int index=0;index<graphs.size();index++)states.add(Map.of("store",index,
            "retained",flag(graphs.get(index),"isCallSiteStringIndexInitialized"),
            "mappedView",flag(graphs.get(index),"isMappedCallSiteStringIndexViewInitialized")));
        return states;
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        Gson json=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        var specs=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        var output=new ArrayList<Object>();
        for(var input:specs) {
            var spec=input.getAsJsonObject();String name=spec.get("name").getAsString();
            var graphs=new ArrayList<Graph>();var record=new LinkedHashMap<String,Object>();
            record.put("spec",json.fromJson(spec,Map.class));record.put("name",name);
            String stage="load";
            try {
                int physical=spec.get("physicalStores").getAsInt();
                for(int i=0;i<physical;i++) {
                    Path fixture=Path.of(args[0],name,"store"+i);
                    stage="prepare";GraphStore.INSTANCE.ensureNodeIndex(fixture);
                    stage="load";graphs.add(GraphStore.INSTANCE.loadMapped(fixture));
                }
                record.put("before",states(graphs));
                stage="context";
                var signal=new CypherCancellationSignal();
                String cancellation=spec.get("cancellation").getAsString();
                if(cancellation.equals("cancelled"))record.put("cancelAccepted",signal.cancel());
                if(cancellation.equals("timeout"))record.put("cancelAccepted",signal.cancel(new CypherQueryTimeoutException(17L)));
                var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),signal);
                record.put("cancelledBeforeConstructor",signal.isCancelled());
                stage="sources";
                var sources=new ArrayList<CypherGraph>();
                var ids=spec.getAsJsonArray("graphIDs");var stores=spec.getAsJsonArray("storeIndexes");
                record.put("constructedSourceCount",0);
                for(int i=0;i<ids.size();i++) {
                    int store=stores.get(i).getAsInt();
                    sources.add(new CypherGraph(ids.get(i).getAsString(),store<0?null:graphs.get(store)));
                    record.put("constructedSourceCount",i+1);
                }
                record.put("sourceObjectsConstructed",true);
                stage="constructor";
                var executor=new CrossGraphCypherExecutor(sources,context,spec.get("scoped").getAsBoolean());
                record.put("executorConstructed",true);
                stage="execute";
                var result=executor.execute(spec.get("query").getAsString(),Map.of());
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
            }finally {record.put("after",states(graphs));for(Graph graph:graphs)((java.io.Closeable)graph).close();}
            record.put("phase",stage);output.add(record);
        }
        Files.writeString(Path.of(args[2]),json.toJson(output)+"\n",StandardOpenOption.CREATE_NEW);
    }
}
