import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;

/** Actual engine on independent cloned synthetic sources; no performance measurements. */
public final class BoundedMatcherPublicOracle {
    public static void main(String[] args)throws Exception {
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        var output=new ArrayList<Object>();
        String query="MATCH (n) WHERE n.caller_name CONTAINS 'othe' RETURN n.caller_name AS x LIMIT 1";
        for(int count:new int[]{2,64})for(String fixture:List.of("clean","bad-return-type","caller-name-max","caller-name-negative")) {
            var sources=new ArrayList<CypherGraph>();
            Path root=Path.of(args[0],count+"-"+fixture);
            try {
                for(int i=0;i<count;i++)sources.add(new CypherGraph(String.format("g%02d",i),GraphStore.INSTANCE.loadMapped(root.resolve(String.format("g%02d",i)))));
                for(int repetition=0;repetition<2;repetition++) {
                    var record=new LinkedHashMap<String,Object>();
                    record.put("sourceCount",count);record.put("fixtureMutation",fixture);record.put("repetition",repetition);record.put("query",query);
                    record.put("sourceFixture","clean");record.put("mutatedSource",fixture.equals("clean")?null:"g00");
                    record.put("mutationFile",fixture.equals("clean")?null:"graph.nodedata");
                    record.put("mutationByteOffset",fixture.equals("clean")?null:fixture.equals("bad-return-type")?98:74);
                    record.put("mutationNewSID",fixture.equals("clean")?null:fixture.equals("caller-name-negative")?-1:Integer.MAX_VALUE);
                    boolean expectError=fixture.startsWith("caller-name")||fixture.equals("bad-return-type")&&count==2;
                    try {
                        var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),new CypherCancellationSignal());
                        var result=new CrossGraphCypherExecutor(sources,context,false).execute(query,Map.of());
                        record.put("columns",result.getColumns());record.put("rows",result.getRows());
                        if(expectError)throw new AssertionError("invalid fixture should error");
                        if(result.getRows().size()!=1||!"other".equals(result.getRows().get(0).get("x")))throw new AssertionError("clean expected other row: "+result);
                    } catch(Throwable error) {
                        if(!expectError)throw new AssertionError("expected success failed",error);
                        record.put("error",error.getClass().getName());record.put("message",error.getMessage());
                        var stack=new ArrayList<String>();for(var frame:error.getStackTrace())stack.add(frame.toString());record.put("stack",stack);
                        if(fixture.startsWith("caller-name")) {
                            if(!(error instanceof ArrayIndexOutOfBoundsException))throw new AssertionError("dense matcher public exception",error);
                            if(stack.stream().noneMatch(s->s.contains("BoundedStringMatcher.state")))throw new AssertionError("must reach actual bounded state");
                        } else if(error.getClass()!=IndexOutOfBoundsException.class)throw new AssertionError("full node decode list exception",error);
                        String caller=count==64?"rawCallSiteStringProjection":"serialRawCallSiteStringDisjunction";
                        if(stack.stream().noneMatch(s->s.contains(caller)))throw new AssertionError("wrong raw caller: "+stack);
                    }
                    output.add(record);
                }
            } finally {for(CypherGraph source:sources)((java.io.Closeable)source.getGraph()).close();}
        }
        Files.writeString(Path.of(args[1]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(output)+"\n");
    }
}
