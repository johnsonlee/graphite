import it.unimi.dsi.webgraph.*;import it.unimi.dsi.util.FrontCodedStringList;import it.unimi.dsi.fastutil.io.BinIO;import java.nio.file.*;import java.util.*;
class Formats {
 public static void main(String[] args)throws Exception{
  Path p=Path.of(args[0]);String kind=args[1];
  if(kind.equals("utf8")){var old=(FrontCodedStringList)BinIO.loadObject(p.resolve("graph.strings").toString());List<String> v=new ArrayList<>();for(int i=0;i<old.size();i++)v.add(old.get(i).toString());BinIO.storeObject(new FrontCodedStringList(v.iterator(),8,true),p.resolve("graph.strings").toString());}
  else {var graph=BVGraph.load(p.resolve("forward").toString());int flags=kind.equals("window0")?0:BVGraph.class.getField(kind).getInt(null);BVGraph.store(graph,p.resolve("forward").toString(),kind.equals("window0")?0:7,3,4,3,flags);}
 }
}
