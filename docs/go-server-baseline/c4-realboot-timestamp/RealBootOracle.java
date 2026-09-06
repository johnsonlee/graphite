import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.input.*;
import io.johnsonlee.graphite.sootup.JavaProjectLoader;
import io.johnsonlee.graphite.webgraph.GraphStore;
import io.johnsonlee.graphite.cli.c4.*;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;
class RealBootOracle {
 static final com.google.gson.Gson GSON=new GsonBuilder().setPrettyPrinting().create();
 static void stats(Path file,Graph graph)throws Exception {
  Map<String,Object> m=new LinkedHashMap<>();m.put("nodes",graph.nodeCount(Node.class));m.put("edges",graph.edgeCount());m.put("methods",graph.methodCount());m.put("callSites",graph.nodeCount(CallSiteNode.class));m.put("classOrigins",graph.classOrigins());m.put("manifest",SubjectDetector.INSTANCE.readManifestMetadata(graph));m.put("resources",kotlin.sequences.SequencesKt.toList(graph.getResources().list("**")));Files.writeString(file,GSON.toJson(m)+"\n");
 }
 public static void main(String[] args)throws Exception {
  Path input=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);
  // Parse a real published archive; select the app and its actual nested task
  // starter, leaving unrelated framework/database bytecode out of this scope.
  LoaderConfig config=new LoaderConfig(true,List.of("org.springframework.cloud.task.app.timestamp"),List.of(),List.of("spring-cloud-starter-task-timestamp-*.jar"),true,true,true,CallGraphAlgorithm.CHA,null,s->{System.err.println(s);return kotlin.Unit.INSTANCE;});
  Graph built=new JavaProjectLoader(config,true).load(input);
  stats(out.resolve("built-stats.json"),built);
  GraphStore.INSTANCE.save(built,out.resolve("store"),3,false);
  for(GraphStore.LoadMode mode:List.of(GraphStore.LoadMode.MAPPED,GraphStore.LoadMode.EAGER)){
   Graph graph=GraphStore.INSTANCE.load(out.resolve("store"),mode);Path dir=out.resolve(mode.name());Files.createDirectories(dir);stats(dir.resolve("stats.json"),graph);
   C4ArchitectureService service=new C4ArchitectureService();
   for(String level:List.of("all","context","container","component")){
    Map<String,Object> model=service.buildModel$explore(graph,level,Integer.MAX_VALUE);
    Files.writeString(dir.resolve(level+".json"),GSON.toJson(model)+"\n");
    Files.writeString(dir.resolve(level+".mermaid"),service.renderMermaid$explore(model));
    Files.writeString(dir.resolve(level+".plantuml"),service.renderPlantUml$explore(model));
    Files.writeString(dir.resolve(level+".dsl"),service.renderStructurizrDsl$explore(model));
   }
  }
 }
}
