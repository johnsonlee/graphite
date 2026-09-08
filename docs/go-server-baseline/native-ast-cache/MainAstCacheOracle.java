import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.johnsonlee.graphite.cypher.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Correctness-only observations of actual pinned-main parser and immutable AST. */
public final class MainAstCacheOracle {
    static final Object ADAPTER=CypherDslAdapter.INSTANCE;
    static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    static final List<Object> EVENTS=new ArrayList<>();
    static Object field(Object owner,String name)throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    static Object method(Object owner,String prefix,Object...args)throws Exception {
        List<Method> found=Arrays.stream(owner.getClass().getDeclaredMethods()).filter(m->m.getName().startsWith(prefix)&&m.getParameterCount()==args.length).toList();
        if(found.size()!=1)throw new IllegalStateException("ambiguous method "+prefix);
        Method m=found.get(0);m.setAccessible(true);
        try{return m.invoke(owner,args);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    static void clear()throws Exception {method(ADAPTER,"clearParsedQueryCache");}
    static String sha(String text)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    static Object query(String q)throws Exception {
        Map<String,Object> r=new LinkedHashMap<>();r.put("raw",q.length()<300?q:null);r.put("utf16Length",q.length());r.put("utf8Sha256",sha(q));return r;
    }
    static Object state()throws Exception {
        Map<?,?> cache=(Map<?,?>)field(ADAPTER,"parsedQueries");List<Object> entries=new ArrayList<>();
        for(var e:cache.entrySet())entries.add(Map.of("query",query((String)e.getKey()),"retainedBytes",field(e.getValue(),"retainedBytes")));
        return Map.of("size",cache.size(),"bytes",field(ADAPTER,"parsedQueryCacheBytes"),"maxBytes",method(ADAPTER,"getMaxParsedQueryCacheBytes"),"entriesLruToMru",entries);
    }
    static Object plain(Object v)throws Exception {
        if(v==null||v instanceof String||v instanceof Number||v instanceof Boolean)return v;
        if(v instanceof Enum<?>)return v.toString();
        if(v instanceof Map<?,?> m){Map<String,Object> r=new LinkedHashMap<>();for(var e:m.entrySet())r.put(String.valueOf(e.getKey()),plain(e.getValue()));return r;}
        if(v instanceof Iterable<?> it){List<Object> r=new ArrayList<>();for(Object x:it)r.add(plain(x));return r;}
        Map<String,Object> r=new LinkedHashMap<>();r.put("$class",v.getClass().getName());
        for(Field f:v.getClass().getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!f.isSynthetic()){f.setAccessible(true);r.put(f.getName(),plain(f.get(v)));}
        return r;
    }
    static Map<String,Object> error(Throwable e){Map<String,Object> r=new LinkedHashMap<>();r.put("class",e.getClass().getName());r.put("message",e.getMessage());r.put("stack",Arrays.stream(e.getStackTrace()).map(Object::toString).toList());return r;}
    static List<CypherClause> parse(String q){return CypherDslAdapter.INSTANCE.parse(q);}
    static void event(String name,String q)throws Exception {
        Map<String,Object> r=new LinkedHashMap<>();r.put("name",name);r.put("query",query(q));r.put("before",state());
        try{var ast=parse(q);r.put("clauseCount",ast.size());r.put("clauses",ast.stream().map(x->x.getClass().getSimpleName()).toList());r.put("ast",plain(ast));r.put("outcome","SUCCESS");}
        catch(Throwable e){r.put("outcome","FAILED");r.put("error",error(e));}
        r.put("after",state());EVENTS.add(r);
    }
    static void identity(String name,String q)throws Exception {
        Object a=parse(q),b=parse(q);EVENTS.add(Map.of("name",name,"query",query(q),"sameListIdentity",a==b,"sameValue",a.equals(b),"state",state()));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void walkMutations(Object v,String path,IdentityHashMap<Object,Boolean> seen,List<Object> output)throws Exception {
        if(v==null||v instanceof String||v instanceof Number||v instanceof Boolean||v instanceof Enum<?>||seen.put(v,true)!=null)return;
        if(v instanceof List list){
            for(int i=0;i<list.size();i++)walkMutations(list.get(i),path+"["+i+"]",seen,output);
            Map<String,Object> record=new LinkedHashMap<>();record.put("path",path);record.put("kind","list.add");record.put("class",v.getClass().getName());
            try{list.add(null);record.put("mutationAccepted",true);}catch(Throwable e){record.put("mutationAccepted",false);record.put("error",error(e));}output.add(record);return;
        }
        if(v instanceof Map map){
            for(Object raw:map.entrySet()){var e=(Map.Entry)raw;walkMutations(e.getValue(),path+"."+e.getKey(),seen,output);}
            Map<String,Object> record=new LinkedHashMap<>();record.put("path",path);record.put("kind","map.put");record.put("class",v.getClass().getName());
            try{map.put("__poison__",true);record.put("mutationAccepted",true);}catch(Throwable e){record.put("mutationAccepted",false);record.put("error",error(e));}output.add(record);
            if(!map.isEmpty()){
                Map<String,Object> entryRecord=new LinkedHashMap<>();entryRecord.put("path",path);entryRecord.put("kind","entry.setValue");
                try{((Map.Entry)map.entrySet().iterator().next()).setValue(null);entryRecord.put("mutationAccepted",true);}catch(Throwable e){entryRecord.put("mutationAccepted",false);entryRecord.put("error",error(e));}output.add(entryRecord);
            }return;
        }
        if(v.getClass().getName().startsWith("io.johnsonlee.graphite.cypher")||v.getClass().getName().equals("kotlin.Pair")){
            for(Field f:v.getClass().getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!f.isSynthetic()){f.setAccessible(true);walkMutations(f.get(v),path+"."+f.getName(),seen,output);}
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("fixture-root new-output.json");
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        clear();event("raw-first","RETURN $value AS x");identity("raw-identical-object","RETURN $value AS x");
        event("raw-leading-space"," RETURN $value AS x");event("raw-trailing-space","RETURN $value AS x ");event("raw-trailing-semicolon","RETURN $value AS x;");
        for(String q:List.of(""," ","\t\n","\u00a0"))event("blank-"+q.length()+"-"+sha(q).substring(0,6),q);
        clear();event("error-first","MATCH RETURN n");event("error-repeat","MATCH RETURN n");
        event("literal-error-first","RETURN 999999999999999999999999");event("literal-error-repeat","RETURN 999999999999999999999999");
        clear();event("semicolon-parent","RETURN 1; RETURN 2");identity("semicolon-parent-hit","RETURN 1; RETURN 2");identity("semicolon-subquery-hit","RETURN 1");event("semicolon-parent-whitespace"," RETURN 1 ;  RETURN 2 ");
        clear();event("semicolon-partial-error","RETURN 1; MATCH RETURN n");event("semicolon-partial-error-repeat","RETURN 1; MATCH RETURN n");
        clear();event("semicolon-only",";;;");event("semicolon-in-string","RETURN ';' AS x");
        String[] clauses={
            "MATCH (n) WHERE n.id=1 RETURN n ORDER BY n.id SKIP 1 LIMIT 2",
            "OPTIONAL MATCH (n) WHERE n.id=1 RETURN n ORDER BY n.id SKIP 1 LIMIT 2",
            "WITH 1 AS x ORDER BY x SKIP 0 LIMIT 2 WHERE x=1 RETURN x",
            "RETURN 1 AS x UNION RETURN 2 AS x UNION ALL RETURN 3 AS x",
            "UNWIND [1,2] AS x WITH x WHERE x=1 RETURN x ORDER BY x LIMIT 1",
            "RETURN '\uD83D\uDE00' AS x",
            "WITH 1 AS x WHERE x=1 ORDER BY x SKIP 0 LIMIT 2 RETURN x"
        };
        for(int i=0;i<clauses.length;i++){clear();event("clauses-"+i,clauses[i]);}
        clear();String nested="MATCH (n:A:B {p:{inner:[1,{deep:[2,3]}]}})-[r:R|S {p:[4,5]}]->(m) WHERE n.p IS NOT NULL RETURN {a:[n.p,{b:[1,2]}]} AS x ORDER BY x LIMIT 1";
        var ast=parse(nested);Object before=plain(ast);List<Object> mutations=new ArrayList<>();walkMutations(ast,"ast",new IdentityHashMap<>(),mutations);
        EVENTS.add(Map.of("name","deep-parser-immutability","query",query(nested),"beforeAst",before,"afterAst",plain(ast),"mutations",mutations,"cacheIdentityUnchanged",ast==parse(nested),"state",state()));
        List<Object> originalList=new ArrayList<>(List.of("value"));Map<Object,Object> originalMap=new LinkedHashMap<>();originalMap.put("key",originalList);
        var mutableAst=List.of(new CypherClause.Return(List.of(new ReturnItem(new CypherExpr.Literal(originalMap),"literal")),false));
        Class<?> freezer=Class.forName("io.johnsonlee.graphite.cypher.ImmutableCypherAstKt");Method freeze=freezer.getDeclaredMethod("toImmutableCypherAst",List.class);freeze.setAccessible(true);Object immutable=freeze.invoke(null,mutableAst);
        Object frozenBefore=plain(immutable);originalList.add("later");originalMap.put("later",true);List<Object> literalMutations=new ArrayList<>();walkMutations(immutable,"literalAst",new IdentityHashMap<>(),literalMutations);
        EVENTS.add(Map.of("name","deep-literal-freezer","beforeAst",frozenBefore,"afterExternalAndDirectMutations",plain(immutable),"mutations",literalMutations));
        clear();Object first=null;
        for(int i=0;i<1024;i++){Object item=parse("RETURN "+i+" AS x");if(i==0)first=item;}
        Object filled=state();boolean firstHit=first==parse("RETURN 0 AS x");parse("RETURN 500 AS x");Object touched=state();parse("RETURN 1024 AS x");Object evicted=state();
        EVENTS.add(Map.of("name","entry-lru-1024","firstIdentityRetained",firstHit,"filled",filled,"afterTouches",touched,"afterInsertion",evicted));
        clear();long cap=(Long)method(ADAPTER,"getMaxParsedQueryCacheBytes");
        String padded="RETURN 1 AS x"+" ".repeat((int)(cap/32));
        for(int i=0;i<24;i++)parse(padded+" ".repeat(i));
        EVENTS.add(Map.of("name","byte-budget-eviction","queryRecipe","RETURN 1 AS x + space.repeat(maxBytes/32+i), i=0..23","state",state()));
        clear();parse("RETURN 1 AS x");Object beforeLarge=state();String large="RETURN 2 AS x"+" ".repeat((int)(cap/2));
        Object largeA=parse(large),largeB=parse(large);
        EVENTS.add(Map.of("name","oversize-not-cached","query",query(large),"queryRecipe","RETURN 2 AS x + space.repeat(maxBytes/2)","sameListIdentity",largeA==largeB,"sameValue",largeA.equals(largeB),"before",beforeLarge,"after",state()));
        clear();parse("RETURN 1 AS x");
        String exactBase="RETURN 3 AS x",exact=exactBase+" ".repeat((int)((cap-128-2048)/2)-exactBase.length());
        Object exactA=parse(exact),exactB=parse(exact);Object atCapacity=state();
        Object aboveA=parse(exact+" "),aboveB=parse(exact+" ");
        EVENTS.add(Map.of("name","exact-byte-capacity","query",query(exact),"queryRecipe","RETURN 3 AS x padded to UTF16 length (maxBytes-128-2048)/2",
            "exactSameIdentity",exactA==exactB,"aboveSameIdentity",aboveA==aboveB,"atCapacity",atCapacity,"afterAboveCapacity",state()));
        clear();List<Object> executions=new ArrayList<>();Object cached=null;
        for(int graphIndex=0;graphIndex<2;graphIndex++){
            Graph graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0],"store"+graphIndex));
            try{
                for(int value:List.of(7,8)){
                    String q="RETURN $value AS x";var result=new CypherExecutor(graph).execute(q,Map.of("value",value));Object parsed=parse(q);
                    if(cached==null)cached=parsed;
                    executions.add(Map.of("graphIndex",graphIndex,"parameter",value,"columns",result.getColumns(),"rows",result.getRows(),"astSameIdentity",cached==parsed,"state",state()));
                }
            }finally{((java.io.Closeable)graph).close();}
        }
        EVENTS.add(Map.of("name","parameters-and-distinct-graphs","executions",executions));
        Map<String,Object> output=new LinkedHashMap<>();output.put("mainRevision","4e328b0109e13c896b74004823fb049fcb19251a");output.put("maxHeapBytes",Runtime.getRuntime().maxMemory());output.put("maxCacheBytes",cap);output.put("performanceMeasurements",0);output.put("events",EVENTS);
        Files.writeString(Path.of(args[1]),JSON.toJson(output)+"\n",StandardOpenOption.CREATE_NEW);
        clear();
    }
}
