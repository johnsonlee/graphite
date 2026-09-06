import it.unimi.dsi.util.FrontCodedStringList;import it.unimi.dsi.fastutil.io.BinIO;import java.nio.file.*;import java.util.*;import java.security.*;import com.google.gson.Gson;
class StringFormats {
 public static void main(String[] args)throws Exception{
  Path p=Path.of(args[0]);String kind=args[1];List<String> values;
  if(kind.equals("surrogates"))values=List.of("\ud800","\udc00","A\ud800B","\ud83d\ude00");
  else if(kind.equals("long-prefix"))values=List.of("A".repeat(40000),"A".repeat(40000)+"B","A".repeat(40001));
  else values=List.of("","hello\0world","\u4e16\u754c","\ud83d\ude00","\u00ff","\uffff");
  int ratio=kind.equals("ratio1")?1:kind.equals("ratio128")?128:8;
  BinIO.storeObject(new FrontCodedStringList(values.iterator(),ratio,false),p.toString());
  var list=(FrontCodedStringList)BinIO.loadObject(p.toString());MessageDigest h=MessageDigest.getInstance("SHA-256");
  for(int i=0;i<list.size();i++){String s=list.get(i).toString();int n=s.length();h.update(new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n});for(int j=0;j<n;j++){char c=s.charAt(j);h.update((byte)(c>>>8));h.update((byte)c);}}
  System.out.println(new Gson().toJson(Map.of("count",list.size(),"utf16SHA256",HexFormat.of().formatHex(h.digest()))));
 }
}
