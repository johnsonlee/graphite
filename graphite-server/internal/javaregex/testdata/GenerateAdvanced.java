// Development-only Java17 oracle data generator. No JDK source is copied.
import java.util.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.text.Normalizer;
import java.util.zip.GZIPOutputStream;
import com.google.gson.Gson;
public class GenerateAdvanced {
 public static void main(String[]args)throws Exception{
  Class<?> grapheme=Class.forName("java.util.regex.Grapheme");Method getType=grapheme.getDeclaredMethod("getType",int.class);getType.setAccessible(true);
  Class<?> unicode=Class.forName("jdk.internal.icu.lang.UCharacter");Method combining=unicode.getMethod("getCombiningClass",int.class);
  Map<String,List<Integer>> ranges=new TreeMap<>();Map<Integer,String> decompositions=new TreeMap<>();Map<String,Integer> compositions=new TreeMap<>(),names=new TreeMap<>();Map<Integer,Integer> combiningClasses=new TreeMap<>();
  for(int cp=0;cp<=0x10ffff;cp++){
   int type=(Integer)getType.invoke(null,cp);String key=Integer.toString(type);List<Integer>a=ranges.computeIfAbsent(key,k->new ArrayList<>());int n=a.size();if(n>0&&a.get(n-1)==cp-1)a.set(n-1,cp);else{a.add(cp);a.add(cp);}
   int ccc=(Integer)combining.invoke(null,cp);if(ccc!=0)combiningClasses.put(cp,ccc);
   String text=new String(Character.toChars(cp));String nfd=Normalizer.normalize(text,Normalizer.Form.NFD);if(!nfd.equals(text)){decompositions.put(cp,nfd);String nfc=Normalizer.normalize(text,Normalizer.Form.NFC);if(nfc.codePointCount(0,nfc.length())==1)compositions.put(nfd,nfc.codePointAt(0));}
   String name=Character.getName(cp);if(name!=null)names.put(name,cp);
  }
  Map<String,Object> out=new LinkedHashMap<>();out.put("javaVersion",System.getProperty("java.version"));out.put("graphemeRanges",ranges);out.put("decompositions",decompositions);out.put("compositions",compositions);out.put("combiningClasses",combiningClasses);
  Files.writeString(Path.of(args[0]),new Gson().toJson(out)+"\n");
  try(GZIPOutputStream stream=new GZIPOutputStream(Files.newOutputStream(Path.of(args[1])))){stream.write((new Gson().toJson(names)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
}
