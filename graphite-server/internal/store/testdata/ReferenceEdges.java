// Correctness reference: java -cp <graphite-webgraph-jmh.jar> ReferenceEdges.java <graph-directory>
// Prints SHA-256 of every ordered (source:int32,target:int32) pair, big endian.
import it.unimi.dsi.webgraph.*;
import java.nio.*;
import java.security.*;
public class ReferenceEdges {
 public static void main(String[]args)throws Exception{
  ImmutableGraph g=BVGraph.loadSequential(args[0]+"/forward");MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteBuffer b=ByteBuffer.allocate(8);long arcs=0;
  NodeIterator nodes=g.nodeIterator();while(nodes.hasNext()){int from=nodes.nextInt();int count=nodes.outdegree();LazyIntIterator targets=nodes.successors();for(int i=0;i<count;i++){b.clear();b.putInt(from);b.putInt(targets.nextInt());digest.update(b.array());arcs++;}}
  StringBuilder hex=new StringBuilder();for(byte v:digest.digest())hex.append(String.format("%02x",v&255));System.out.println(arcs+" "+hex);
 }
}
