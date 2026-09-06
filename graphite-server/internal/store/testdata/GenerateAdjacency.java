// Regenerate with java -cp <graphite-webgraph-jmh.jar> GenerateAdjacency.java <output-directory>
// Uses the same BVGraph.store defaults as GraphStore.save. Correctness fixture only.
import it.unimi.dsi.webgraph.*;
import java.nio.file.*;
public class GenerateAdjacency {
 public static void main(String[] args)throws Exception{
  Path p=Paths.get(args[0]);Files.createDirectories(p);
  ArrayListMutableGraph g=new ArrayListMutableGraph(64);
  int[][] rows={{0,1,2,3,4,5,6,20,40,63},{1,2,3,4,5,6,7,20,40,63},{2,3,4,5,6,7,8,20,42,63},{3,2,3,4,5,6,7,20,40,60,61,62,63},{7,0,63},{63,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}};
  for(int[] row:rows)for(int i=1;i<row.length;i++)g.addArc(row[0],row[i]);
  BVGraph.store(g.immutableView(),p.resolve("forward").toString());
 }
}
