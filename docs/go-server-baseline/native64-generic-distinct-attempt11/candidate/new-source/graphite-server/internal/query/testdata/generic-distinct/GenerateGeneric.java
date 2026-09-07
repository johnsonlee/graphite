import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.*;
import io.johnsonlee.graphite.webgraph.*;
import java.nio.file.*;
import java.util.*;
public class GenerateGeneric {
 public static void main(String[] args)throws Exception {
  Object[] values={Integer.valueOf(1),Long.valueOf(1),Float.valueOf(1),Double.valueOf(1),Float.valueOf(0),Float.valueOf(-0.0f),Double.valueOf(0),Double.valueOf(-0.0d),List.of(1),List.of(1L),new EnumValueReference("app.Level","ONE"),new EnumValueReference("app.Level","ONE"),null,"ÉΣ",true,false,List.of(new EnumValueReference("app.Level","ONE")),List.of("app.Level.ONE")};
  var builder=new DefaultGraph.Builder();var constructor=AnnotationNode.class.getConstructors()[0];
  for(int i=0;i<values.length;i++){Map<String,Object> m=new LinkedHashMap<>();m.put("caller_class","example.Emitter");m.put("callee_class","example.Target");m.put("value",values[i]);m.put("number",i);m.put("arbitrary!",values[i]);builder.addNode((Node)constructor.newInstance(i,"entry","example.Annotation","member",m,null));}
  GraphStore.INSTANCE.save(builder.build(),Path.of(args[0]),3,true);
 }
}
