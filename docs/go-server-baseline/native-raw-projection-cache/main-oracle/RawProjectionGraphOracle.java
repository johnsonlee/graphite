import com.google.gson.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.webgraph.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Directly invokes original private raw provider on cloned correctness fixtures. */
public class RawProjectionGraphOracle {
    static final Gson JSON=new GsonBuilder().serializeNulls().create();
    static int assertions;
    static void require(boolean ok,String what){assertions++;if(!ok)throw new AssertionError(what);}
    static Method method(Object g,String name,Class<?>... types)throws Exception{var m=g.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m;}
    static Object cache(Object g)throws Exception {var f=g.getClass().getDeclaredField("rawProjectionMatches");f.setAccessible(true);return f.get(g);}
    static List<Object> state(Object g)throws Exception {
        var out=new ArrayList<Object>();
        for(var e:RawProjectionCacheOracle.entries(cache(g)).entrySet())out.add(Map.of("key",e.getKey().toString(),"ids",e.getValue()));
        return out;
    }
    static List<?> query(Object g,String name,String term,List<String> properties,int limit)throws Exception {
        var predicates=List.of(new StringPropertyPredicate("caller_name",null,StringMatchMode.CONTAINS,term));
        long[] work={0};var before=state(g);
        Object rows=method(g,"rawCallSiteStringProjection",List.class,List.class,int.class,GraphWorkConsumer.class).invoke(g,predicates,properties,limit,(GraphWorkConsumer)()->work[0]++);
        var response=new LinkedHashMap<String,Object>();response.put("case",name);response.put("term",term);response.put("properties",properties);response.put("limit",limit);response.put("before",before);response.put("rows",rows);response.put("work",work[0]);response.put("after",state(g));
        response.put("rawMatchCount",method(g,"rawStringMatchStateCount$webgraph").invoke(g));
        System.out.println(JSON.toJson(response));return (List<?>)rows;
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        Object clean=GraphStore.INSTANCE.loadMapped(Path.of(args[0]));
        try {
            long cleanCount=((Graph)clean).nodeCount(CallSiteNode.class);require(cleanCount>1&&cleanCount<=1024,"clean count within exhausted probe bound");
            System.out.println(JSON.toJson(Map.of("fixture","clean","callSiteCount",cleanCount)));
            var a=query(clean,"clean-full-hit","",List.of("caller_name","callee_name"),2);require(a.size()==2,"full hit two");
            var ids=RawProjectionCacheOracle.entries(cache(clean)).values().iterator().next();
            var b=query(clean,"clean-reproject","",List.of("callee_name","caller_name"),2);require(b.size()==2,"reproject two");
            require(RawProjectionCacheOracle.entries(cache(clean)).size()==1,"reproject reuses key");
            require(RawProjectionCacheOracle.entries(cache(clean)).values().iterator().next()==ids,"reproject same cached ID array");
            for(int i=0;i<2;i++){var av=((StringPropertyProjectionRow)a.get(i)).getValues();var bv=((StringPropertyProjectionRow)b.get(i)).getValues();require(av.get(0).equals(bv.get(1))&&av.get(1).equals(bv.get(0)),"reproject correct columns");}
            // The minimum probe64 exhausts this four-callsite fixture.
            var empty=query(clean,"clean-empty-exhausted","NeverPresentRawProjectionControl",List.of("caller_name"),1);
            require(empty!=null&&empty.isEmpty()&&state(clean).size()==2,"exhausted empty cached");
            method(clean,"releaseStringPropertyDisjunctionCache").invoke(clean);require(state(clean).size()==2,"ordinary retained release preserves raw cache");
            query(clean,"empty-hit-after-release","NeverPresentRawProjectionControl",List.of("callee_name"),1);
            method(clean,"clearStringPropertyIndexes$webgraph").invoke(clean);require(state(clean).isEmpty(),"clear empties rawcache");
            query(clean,"refill-after-clear","",List.of("caller_name"),2);
        } finally {((java.io.Closeable)clean).close();}
        require(state(clean).isEmpty(),"close empties rawcache");
        Object split=GraphStore.INSTANCE.loadMapped(Path.of(args[1]));
        try {
            long count=((Graph)split).nodeCount(CallSiteNode.class);require(count>64,"split fixture more than bounded probe");
            System.out.println(JSON.toJson(Map.of("fixture","split-clean","callSiteCount",count)));
            require(query(split,"split-incomplete-miss","NeverPresentRawProjectionControl",List.of("caller_name"),1)==null,"incomplete returns null");
            require(state(split).isEmpty(),"incomplete not cached");
            Thread.currentThread().interrupt();
            try {
                query(split,"interrupted-before-first-node","",List.of("caller_name"),2);
                throw new AssertionError("interrupted scan should fail");
            } catch(InvocationTargetException e) {
                require(e.getCause() instanceof java.util.concurrent.CancellationException,"interrupt classification");
                require(state(split).isEmpty(),"interrupted scan does not publish");
                System.out.println(JSON.toJson(Map.of("case","interrupted-before-first-node","error",e.getCause().getClass().getName(),"message",e.getCause().getMessage(),"after",state(split))));
            } finally {Thread.interrupted();}
            require(query(split,"split-hit","",List.of("caller_name"),2).size()==2,"split early complete");
            require(state(split).size()==1,"complete cached");
        } finally {((java.io.Closeable)split).close();}
        System.out.println(JSON.toJson(Map.of("verified",true,"assertions",assertions,"scope","private provider correctness and cache lifetime, no planner or timing assertion")));
    }
}
