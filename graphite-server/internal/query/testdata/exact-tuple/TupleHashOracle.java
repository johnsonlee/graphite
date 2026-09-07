import java.util.*;import java.nio.file.*;import com.google.gson.*;
public class TupleHashOracle{
 public static void main(String[]args)throws Exception{
  Class<?> c=Class.forName("io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndexKt");var hash=c.getDeclaredMethod("callSiteProjectionTupleHash",int.class,int.class,int.class,int.class);hash.setAccessible(true);var slot=c.getDeclaredMethod("callSiteProjectionTupleSlot",long.class,int.class);slot.setAccessible(true);
  String[][] inputs={{"Aa","BB","","\ud800"},{"BB","Aa","","\ud800"},{"\u0000","","\udfff","😀"},{"a","b","c","d"},{"\uffff\uffff\uffff\uffff","longlonglonglong","\ud800\udc00","\ud800"},{"","","",""},{"𐀀","😀","日本語","İΣ"}};var out=new ArrayList<Object>();for(String[] values:inputs){int[] hs=Arrays.stream(values).mapToInt(String::hashCode).toArray();long h=(long)hash.invoke(null,hs[0],hs[1],hs[2],hs[3]);var row=new LinkedHashMap<String,Object>();row.put("units",Arrays.stream(values).map(v->v.chars().boxed().toList()).toList());row.put("hashes",hs);row.put("hash",Long.toUnsignedString(h));row.put("slot",slot.invoke(null,h,8192));out.add(row);}Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().create().toJson(out)+"\n");
 }
}
