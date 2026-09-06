// Development oracle for Java Strings containing isolated UTF16 surrogates.
import java.nio.file.*;import java.util.regex.*;import com.google.gson.*;
public class WTF8Oracle{
 static JsonArray units(String s){JsonArray a=new JsonArray();for(int i=0;i<s.length();i++)a.add((int)s.charAt(i));return a;}
 public static void main(String[]args)throws Exception{
  JsonArray input=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray(),out=new JsonArray();
  for(JsonElement e:input){JsonObject c=e.getAsJsonObject(),r=new JsonObject();String p=c.get("pattern").getAsString(),t=c.get("text").getAsString();r.add("name",c.get("name"));r.add("patternUTF16",units(p));r.add("textUTF16",units(t));try{r.addProperty("matches",Pattern.compile(p).matcher(t).matches());}catch(PatternSyntaxException x){r.add("errorUTF16",units(x.getMessage()));r.addProperty("description",x.getDescription());r.addProperty("index",x.getIndex());}out.add(r);}
  JsonObject result=new JsonObject();result.addProperty("javaVersion",System.getProperty("java.version"));result.add("cases",out);Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
 }
}
