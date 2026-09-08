import java.lang.reflect.*;
import java.util.*;
import io.johnsonlee.graphite.graph.*;

/** Calls unchanged pinned-main cache bytecode. No performance measurements. */
public class RawProjectionCacheOracle {
    static final Class<?> CACHE, KEY;
    static final Constructor<?> CACHE_CTOR, KEY_CTOR;
    static final Method GET, PUT, CLEAR, SIZE;
    static final Field MATCHES;
    static int assertions;
    static {
        try {
            CACHE=Class.forName("io.johnsonlee.graphite.webgraph.RawProjectionMatches");
            KEY=Class.forName("io.johnsonlee.graphite.webgraph.RawProjectionMatchKey");
            CACHE_CTOR=CACHE.getDeclaredConstructor(); KEY_CTOR=KEY.getDeclaredConstructor(List.class,int.class);
            GET=CACHE.getDeclaredMethod("get",KEY); PUT=CACHE.getDeclaredMethod("put",KEY,int[].class);
            CLEAR=CACHE.getDeclaredMethod("clear"); SIZE=CACHE.getDeclaredMethod("size");
            MATCHES=CACHE.getDeclaredField("matches");
            for (AccessibleObject a:List.of(CACHE_CTOR,KEY_CTOR,GET,PUT,CLEAR,SIZE,MATCHES)) a.setAccessible(true);
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    static StringPropertyPredicate pred(String property,String term,StringValueTransform transform,StringMatchMode mode) {
        return new StringPropertyPredicate(property,transform,mode,term);
    }
    static Object key(int i) throws Exception { return key(List.of(pred("caller_name","term"+i,null,StringMatchMode.CONTAINS)),1); }
    static Object key(List<StringPropertyPredicate> p,int limit) throws Exception { return KEY_CTOR.newInstance(p,limit); }
    static Object cache() throws Exception { return CACHE_CTOR.newInstance(); }
    static void put(Object c,Object k,int... ids) throws Exception { PUT.invoke(c,k,ids); }
    static int[] get(Object c,Object k) throws Exception { return (int[]) GET.invoke(c,k); }
    static void require(boolean yes,String name) { assertions++; if(!yes) throw new AssertionError(name); }
    @SuppressWarnings("unchecked")
    static Map<Object,int[]> entries(Object c) throws Exception { return (Map<Object,int[]>) MATCHES.get(c); }
    static Object filled() throws Exception { Object c=cache(); for(int i=1;i<=16;i++)put(c,key(i),i); return c; }
    static void output(String name,Object c) throws Exception {
        System.out.print("{\"scenario\":\""+name+"\",\"size\":"+SIZE.invoke(c)+",\"entriesInLruOrder\":[");
        boolean comma=false;
        for(var e:entries(c).entrySet()) {
            if(comma)System.out.print(","); comma=true;
            Method ps=KEY.getDeclaredMethod("getPredicates"); ps.setAccessible(true);
            Method lim=KEY.getDeclaredMethod("getLimit"); lim.setAccessible(true);
            String ks=e.getKey().toString().replace("\\","\\\\").replace("\"","\\\"");
            System.out.print("{\"key\":\""+ks+"\",\"ids\":"+Arrays.toString(e.getValue())+"}");
        }
        System.out.println("]}");
    }
    public static void main(String[] args) throws Exception {
        Object c=filled(); require((int)SIZE.invoke(c)==16,"capacity before insert");
        put(c,key(17),17); require((int)SIZE.invoke(c)==16,"capacity stays16");
        require(!entries(c).containsKey(key(1))&&entries(c).containsKey(key(2)),"eldest1 evicted"); output("capacity16",c);
        c=filled(); require(Arrays.equals(get(c,key(1)),new int[]{1}),"hit returns IDs");
        put(c,key(17),17);require(entries(c).containsKey(key(1))&&!entries(c).containsKey(key(2)),"get1 promotes evicts2");output("get-promotes",c);
        c=filled();put(c,key(1),999);require(Arrays.equals(entries(c).entrySet().iterator().next().getValue(),new int[]{1}),"duplicate keeps original IDs");
        put(c,key(17),17);require(!entries(c).containsKey(key(1))&&entries(c).containsKey(key(2)),"duplicate put does not promote");output("duplicate-put-no-replace-no-promote",c);
        c=cache();put(c,key(1));require(get(c,key(1))!=null&&get(c,key(1)).length==0,"empty entry distinguishable from miss");
        require(get(c,key(2))==null,"missing null");require((int)SIZE.invoke(c)==1,"empty counted");output("empty-entry",c);
        CLEAR.invoke(c);require((int)SIZE.invoke(c)==0&&get(c,key(1))==null,"clear removes all");output("clear",c);
        var a=pred("caller_name","term",null,StringMatchMode.CONTAINS);
        var b=pred("callee_name","term",null,StringMatchMode.CONTAINS);
        Object base=key(List.of(a,b),10);
        require(base.equals(key(List.of(a,b),10)),"value equal keys");
        require(!base.equals(key(List.of(b,a),10)),"predicate order matters");
        require(!base.equals(key(List.of(a,b),11)),"limit matters");
        require(!key(List.of(a),10).equals(key(List.of(b),10)),"property matters");
        require(!key(List.of(a),10).equals(key(List.of(pred("caller_name","term",StringValueTransform.LOWERCASE,StringMatchMode.CONTAINS)),10)),"transform matters");
        require(!key(List.of(a),10).equals(key(List.of(pred("caller_name","term",null,StringMatchMode.EQUALS)),10)),"mode matters");
        require(!key(List.of(a),10).equals(key(List.of(pred("caller_name","other",null,StringMatchMode.CONTAINS)),10)),"expected matters");
        require(KEY.getDeclaredFields().length==2,"only predicates and limit stored, projection excluded");
        c=cache();put(c,base,7,9);require(Arrays.equals(get(c,key(List.of(a,b),10)),new int[]{7,9}),"equivalent key lookup");output("value-key",c);
        System.out.println("{\"verified\":true,\"assertions\":"+assertions+",\"scope\":\"unchanged JVM raw cache primitive; no graph query or timing assertions\"}");
    }
}
