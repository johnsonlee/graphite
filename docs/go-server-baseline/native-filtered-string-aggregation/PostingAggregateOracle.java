import com.google.gson.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import kotlin.jvm.functions.Function2;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Actual main PostingRanges.aggregate, with controlled counted-property reads. No timers. */
public final class PostingAggregateOracle {
    public static void main(String[]args)throws Exception {
        System.setProperty("graphite.webgraph.prepareCallSiteStringIndexOnLoad","lazy");
        var graph=GraphStore.INSTANCE.loadMapped(Path.of(args[0]));
        try {
            Field field=graph.getClass().getDeclaredField("stringTable");field.setAccessible(true);
            StringTable table=(StringTable)field.get(graph);
            var records=new ArrayList<Object>();
            for(int postingCount:new int[]{11,12,13})for(boolean failRaw:List.of(false,true)) {
                int[] denseIDs=new int[postingCount];for(int i=0;i<postingCount;i++)denseIDs[i]=i%2==0?1:65;
                var properties=new MappedCallSiteStringIndex.PropertyCsr[]{
                    new MappedCallSiteStringIndex.PropertyCsr(new int[]{4},new int[]{0},new int[]{1,1,3,65},false),
                    new MappedCallSiteStringIndex.PropertyCsr(new int[]{postingCount},new int[]{0},denseIDs,false)};
                var ranges=new MappedCallSiteStringIndex.PostingRanges(properties);ranges.add(0,0,4);
                var reads=new ArrayList<Object>();var work=new ArrayList<Long>();
                Function2<Integer,Integer,Integer> reader=(node,property)->{
                    reads.add(List.of(node,property));
                    if(failRaw)throw new IllegalStateException("deliberate raw read");
                    return node==3?1:0;
                };
                var record=new LinkedHashMap<String,Object>();record.put("postingCount",postingCount);record.put("failRaw",failRaw);
                try {
                    var result=ranges.aggregate(128,1,reader,table,new GraphWorkBatchConsumer(){
                        public void consume(){consume(1L);}public void consume(long units){work.add(units);}
                    });
                    record.put("count",result.getCount());record.put("values",result.getDistinctValues());
                    if(result.getCount()!=3L)throw new AssertionError("OR postings must deduplicate");
                    if(postingCount>=12&&failRaw)throw new AssertionError("sparse branch must consume raw counted field");
                }catch(IllegalStateException error){record.put("error",error.getClass().getName());record.put("message",error.getMessage());}
                record.put("reads",reads);record.put("workBatches",work);
                if(postingCount<12&&!reads.isEmpty())throw new AssertionError("dense branch read raw fields");
                if(postingCount>=12&&reads.isEmpty())throw new AssertionError("threshold boundary must choose sparse");
                records.add(record);
            }
            for(int invalid:new int[]{-1,128,Integer.MAX_VALUE}) {
                var properties=new MappedCallSiteStringIndex.PropertyCsr[]{new MappedCallSiteStringIndex.PropertyCsr(new int[]{1},new int[]{0},new int[]{invalid},false)};
                var ranges=new MappedCallSiteStringIndex.PostingRanges(properties);ranges.add(0,0,1);
                var record=new LinkedHashMap<String,Object>();record.put("invalidPostingID",invalid);
                try{ranges.aggregate(128,null,(node,property)->0,table,null);throw new AssertionError("invalid bitset index accepted");}
                catch(ArrayIndexOutOfBoundsException error){record.put("error",error.getClass().getName());record.put("message",error.getMessage());}
                records.add(record);
            }
            Files.writeString(Path.of(args[1]),new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(records)+"\n",StandardOpenOption.CREATE_NEW);
        }finally{((java.io.Closeable)graph).close();}
    }
}
