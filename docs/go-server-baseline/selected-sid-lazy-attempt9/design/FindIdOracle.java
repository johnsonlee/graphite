import com.google.gson.*;
import it.unimi.dsi.util.FrontCodedStringList;
import it.unimi.dsi.fastutil.io.BinIO;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public class FindIdOracle {
 public static void main(String[]args)throws Exception{
  Class<?> type=Class.forName("io.johnsonlee.graphite.webgraph.StringTable");Object companion=type.getField("Companion").get(null);Method load=companion.getClass().getMethod("load",Path.class);Method find=Arrays.stream(type.getDeclaredMethods()).filter(m->m.getName().startsWith("findId")).findFirst().orElseThrow();
  List<List<String>> cases=List.of(List.of("a","a","a"),List.of("b","a"),List.of("","?","a","\ud800","\ud83d\ude00","\ue000"));
  for(int i=0;i<cases.size();i++){
   List<String> strings=cases.get(i);Path dir=Path.of(args[0],"table-"+i);Files.createDirectories(dir);BinIO.storeObject(new FrontCodedStringList(strings.iterator(),4,false),dir.resolve("graph.strings").toString());Object table=load.invoke(companion,dir);
   List<Map<String,Object>> hits=new ArrayList<>();for(String text:strings){hits.add(Map.of("text",text,"mainSID",find.invoke(table,text),"nativeFirstSID",strings.indexOf(text)));}
   String json=new GsonBuilder().disableHtmlEscaping().create().toJson(Map.of("case",i,"strings",strings,"hits",hits));StringBuilder ascii=new StringBuilder();for(char c:json.toCharArray()){if(c>127)ascii.append(String.format("\\u%04x",(int)c));else ascii.append(c);}System.out.println(ascii);
  }
 }
}
