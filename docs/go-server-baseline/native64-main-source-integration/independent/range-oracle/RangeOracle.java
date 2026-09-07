import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import com.google.gson.*;
import kotlin.jvm.functions.Function1;
import kotlin.sequences.Sequence;
import io.johnsonlee.graphite.webgraph.*;
import io.johnsonlee.graphite.graph.*;
public class RangeOracle {
 static final List<Integer> reads=new ArrayList<>();
 static int fail=-1;
 static List<Map<String,Object>> events=new ArrayList<>();
 static void record(String stage,Object value){var m=new LinkedHashMap<String,Object>();m.put("stage",stage);m.put("reads",new ArrayList<>(reads));m.put("value",value);events.add(m);reads.clear();}
 static MappedCallSiteStringIndexView create()throws Exception{
  IntBuffer[] strings=new IntBuffer[4],ends=new IntBuffer[4],nodes=new IntBuffer[4];
  for(int i=0;i<4;i++){strings[i]=IntBuffer.wrap(new int[]{0});ends[i]=IntBuffer.wrap(new int[]{3});nodes[i]=IntBuffer.wrap(new int[]{10,20,30});}
  Constructor<?> ctor=Arrays.stream(MappedCallSiteStringIndexView.class.getDeclaredConstructors()).filter(c->c.getParameterCount()==8).findFirst().get();ctor.setAccessible(true);
  StringTable table=StringTable.Companion.load(Path.of("/tmp/graphite-main-source-independent-review/fixture"));
  Function1<Integer,Long> order=id->{reads.add(id);if(id==fail)throw new IllegalStateException("ORDER_"+id);return (long)id;};
  return (MappedCallSiteStringIndexView)ctor.newInstance(strings,ends,nodes,LongBuffer.allocate(0),3,table.size(),table,order);
 }
 static void run(MappedCallSiteStringIndexView v,String name){
  try{
   Sequence<Integer> s=v.matchingNodeIds(List.of(new StringPropertyPredicate("caller_class",null,StringMatchMode.EQUALS,"ignored")),List.of(new int[]{0}),null);
   record(name+"/created",v.validatedPostingRangeCount$webgraph());
   Iterator<Integer> it=s.iterator();
   for(int n=0;;n++){boolean has=it.hasNext();record(name+"/hasNext"+n,has);if(!has)break;record(name+"/next"+n,it.next());}
  }catch(Throwable e){record(name+"/error",Map.of("class",e.getClass().getSimpleName(),"message",e.getMessage()));}
 }
 public static void main(String[]args)throws Exception{
  try(var v=create()){run(v,"cold");run(v,"hot");fail=30;run(v,"hot-fail-last");}
  try(var v=create()){run(v,"cold-fail-last");}
  System.out.println(new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(events));
 }
}
