import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import com.google.gson.*;
public class GsonOracle{
 static JsonArray units(String s){JsonArray a=new JsonArray();for(int i=0;i<s.length();i++)a.add((int)s.charAt(i));return a;}
 static JsonObject describe(JsonElement value){JsonObject d=new JsonObject();
  if(value.isJsonNull()){d.addProperty("kind","null");return d;}
  if(value.isJsonPrimitive()){JsonPrimitive p=value.getAsJsonPrimitive();if(p.isString()){d.addProperty("kind","string");d.add("units",units(p.getAsString()));}else{d.addProperty("kind",p.isBoolean()?"boolean":"number");d.addProperty("text",p.getAsString());}return d;}
  if(value.isJsonArray()){d.addProperty("kind","array");JsonArray a=new JsonArray();for(JsonElement item:value.getAsJsonArray())a.add(describe(item));d.add("items",a);return d;}
  d.addProperty("kind","object");JsonArray a=new JsonArray();for(var member:value.getAsJsonObject().entrySet()){JsonObject m=new JsonObject();m.add("nameUnits",units(member.getKey()));m.add("value",describe(member.getValue()));a.add(m);}d.add("members",a);return d;
 }
 public static void main(String[]args)throws Exception{JsonArray cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();for(JsonElement e:cases){JsonObject c=e.getAsJsonObject();String body=new String(Base64.getDecoder().decode(c.get("bodyBase64").getAsString()),StandardCharsets.UTF_8);try{c.add("expected",describe(JsonParser.parseString(body)));}catch(RuntimeException ex){c.addProperty("error",ex.getMessage());c.addProperty("errorClass",ex.getClass().getName());}}
  JsonObject root=new JsonObject();root.addProperty("gsonVersion","2.11.0");root.addProperty("javaVersion",System.getProperty("java.version"));root.add("cases",cases);Files.writeString(Path.of(args[1]),new GsonBuilder().setPrettyPrinting().create().toJson(root)+"\n");}
}
