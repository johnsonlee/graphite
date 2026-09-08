import java.lang.reflect.*;
import java.util.*;
import com.google.gson.*;
import it.unimi.dsi.lang.MutableString;
import it.unimi.dsi.util.FrontCodedStringList;
import io.johnsonlee.graphite.graph.*;

/** Executes unchanged pinned-main bytecode; the only override counts real decode calls. */
public class BoundedStringMatcherOracle {
    static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    static final Class<?> TABLE, KEY, MATCHER;
    static final Constructor<?> TC, KC, MC;
    static final Method MATCH;
    static int assertions;
    static {
        try {
            TABLE=Class.forName("io.johnsonlee.graphite.webgraph.StringTable");
            KEY=Class.forName("io.johnsonlee.graphite.webgraph.StringPredicateKey");
            MATCHER=Class.forName("io.johnsonlee.graphite.webgraph.BoundedStringMatcher");
            TC=TABLE.getDeclaredConstructor(FrontCodedStringList.class,Map.class,byte[].class);
            KC=KEY.getDeclaredConstructor(StringValueTransform.class,StringMatchMode.class,String.class);
            MC=MATCHER.getDeclaredConstructor(TABLE,KEY,int.class);
            MATCH=MATCHER.getDeclaredMethod("matches",int.class);
            for (AccessibleObject a:List.of(TC,KC,MC,MATCH)) a.setAccessible(true);
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static class CountingList extends FrontCodedStringList {
        int reads;
        boolean failReads;
        CountingList(List<String> strings) { super(strings,4,false); }
        @Override public void get(int index,MutableString target) {
            reads++;
            if(failReads) throw new IllegalStateException("oracle backing read forbidden");
            super.get(index,target);
        }
    }
    static Object field(Object object,String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    static void require(boolean value,String name) { assertions++;if(!value)throw new AssertionError(name); }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;
    }
    static void out(Map<String,Object> m) { System.out.println(JSON.toJson(m)); }
    static Object matcher(CountingList list,int capacity,String term,StringValueTransform transform,StringMatchMode mode) throws Exception {
        return MC.newInstance(TC.newInstance(list,null,null),KC.newInstance(transform,mode,term),capacity);
    }
    static Map<String,Object> snapshot(Object m) throws Exception {
        byte[] dense=(byte[])field(m,"dense"),values=(byte[])field(m,"values");int[] keys=(int[])field(m,"keys");
        List<Object> populated=new ArrayList<>();
        if(dense!=null) { for(int i=0;i<dense.length;i++) if(dense[i]!=0)populated.add(map("sid",i,"state",dense[i])); }
        else { for(int i=0;i<keys.length;i++)if(keys[i]!=0)populated.add(map("slot",i,"key",keys[i],"state",values[i])); }
        return map("capacity",field(m,"capacity"),"stringCount",field(m,"stringCount"),"dense",dense!=null,"populated",populated);
    }
    static Map<String,Object> call(String scenario,Object m,CountingList l,int sid) throws Exception {
        Map<String,Object> r=map("scenario",scenario,"sid",sid,"readsBefore",l.reads);
        try { r.put("matched",MATCH.invoke(m,sid)); }
        catch(InvocationTargetException e) { r.put("error",e.getCause().getClass().getName());r.put("message",e.getCause().getMessage()); }
        r.put("readsAfter",l.reads);r.put("state",snapshot(m));out(r);return r;
    }
    static List<String> table(int size) {
        List<String> s=new ArrayList<>();for(int i=0;i<size;i++)s.add(i%2==0?"hit-"+i:"miss-"+i);return s;
    }
    static void cacheHistory(int size) throws Exception {
        CountingList l=new CountingList(table(size));Object m=matcher(l,4096,"hit",null,StringMatchMode.CONTAINS);
        String name=size<=4096?"dense":"hashed";
        require((Boolean)call(name+"-hit",m,l,0).get("matched"),"first hit");
        require(!(Boolean)call(name+"-miss",m,l,1).get("matched"),"first miss");
        l.failReads=true;
        require((Boolean)call(name+"-cached-hit",m,l,0).get("matched"),"cached hit no backing read");
        require(!(Boolean)call(name+"-cached-miss",m,l,1).get("matched"),"cached miss no backing read");
        require(l.reads==2,"positive and negative cache skip decoder");l.failReads=false;
        if(size>4096) {
            call("hashed-collision-4096",m,l,4096);require(l.reads==3,"collision decodes");
            call("hashed-evicted-0",m,l,0);require(l.reads==4,"evicted decodes again");
            call("hashed-unsigned-spread-65536",m,l,65536);require(l.reads==5,"high bits spread to slot1");
            int[] keys=(int[])field(m,"keys");require(keys[1]==65537,"unsigned high-bit hash slot1");
            call("hashed-evicted-miss-1",m,l,1);require(l.reads==6,"miss evicted by high bits");
        }
        for(int sid:new int[]{-1,Integer.MIN_VALUE,size,Integer.MAX_VALUE}) {
            int before=l.reads;Map<String,Object> r=call(name+"-invalid",m,l,sid);
            require(r.containsKey("error"),"invalid ID errors");
            require(l.reads==before+(size<=4096?0:1),"dense errors before decoder, hashed at decoder");
        }
    }
    static void unicode() throws Exception {
        // Java strings and real char front coding retain individual UTF-16 surrogate code units.
        Object[][] cases={
            {"ascii-lower","AbC","abc",true,true},
            {"expected-not-lowered","AbC","ABC",true,false},
            {"dotted-i-expansion","\u0130","i\u0307",true,true},
            {"final-sigma","\u039f\u03a3","\u03bf\u03c2",true,true},
            {"nonfinal-sigma","\u039f\u03a3A","\u03bf\u03c3a",true,true},
            {"supplementary-lower","\ud801\udc00","\ud801\udc28",true,true},
            {"high-surrogate-substring","a\ud83d\ude00b","\ud83d",false,true},
            {"low-surrogate-substring","a\ud83d\ude00b","\ude00",false,true},
            {"lone-surrogate","x\ud800y","\ud800",true,true},
            {"empty-needle","","",true,true},
            {"contains-miss","\u0130","I",true,false}
        };
        for(Object[] c:cases)for(int capacity:new int[]{1,4096}) {
            CountingList l=new CountingList(List.of((String)c[1],"padding"));
            StringValueTransform transform=(boolean)c[3]?StringValueTransform.LOWERCASE:null;
            Object m=matcher(l,capacity,(String)c[2],transform,StringMatchMode.CONTAINS);
            Map<String,Object> r=call("unicode-"+c[0]+"-capacity"+capacity,m,l,0);
            require(r.get("matched").equals(c[4]),"unicode result "+c[0]);
            out(map("scenario","unicode-input","name",c[0],"capacity",capacity,"actualUtf16",utf16((String)c[1]),"expectedUtf16",utf16((String)c[2]),"lower",c[3],"matched",r.get("matched")));
        }
        for(StringMatchMode mode:StringMatchMode.values()) {
            String term=mode==StringMatchMode.EQUALS?"i\u0307x":mode==StringMatchMode.ENDS_WITH?"x":"i\u0307";
            CountingList l=new CountingList(List.of("\u0130X"));Object m=matcher(l,4096,term,StringValueTransform.LOWERCASE,mode);
            require((Boolean)call("mode-"+mode,m,l,0).get("matched"),"non-CONTAINS stringMatches");
        }
    }
    static List<Integer> utf16(String s) { List<Integer> r=new ArrayList<>();for(char c:s.toCharArray())r.add((int)c);return r; }
    static void defaultCapacityBoundary() throws Exception {
        Constructor<?> defaultCtor=MATCHER.getDeclaredConstructor(TABLE,KEY,int.class,int.class,Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"));
        defaultCtor.setAccessible(true);
        for(int size:new int[]{65536,65537}) {
            CountingList l=new CountingList(table(size));
            Object m=defaultCtor.newInstance(TC.newInstance(l,null,null),KC.newInstance(null,StringMatchMode.CONTAINS,"hit"),0,4,null);
            require((int)field(m,"capacity")==65536,"actual Kotlin default constructor capacity");
            require((field(m,"dense")!=null)==(size==65536),"default dense boundary");
            String name="default-capacity-size"+size;
            require((Boolean)call(name+"-last-valid",m,l,size-1).get("matched")==((size-1)%2==0),"boundary valid result");
            l.failReads=true;
            require(!call(name+"-last-cached",m,l,size-1).containsKey("error"),"boundary cached decoder skipped");
            l.failReads=false;require(l.reads==1,"one boundary read");
            call(name+"-invalid",m,l,size);require(l.reads==(size==65536?1:2),"boundary invalid decoder timing");
            if(size==65537) {
                call(name+"-collision1",m,l,1);
                require(((int[])field(m,"keys"))[1]==2,"65536 collides with1 at defaultcapacity");
                call(name+"-collision65536",m,l,65536);
                require(((int[])field(m,"keys"))[1]==65537,"defaultcapacity replacement");
            }
        }
    }
    static void storagePolicy() throws Exception {
        Method make=Class.forName("io.johnsonlee.graphite.cypher.QueryPipelineKt").getDeclaredMethod("directStringStorageWorkConsumer",int.class,int.class,String.class,boolean.class,boolean.class,boolean.class,kotlin.jvm.functions.Function1.class);
        for(int sources:new int[]{1,2,39,40,64}) for(boolean forceSerial:new boolean[]{false,true}) for(boolean tracking:new boolean[]{false,true}) {
            long[] total={0};kotlin.jvm.functions.Function1<Long,kotlin.Unit> callback=tracking?(n)->{total[0]+=n;return kotlin.Unit.INSTANCE;}:null;
            GraphWorkConsumer c=(GraphWorkConsumer)make.invoke(null,sources,16,null,forceSerial,false,false,callback);
            boolean serial=c instanceof SerialGraphWorkBatchConsumer;
            require(serial==(forceSerial||sources>1&&sources<40),"serial default selection");
            require((c instanceof PreferredPersistedStringIndexGraphWorkBatchConsumer)==forceSerial,"forceSerial preferred persisted marker");
            require((c instanceof SplitGraphWorkBatchConsumer)==(!forceSerial&&sources>=40),"split default selection");
            ((GraphWorkBatchConsumer)c).consume(7L);require(total[0]==(tracking?7L:0L),"tracking callback");
            out(map("scenario","storage-consumer-policy","sourceCount",sources,"processors",16,"configuredGraphWorkers",null,"forceSerial",forceSerial,"preferRaw",false,"preferMappedView",false,"tracking",tracking,"class",c.getClass().getName(),"serial",serial,"parallel",c instanceof ParallelGraphWorkBatchConsumer,"split",c instanceof SplitGraphWorkBatchConsumer,"preferredPersisted",c instanceof PreferredPersistedStringIndexGraphWorkBatchConsumer));
        }
    }
    public static void main(String[] args) throws Exception {
        cacheHistory(4096);cacheHistory(65538);unicode();defaultCapacityBoundary();storagePolicy();
        for(int requested:new int[]{Integer.MIN_VALUE,-1,0,1,3,4095,4096,4097,65536}) {
            CountingList l=new CountingList(List.of("hit","miss"));Object m=matcher(l,requested,"hit",null,StringMatchMode.CONTAINS);
            require((int)field(m,"capacity")==Integer.highestOneBit(Math.max(1,requested)),"capacity normalization");
            out(map("scenario","capacity-normalization","requested",requested,"state",snapshot(m)));
        }
        Object key=KC.newInstance(null,StringMatchMode.CONTAINS,"hit");
        require(key.equals(KC.newInstance(null,StringMatchMode.CONTAINS,"hit")),"equal key");
        require(!key.equals(KC.newInstance(StringValueTransform.LOWERCASE,StringMatchMode.CONTAINS,"hit")),"transform distinguishes");
        require(!key.equals(KC.newInstance(null,StringMatchMode.EQUALS,"hit")),"mode distinguishes");
        require(!key.equals(KC.newInstance(null,StringMatchMode.CONTAINS,"miss")),"expected distinguishes");
        Set<String> names=new TreeSet<>();for(Field f:KEY.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers()))names.add(f.getName());
        require(names.equals(Set.of("transform","mode","expected")),"property absent from key");
        out(map("scenario","predicate-identity","fields",names));
        out(map("verified",true,"assertions",assertions,"performanceMeasurement",false));
    }
}
