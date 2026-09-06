// Correctness-only full model and renderer oracle; all string leaves are UTF16
// unit arrays, preserving isolated surrogates through the evidence JSON file.
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.cli.c4.*;
import io.johnsonlee.graphite.webgraph.GraphStore;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
class UnicodeModelOracle {
 static MethodDescriptor method(String c,String n){return new MethodDescriptor(new TypeDescriptor(c,List.of()),n,List.of(),new TypeDescriptor("void",List.of()));}
 static Object units(Object value) {
  if(value instanceof String s){List<Integer> out=new ArrayList<>();for(int i=0;i<s.length();i++)out.add((int)s.charAt(i));return Map.of("$utf16",out);}
  if(value instanceof Map<?,?> m){Map<String,Object> out=new LinkedHashMap<>();for(var e:m.entrySet())out.put(e.getKey().toString(),units(e.getValue()));return out;}
  if(value instanceof Iterable<?> xs){List<Object> out=new ArrayList<>();for(Object x:xs)out.add(units(x));return out;}
  return value;
 }
 public static void main(String[] args)throws Exception{
  Path dir=Path.of(args[0]);Files.createDirectories(dir);
  DefaultGraph.Builder builder=new DefaultGraph.Builder();
  MethodDescriptor main=new MethodDescriptor(new TypeDescriptor("com.\u0130D.Application",List.of()),"main",List.of(new TypeDescriptor("java.lang.String[]",List.of())),new TypeDescriptor("void",List.of()));
  MethodDescriptor service=method("com.\u0130D.\u039f\u03a3.\u00dfService","run"),api=method("com.\u0130D.\ud801\udc28abc.Controller","post");
  builder.addMethod(main);builder.addMethod(service);builder.addMethod(api);
  int id=0;
  for(MethodDescriptor[] edge:List.of(new MethodDescriptor[]{main,api},new MethodDescriptor[]{api,service},new MethodDescriptor[]{service,method("com.vendor.Bad\ud800Name","run")},new MethodDescriptor[]{service,method("com.vendor.\u00dfeta.Library","run")},new MethodDescriptor[]{service,method("com.vendor.\ufb03le.Library","run")})){
   builder.addNode((CallSiteNode)CallSiteNode.class.getConstructors()[0].newInstance(id++,edge[0],edge[1],Integer.valueOf(1),null,List.of(),null));
  }
  builder.addClassOrigin("com.vendor.Bad\ud800Name","lib/bad\ud800name-1.0.jar");
  builder.addClassOrigin("com.vendor.\u00dfeta.Library","lib/\u00dfeta-1.0.jar");
  builder.addClassOrigin("com.vendor.\ufb03le.Library","lib/\ufb03le-1.0.jar");
  builder.addMemberAnnotation(api.getDeclaringClass().getClassName(),api.getName(),"org.springframework.web.bind.annotation.PostMapping",Map.of("path",List.of("/\u0130D")));
  GraphStore.INSTANCE.save(builder.build(),dir.resolve("store"),3,false);
  Graph graph=GraphStore.INSTANCE.loadMapped(dir.resolve("store"));
  C4ArchitectureService service4=new C4ArchitectureService();Map<String,Object> rows=new LinkedHashMap<>();
  for(String level:List.of("all","context","container","component")){
   Map<String,Object> model=service4.buildModel$explore(graph,level,Integer.MAX_VALUE);
   Map<String,Object> row=new LinkedHashMap<>();row.put("model",units(model));row.put("mermaid",units(service4.renderMermaid$explore(model)));row.put("plantuml",units(service4.renderPlantUml$explore(model)));row.put("dsl",units(service4.renderStructurizrDsl$explore(model)));rows.put(level,row);
  }
  Files.writeString(dir.resolve("expected.json"),new GsonBuilder().setPrettyPrinting().create().toJson(rows)+"\n");
 }
}
