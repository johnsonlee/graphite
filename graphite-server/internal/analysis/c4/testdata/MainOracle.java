// Correctness-only oracle. Based on main's C4InferenceTest.checkoutFixture.
// Run with main 4e328b0's jar; output complete workspace + all renderer goldens.
import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.cli.c4.*;
import io.johnsonlee.graphite.webgraph.GraphStore;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;

class MainOracle {
 static MethodDescriptor method(String c,String n){return new MethodDescriptor(new TypeDescriptor(c,List.of()),n,List.of(),new TypeDescriptor("void",List.of()));}
 static CallSiteNode call(int id,MethodDescriptor a,MethodDescriptor b)throws Exception{return (CallSiteNode)CallSiteNode.class.getConstructors()[0].newInstance(id,a,b,Integer.valueOf(1),null,List.of(),null);}
 static void emit(Path output,String name,Graph graph)throws Exception{emit(output,name,graph,Integer.MAX_VALUE);}
 static void emit(Path output,String name,Graph graph,int limit)throws Exception{
  Path dir=output.resolve(name);Files.createDirectories(dir);
  GraphStore.INSTANCE.save(graph,dir.resolve("store"),3,false);
  C4ArchitectureService service=new C4ArchitectureService();
  for(String level:List.of("all","context","container","component")){
   Map<String,Object> model=service.buildModel$explore(graph,level,limit);
   Files.writeString(dir.resolve(level+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(model)+"\n");
   Files.writeString(dir.resolve(level+".mermaid"),service.renderMermaid$explore(model));
   Files.writeString(dir.resolve(level+".plantuml"),service.renderPlantUml$explore(model));
   Files.writeString(dir.resolve(level+".dsl"),service.renderStructurizrDsl$explore(model));
  }
 }
 public static void main(String[] args)throws Exception{
  Path output=Path.of(args[0]);
  MethodDescriptor main=new MethodDescriptor(new TypeDescriptor("com.acme.checkout.Application",List.of()),"main",List.of(new TypeDescriptor("java.lang.String[]",List.of())),new TypeDescriptor("void",List.of()));
  MethodDescriptor api=method("com.acme.checkout.api.CheckoutController","submit"),service=method("com.acme.checkout.service.CheckoutService","authorize"),repository=method("com.acme.checkout.repository.OrderRepository","save"),payment=method("com.partner.payment.PaymentGateway","charge"),postgres=method("org.postgresql.Driver","connect"),runtime=new MethodDescriptor(new TypeDescriptor("java.util.List",List.of()),"size",List.of(),new TypeDescriptor("int",List.of()));
  DefaultGraph.Builder builder=new DefaultGraph.Builder();
  for(MethodDescriptor m:List.of(main,api,service,repository))builder.addMethod(m);
  int id=0;for(MethodDescriptor[] edge:List.of(new MethodDescriptor[]{main,api},new MethodDescriptor[]{api,service},new MethodDescriptor[]{service,repository},new MethodDescriptor[]{service,payment},new MethodDescriptor[]{repository,postgres},new MethodDescriptor[]{repository,runtime}))builder.addNode(call(id++,edge[0],edge[1]));
  builder.addClassOrigin("org.postgresql.Driver","lib/postgresql-42.7.3.jar");
  builder.addMemberAnnotation(api.getDeclaringClass().getClassName(),api.getName(),"org.springframework.web.bind.annotation.PostMapping",Map.of("path",List.of("/checkout")));
  emit(output,"checkout",builder.build());
  emit(output,"checkout-limit-0",builder.build(),0);emit(output,"checkout-limit-1",builder.build(),1);
  MethodDescriptor lucene=method("org.apache.lucene.Index","search"),analyzer=method("org.apache.lucene.analysis.Analyzer","analyze"),lang=method("org.apache.commons.lang3.StringUtils","trim"),io=method("org.apache.commons.io.IOUtils","copy");
  for(MethodDescriptor m:List.of(lucene,analyzer,lang,io))builder.addNode(call(id++,service,m));
  builder.addClassOrigin(lucene.getDeclaringClass().getClassName(),"lib/lucene-core-9.12.0.jar");builder.addClassOrigin(analyzer.getDeclaringClass().getClassName(),"lib/lucene-analysis-9.12.0.jar");builder.addClassOrigin(lang.getDeclaringClass().getClassName(),"lib/commons-lang3-3.14.0.jar");builder.addClassOrigin(io.getDeclaringClass().getClassName(),"lib/commons-io-2.15.0.jar");
  builder.addArtifactDependency("lucene-core-9.12.0","commons-lang3-3.14.0",3);builder.addArtifactDependency("lucene-analysis-9.12.0","commons-io-2.15.0",3);builder.addArtifactDependency("commons-io-2.15.0","postgresql-42.7.3",2);
  MethodDescriptor zeta=method("com.vendor.zeta.Library","run"),alpha=method("com.vendor.alpha.Library","run");
  builder.addNode(call(id++,service,zeta));builder.addNode(call(id++,service,alpha));
  builder.addClassOrigin(zeta.getDeclaringClass().getClassName(),"lib/zeta-lib-1.0.jar");builder.addClassOrigin(alpha.getDeclaringClass().getClassName(),"lib/alpha-lib-1.0.jar");
  // Deliberately reverse lexical order to exercise persisted outer and inner ties.
  builder.addArtifactDependency("zeta-lib-1.0","postgresql-42.7.3",2);builder.addArtifactDependency("zeta-lib-1.0","lucene-core-9.12.0",2);builder.addArtifactDependency("alpha-lib-1.0","postgresql-42.7.3",2);
  emit(output,"artifact-families",builder.build());
  for(int i=0;i<24;i++){builder.addNode(call(id++,service,method(String.format("com.partner.service%02d.Gateway",i),"invoke")));builder.addMethod(method(String.format("com.acme.checkout.island%02d.Capability",i),"work"));}
  emit(output,"dense",builder.build());
  DefaultGraph.Builder library=new DefaultGraph.Builder();library.addMethod(api);library.addNode(call(0,api,runtime));emit(output,"library",library.build());
 }
}
