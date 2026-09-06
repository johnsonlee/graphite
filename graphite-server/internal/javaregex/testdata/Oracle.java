import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import com.google.gson.*;
public class Oracle {
 public static void main(String[] args)throws Exception{
  JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
  for(JsonElement e:cases){JsonObject c=e.getAsJsonObject();try{c.addProperty("matches",Pattern.compile(c.get("pattern").getAsString()).matcher(c.get("text").getAsString()).matches());}catch(PatternSyntaxException ex){c.addProperty("error",ex.getMessage());c.addProperty("description",ex.getDescription());c.addProperty("index",ex.getIndex());}}
  JsonObject result=new JsonObject();result.addProperty("javaVersion",System.getProperty("java.version"));result.addProperty("mainRevision","4e328b0109e13c896b74004823fb049fcb19251a");result.add("cases",cases);Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result)+"\n");
 }
}
