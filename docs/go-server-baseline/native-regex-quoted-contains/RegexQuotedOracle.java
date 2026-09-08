import com.google.gson.*;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Direct Java17 Pattern and original public Cypher correctness, preserving UTF16. */
public final class RegexQuotedOracle {
    static final Gson JSON=new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
    static List<Integer> units(String text){List<Integer> out=new ArrayList<>();if(text!=null)for(int i=0;i<text.length();i++)out.add((int)text.charAt(i));return out;}
    static void write(Path path,Object value)throws Exception {
        String raw=JSON.toJson(value);StringBuilder escaped=new StringBuilder();
        for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(Character.isSurrogate(c))escaped.append(String.format("\\u%04x",(int)c));else escaped.append(c);}
        Files.writeString(path,escaped+"\n",StandardOpenOption.CREATE_NEW);
    }
    static void error(Map<String,Object> record,Throwable error){
        record.put("outcome","FAILED");record.put("error",error.getClass().getSimpleName());record.put("errorClass",error.getClass().getName());record.put("message",error.getMessage());record.put("errorUTF16",units(error.getMessage()));
        record.put("stack",Arrays.stream(error.getStackTrace()).map(Object::toString).toList());
        if(error instanceof PatternSyntaxException regex){record.put("description",regex.getDescription());record.put("index",regex.getIndex());}
    }
    static Object state(Graph graph)throws Exception {
        Map<String,Object> output=new LinkedHashMap<>();
        for(String key:List.of("retained","mappedView")){
            String prefix=key.equals("retained")?"isCallSiteStringIndexInitialized":"isMappedCallSiteStringIndexViewInitialized";
            List<Method> methods=Arrays.stream(graph.getClass().getDeclaredMethods()).filter(m->m.getName().startsWith(prefix)&&m.getParameterCount()==0).toList();
            if(methods.size()!=1)throw new IllegalStateException("missing state accessor "+prefix);
            Method method=methods.get(0);method.setAccessible(true);output.put(key,method.invoke(graph));
        }return output;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("pattern-cases public-cases fixture-root new-output-directory");
        Path out=Path.of(args[3]);Files.createDirectory(out);
        if(!System.getProperty("java.specification.version").equals("17"))throw new IllegalStateException("Java17 required");
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        List<Object> patterns=new ArrayList<>();
        for(JsonElement input:JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray()){
            JsonObject spec=input.getAsJsonObject();String regex=spec.get("pattern").getAsString(),text=spec.get("text").getAsString();
            Map<String,Object> record=new LinkedHashMap<>();record.put("name",spec.get("name").getAsString());record.put("spec",spec);record.put("patternUTF16",units(regex));record.put("textUTF16",units(text));String phase="compile";
            try{Pattern compiled=Pattern.compile(regex);record.put("compileSucceeded",true);phase="matches";record.put("matches",compiled.matcher(text).matches());record.put("outcome","SUCCESS");}
            catch(Throwable error){error(record,error);}
            record.put("phase",phase);patterns.add(record);
        }
        write(out.resolve("pattern-main.json"),Map.of("javaVersion",System.getProperty("java.version"),"performanceMeasurements",0,"cases",patterns));
        List<Object> publics=new ArrayList<>();
        for(JsonElement input:JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonArray()){
            JsonObject spec=input.getAsJsonObject();String name=spec.get("name").getAsString();
            Map<String,Object> record=new LinkedHashMap<>();record.put("name",name);record.put("spec",spec);Graph graph=null;String phase="load";
            try{
                graph=GraphStore.INSTANCE.loadMapped(Path.of(args[2],name,"store0"));record.put("before",state(graph));phase="execute";
                @SuppressWarnings("unchecked") Map<String,Object> parameters=JSON.fromJson(spec.get("parameters"),Map.class);
                var result=new CypherExecutor(graph).execute(spec.get("query").getAsString(),parameters);
                record.put("columns",result.getColumns());record.put("rows",result.getRows());List<Object> types=new ArrayList<>();
                for(var row:result.getRows()){Map<String,Object> rowTypes=new LinkedHashMap<>();for(String column:result.getColumns())rowTypes.put(column,row.get(column)==null?null:row.get(column).getClass().getName());types.add(rowTypes);}
                record.put("types",types);record.put("outcome","SUCCESS");
            }catch(Throwable error){error(record,error);}
            finally{if(graph!=null){record.put("after",state(graph));((java.io.Closeable)graph).close();}}
            record.put("phase",phase);publics.add(record);
        }
        write(out.resolve("public-main.json"),Map.of("javaVersion",System.getProperty("java.version"),"mainRevision","4e328b0109e13c896b74004823fb049fcb19251a","performanceMeasurements",0,"cases",publics));
    }
}
