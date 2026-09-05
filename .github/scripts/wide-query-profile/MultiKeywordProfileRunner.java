import io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark;
import io.johnsonlee.graphite.cypher.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

/** Diagnostic adapter over unchanged frozen-main classes; never used for a speedup claim. */
public final class MultiKeywordProfileRunner {
    static Object field(Object o, String name) throws Exception {
        Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Number || value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> m) {
            var parts=new ArrayList<String>();
            for(var e:m.entrySet())parts.add(json(e.getKey().toString())+":"+json(e.getValue()));
            return "{"+String.join(",",parts)+"}";
        }
        if(value instanceof Iterable<?> values) {
            var parts=new ArrayList<String>();for(Object v:values)parts.add(json(v));
            return "["+String.join(",",parts)+"]";
        }
        String s=value.toString();var b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '\\' -> b.append("\\\\"); case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r");case '\t' -> b.append("\\t");
                default -> {if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}
            }
        }
        return b.append('"').toString();
    }
    static String digest(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
    static List<Map<String,String>> cases(Path p, String only) throws Exception {
        var lines=Files.readAllLines(p);String[] headers=lines.get(0).split("\t",-1);
        var result=new ArrayList<Map<String,String>>();var ids=new HashSet<String>();
        for(String line:lines.subList(1,lines.size())) {
            if(line.isBlank())continue;String[] cells=line.split("\t",-1);
            if(cells.length!=headers.length)throw new IllegalArgumentException("TSV width");
            var row=new LinkedHashMap<String,String>();for(int i=0;i<headers.length;i++)row.put(headers[i],cells[i]);
            String id=row.get("id");if(id==null || !ids.add(id))throw new IllegalArgumentException("Invalid duplicate case");
            if(only.equals("all") || id.equals(only))result.add(row);
        }
        if(result.isEmpty())throw new IllegalArgumentException("No selected cases");return result;
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("manifest workloads.tsv output-prefix all|case-id replay-cold|per-query-cold");
        if(!Set.of("replay-cold","per-query-cold").contains(args[4]))throw new IllegalArgumentException("Unknown reset mode");
        var workload=cases(Path.of(args[1]),args[3]);Path prefix=Path.of(args[2]);
        if(Files.exists(Path.of(prefix+".tsv")) || Files.exists(Path.of(prefix+"-rows.jsonl")))throw new IllegalArgumentException("Refusing stale output");
        System.setProperty("graphite.broad.pressure.graphs",args[0]);
        System.setProperty("graphite.broad.pressure.correctness.mode","record");
        System.setProperty("graphite.broad.pressure.output",prefix+"-unused-original-oracle");
        var b=new LargeBroadQueryPressureBenchmark();b.setGraphCount(64);b.setCoverageFamily("global-wide");
        b.setIndexState("cold");b.setTimeoutMillis(300000);

        try {
            b.setupTrial();
            var sources=(List<CypherGraph>)field(b,"sources");
            var executor=(ExecutorService)field(b,"queryExecutor");
            if(sources.size()!=64)throw new IllegalStateException("Expected 64 sources");
            Files.writeString(Path.of(prefix+".tsv"),"id\tfamily\tshape\tprojection\tworkloadIdentity\toutcome\trowCount\tdigest\tlatencyNanos\thitGraphIds\tinputSourceCount\tresetMode\tgraphWorkUnits\tfilteredNodeLimitFastPathExecutions\tgeneralFallbackExecutions\n");
            Files.writeString(Path.of(prefix+"-rows.jsonl"),"");
            boolean first=true;
            for(var c:workload) {
                String id=c.get("id");
                if(first || args[4].equals("per-query-cold"))b.setupInvocation();first=false;
                String encoded=c.get("queryBase64");
                if(encoded==null)throw new IllegalArgumentException("Missing queryBase64");
                String query=new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8);
                var cancellation=new CypherCancellationSignal();
                var context=new CypherExecutionContext(new CypherExecutionBudget(Long.MAX_VALUE),cancellation);
                long started=System.nanoTime();
                Future<CypherResult> future=executor.submit(()->new CrossGraphCypherExecutor(sources,context).execute(query,Map.of()));
                CypherResult result;
                try {result=future.get(300000,TimeUnit.MILLISECONDS);}
                catch(TimeoutException e){cancellation.cancel();future.cancel(true);executor.submit(()->true).get(30,TimeUnit.SECONDS);throw e;}
                long elapsed=System.nanoTime()-started;
                var visibleColumns=result.getColumns().stream().filter(s->!s.equals("$metadata")).toList();
                if(visibleColumns.size()!=4)throw new IllegalStateException("Expected four projected columns: "+visibleColumns);
                var rows=new ArrayList<Map<String,Object>>();var returnedGraphs=new TreeSet<String>();
                for(var row:result.getRows()) {
                    var values=new ArrayList<Object>();for(String col:visibleColumns)values.add(row.get(col));
                    Object metadata=row.get("$metadata");
                    if(!(metadata instanceof Map<?,?> m) || !(m.get("graphIds") instanceof Iterable<?> graphIds))throw new IllegalStateException("Missing graph provenance");
                    var graphs=new TreeSet<String>();for(Object g:graphIds)graphs.add((String)g);
                    returnedGraphs.addAll(graphs);var normalized=new LinkedHashMap<String,Object>();normalized.put("values",values);normalized.put("graphIds",graphs);rows.add(normalized);
                }
                String canonical=json(rows);var output=new LinkedHashMap<String,Object>();output.put("id",id);output.put("columns",visibleColumns);output.put("rows",rows);
                Files.writeString(Path.of(prefix+"-rows.jsonl"),json(output)+"\n",StandardOpenOption.APPEND);
                String projection=Boolean.parseBoolean(c.get("distinct"))?"distinct-properties":"properties";
                Files.writeString(Path.of(prefix+".tsv"),String.join("\t",id,"multi-keyword",id,projection,digest(query),"success",Integer.toString(rows.size()),digest(canonical),Long.toString(elapsed),String.join(",",returnedGraphs),"64",args[4],Long.toString(context.getDiagnostics().getWorkUnitsConsumed()),Long.toString(context.getDiagnostics().getFilteredNodeLimitFastPathExecutions()),Long.toString(context.getDiagnostics().getGeneralFallbackExecutions()))+"\n",StandardOpenOption.APPEND);
                System.out.println(id+" rows="+rows.size()+" returnedGraphs="+returnedGraphs.size()+" latencyMs="+elapsed/1e6);
            }
        } finally {b.tearDownTrial();}
    }
}
