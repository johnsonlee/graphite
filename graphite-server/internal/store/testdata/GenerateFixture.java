// Regenerate from repository root with:
// java -cp '<graphite-webgraph-jmh.jar>' \
//   graphite-server/internal/store/testdata/GenerateFixture.java \
//   graphite-server/internal/store/testdata/jvm-v3
// Uses GraphStore's actual string-table implementation and Java DataOutputStream.
import it.unimi.dsi.util.FrontCodedStringList;
import it.unimi.dsi.webgraph.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class GenerateFixture {
 static List<String> strings = new ArrayList<>(new TreeSet<>(List.of("", "Example", "Base", "int", "void", "run", "callee", "field", "x", "RED", "Color", "Color#RED", "Example#run", "Annotation", "key", "hello\0\u4e16\u754c\ud83d\ude00", "config.yml", "app.jar", "dependency.jar", "yaml", "prod")));
 static void str(DataOutputStream d,String s)throws Exception{int i=strings.indexOf(s);if(i<0)throw new Exception(s);d.writeInt(i);}
 static void method(DataOutputStream d,String name)throws Exception{str(d,"Example");str(d,name);d.writeInt(1);str(d,"int");str(d,"void");}
 static void value(DataOutputStream d)throws Exception{d.writeByte(8);d.writeInt(3);d.writeByte(0);d.writeInt(42);d.writeByte(7);str(d,"Color");str(d,"RED");d.writeByte(8);d.writeInt(2);d.writeByte(6);d.writeByte(2);str(d,"hello\0\u4e16\u754c\ud83d\ude00");}
 static void attrs(DataOutputStream d)throws Exception{d.writeInt(1);str(d,"key");value(d);}
 public static void main(String[] args)throws Exception{
 Path p=Paths.get(args[0]);Files.createDirectories(p);
 ArrayListMutableGraph graph=new ArrayListMutableGraph(31);
 graph.addArc(0,2);graph.addArc(0,4);graph.addArc(24,0);graph.addArc(24,2);
 BVGraph.store(graph.immutableView(),p.resolve("forward").toString());
 Files.write(p.resolve("graph.labels"),new byte[]{0,8,1,11});
 try(DataOutputStream r=new DataOutputStream(Files.newOutputStream(p.resolve("graph.resources")))){
 r.writeInt(0x47525201);r.writeInt(3);
 String[][] entries={{"config.yml","app.jar","key: \u4e16\u754c\n"},{"docs/readme.txt","dependency.jar","hello\n"},{"application.properties","app.jar","enabled=true\n"}};
 for(String[] entry:entries)for(String value:entry){byte[] data=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);r.writeInt(data.length);r.write(data);}
 }
 try(ObjectOutputStream o=new ObjectOutputStream(Files.newOutputStream(p.resolve("graph.strings")))){o.writeObject(new FrontCodedStringList(strings.iterator(),8,false));}
 try(DataOutputStream d=new DataOutputStream(Files.newOutputStream(p.resolve("graph.nodedata")))){
 d.writeInt(0x47524e03);d.writeInt(16);
 for(int tag=0;tag<16;tag++){
 d.writeInt(tag*2);d.writeByte(tag);
 switch(tag){
 case 0:d.writeInt(-17);break;
 case 1:str(d,"hello\0\u4e16\u754c\ud83d\ude00");break;
 case 2:d.writeLong(9223372036854775806L);break;
 case 3:d.writeFloat(1.25f);break;
 case 4:d.writeDouble(-2.75);break;
 case 5:d.writeBoolean(true);break;
 case 6:break;
 case 7:str(d,"Color");str(d,"RED");d.writeInt(1);value(d);break;
 case 8:str(d,"x");str(d,"int");method(d,"run");break;
 case 9:str(d,"Example");str(d,"field");str(d,"int");d.writeBoolean(true);break;
 case 10:d.writeInt(2);str(d,"int");method(d,"run");break;
 case 11:method(d,"run");d.writeBoolean(true);str(d,"int");break;
 case 12:method(d,"run");method(d,"callee");d.writeInt(37);d.writeInt(-1);d.writeInt(2);d.writeInt(0);d.writeInt(2);break;
 case 13:str(d,"Annotation");str(d,"Example");str(d,"run");attrs(d);break;
 case 14:str(d,"config.yml");str(d,"key");value(d);str(d,"yaml");d.writeBoolean(true);str(d,"prod");break;
 case 15:str(d,"config.yml");str(d,"app.jar");str(d,"yaml");d.writeBoolean(false);break;
 }
 }
 }
 try(DataOutputStream d=new DataOutputStream(Files.newOutputStream(p.resolve("graph.metadata")))){
 d.writeInt(0x47524d03);d.writeInt(1);method(d,"run");
 d.writeInt(1);str(d,"Example");d.writeInt(1);str(d,"Base");
 d.writeInt(1);str(d,"Base");d.writeInt(1);str(d,"Example");
 d.writeInt(1);str(d,"Color#RED");d.writeInt(1);value(d);
 d.writeInt(1);str(d,"Example");str(d,"app.jar");
 d.writeInt(1);str(d,"app.jar");d.writeInt(1);str(d,"dependency.jar");d.writeInt(7);
 d.writeInt(1);str(d,"Example#run");d.writeInt(1);str(d,"Annotation");attrs(d);
 d.writeInt(1);d.writeInt(24);method(d,"run");d.writeInt(2);d.writeInt(0);d.writeInt(1);d.writeInt(2);d.writeInt(1);d.writeInt(4);
 }
 try(DataOutputStream d=new DataOutputStream(Files.newOutputStream(p.resolve("graph.comparisons")))){d.writeInt(0x47524303);d.writeInt(1);d.writeLong((24L<<32)|2);d.writeInt(2);d.writeInt(0);}
 }
}
