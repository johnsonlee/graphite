import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual pinned engine, complete public responses; synthetic correctness only, no timers. */
public final class FilteredCountOracle {
    static Object invoke(Object target,String prefix)throws Exception {
        for(Method method:target.getClass().getDeclaredMethods()) {
            if(method.getName().startsWith(prefix)&&method.getParameterCount()==0) {
                method.setAccessible(true);return method.invoke(target);
            }
        }
        throw new IllegalStateException("Missing state accessor "+prefix);
    }
    static List<Object> state(List<CypherGraph> sources)throws Exception {
        var output=new ArrayList<Object>();
        for(var source:sources) {
            var row=new LinkedHashMap<String,Object>();row.put("id",source.getId());
            row.put("retained",invoke(source.getGraph(),"isCallSiteStringIndexInitialized"));
            row.put("mappedView",invoke(source.getGraph(),"isMappedCallSiteStringIndexViewInitialized"));
            output.add(row);
        }
        return output;
    }
    public static void main(String[]args)throws Exception {
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        Gson gson=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        JsonArray specs=JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray();
        var records=new ArrayList<Object>();
        for(var item:specs) {
            var spec=item.getAsJsonObject();String name=spec.get("name").getAsString();
            int count=spec.get("sources").getAsInt();
            var sources=new ArrayList<CypherGraph>();
            try {
                for(int i=0;i<count;i++)sources.add(new CypherGraph(spec.has("graphIDs")?spec.getAsJsonArray("graphIDs").get(i).getAsString():String.format("g%02d",i),
                    GraphStore.INSTANCE.loadMapped(Path.of(args[0],name,String.format("g%02d",i)))));
                for(int repeat=0;repeat<2;repeat++) {
                    var record=new LinkedHashMap<String,Object>();record.put("name",name);
                    record.put("repetition",repeat);record.put("spec",gson.fromJson(spec,Map.class));
                    record.put("before",state(sources));
                    try {
                        String query=spec.get("query").getAsString();
                        Map<String,Object> parameters=spec.has("parameters")?gson.fromJson(spec.get("parameters"),Map.class):Map.of();
                        var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
                        var result=count==1&&!spec.has("qualified")?
                            new CypherExecutor(sources.get(0).getGraph(),context).execute(query,parameters):
                            new CrossGraphCypherExecutor(sources,context,spec.has("scoped")&&spec.get("scoped").getAsBoolean()).execute(query,parameters);
                        record.put("columns",result.getColumns());record.put("rows",result.getRows());
                        var types=new ArrayList<Object>();
                        for(var row:result.getRows()) {
                            var rowTypes=new LinkedHashMap<String,Object>();
                            for(var column:result.getColumns())rowTypes.put(column,row.get(column)==null?null:row.get(column).getClass().getName());
                            types.add(rowTypes);
                        }
                        record.put("types",types);
                    }catch(Throwable error) {
                        record.put("error",error.getClass().getSimpleName());record.put("qualifiedError",error.getClass().getName());
                        record.put("message",error.getMessage());
                        var stack=new ArrayList<String>();for(var frame:error.getStackTrace())stack.add(frame.toString());record.put("stack",stack);
                    }
                    record.put("after",state(sources));records.add(record);
                }
            }finally {for(var source:sources)((java.io.Closeable)source.getGraph()).close();}
        }
        Files.writeString(Path.of(args[2]),gson.toJson(records)+"\n",StandardOpenOption.CREATE_NEW);
    }
}
