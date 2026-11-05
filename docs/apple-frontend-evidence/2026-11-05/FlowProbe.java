import io.johnsonlee.graphite.core.*;
import io.johnsonlee.graphite.graph.Graph;
import io.johnsonlee.graphite.ir.IrReader;
import io.johnsonlee.graphite.webgraph.GraphStore;
import com.google.gson.Gson;
import kotlin.sequences.Sequence;
import java.util.*;
import java.nio.file.*;

public class FlowProbe {
 static final String WRAP="OWSLocalizedString(_:tableName:value:comment:)";
 static final String CALL="NSLocalizedString(_:tableName:bundle:value:comment:)";
 static final List<String> TYPES=List.of("Swift.String","Swift.String?","Swift.String","Swift.String");
 static void check(boolean x,String why) { if (!x) throw new AssertionError(why); }
 static int id(Node n) throws Exception {return (int)Node.class.getMethod("getId-4bBHiqc").invoke(n);}
 static int from(Edge e) throws Exception {return (int)Edge.class.getMethod("getFrom-4bBHiqc").invoke(e);}
 static int to(Edge e) throws Exception {return (int)Edge.class.getMethod("getTo-4bBHiqc").invoke(e);}
 static Node node(Graph g,int i) throws Exception{return (Node)Graph.class.getMethod("node-NKfh_u4",int.class).invoke(g,i);}
 @SuppressWarnings("unchecked") static List<Edge> edges(Graph g,int i,boolean outgoing) throws Exception {
  var s=(Sequence<Edge>)Graph.class.getMethod((outgoing?"outgoing":"incoming")+"-NKfh_u4",int.class).invoke(g,i);var r=new ArrayList<Edge>();s.iterator().forEachRemaining(r::add);return r;
 }
 static <T extends Node> List<T> nodes(Graph g,Class<T> c){var a=new ArrayList<T>();g.nodes(c).iterator().forEachRemaining(a::add);return a;}
 static Map<String,Object> verify(Graph g) throws Exception {
  var sites=nodes(g,CallSiteNode.class).stream().filter(c->c.getCaller().getName().equals(WRAP)&&c.getCallee().getName().equals(CALL)).toList();
  check(sites.size()==1,"one wrapper call, got "+sites.size());var c=sites.get(0);var owner=c.getCaller();
  check(owner.getDeclaringClass().getClassName().equals("SignalServiceKit") && owner.getParameterTypes().stream().map(TypeDescriptor::getClassName).toList().equals(TYPES) && owner.getReturnType().getClassName().equals("Swift.String"),"wrong owner "+owner);
  check(c.getCallee().getDeclaringClass().getClassName().equals("Foundation") && c.getLineNumber()==26 && c.getArguments().size()==5,"wrong callee/line/arity");
  var params=nodes(g,ParameterNode.class).stream().filter(p->p.getMethod().equals(owner)).sorted(Comparator.comparingInt(ParameterNode::getIndex)).toList();
  check(params.size()==4,"expected four parameters: "+params);
  var pids=new ArrayList<Integer>();
  for(int i=0;i<4;i++) {var p=params.get(i);check(p.getIndex()==i && p.getType().getClassName().equals(TYPES.get(i)),"wrong formal "+p);pids.add(id(p));}
  var args=c.getArguments().stream().map(NodeId::getValue).toList();
  var names=List.of("key","tableName",".main.app","value","comment");
  var formalSlots=List.of(0,1,-1,2,3);var bindings=new ArrayList<Map<String,Object>>();
  for(int i=0;i<args.size();i++){
   var n=node(g,args.get(i));check(n instanceof LocalVariable,"slot "+i+" expected local, got "+n);
   var loc=(LocalVariable)n;check(loc.getName().equals(names.get(i)) && loc.getMethod().equals(owner),"wrong local at "+i+": "+loc);
   var sources=new ArrayList<Integer>();for(var e:edges(g,id(loc),false))if(e instanceof DataFlowEdge d && d.getKind()==DataFlowKind.ASSIGN)sources.add(from(e));
   int formal=formalSlots.get(i);check(sources.equals(formal<0?List.of():List.of(pids.get(formal))),"bad local ASSIGN at "+i+": "+sources);
   if(formal<0)check(loc.getType().getClassName().isEmpty(),"unexpected bundle type "+loc);
   bindings.add(Map.of("slot",i,"localId",id(loc),"name",loc.getName(),"type",loc.getType().getClassName(),"assignSourceIds",sources));
  }
  check(new HashSet<>(args).size()==5,"each source expression has own local");
  var incoming=edges(g,id(c),false);var pass=new ArrayList<Integer>();for(var e: incoming)if(e instanceof DataFlowEdge d && d.getKind()==DataFlowKind.PARAMETER_PASS)pass.add(from(e));
  check(pass.size()==5 && new HashSet<>(pass).equals(new HashSet<>(args)),"bad PARAMETER_PASS: "+pass);
  var outgoing=edges(g,id(c),true);var rv=outgoing.stream().filter(e->e instanceof DataFlowEdge d && d.getKind()==DataFlowKind.RETURN_VALUE).toList();check(rv.size()==1,"return edge count "+rv.size());
  var ret=node(g,to(rv.get(0)));check(ret instanceof ReturnNode && ((ReturnNode)ret).getMethod().equals(owner),"wrong return owner");
  check(outgoing.stream().noneMatch(e->e instanceof ResourceEdge r && r.getKind()==ResourceRelation.LOOKUP),"invented LOOKUP");
  var r=new LinkedHashMap<String,Object>();r.put("owner",owner.getSignature());r.put("formalTypes",TYPES);r.put("formalIds",pids);r.put("callee",c.getCallee().getSignature());r.put("line",c.getLineNumber());r.put("callId",id(c));r.put("orderedArgumentIds",args);r.put("argumentBindings",bindings);r.put("parameterPassSourceIds",pass);r.put("returnNodeId",id(ret));r.put("returnOwner",((ReturnNode)ret).getMethod().getSignature());r.put("lookupEdgesFromCall",0);return r;
 }
 public static void main(String[] args) throws Exception {
  Graph g=args[0].equals("ir")?new IrReader().read(Path.of(args[1])).getFirst():GraphStore.INSTANCE.load(Path.of(args[1]),GraphStore.LoadMode.MAPPED);
  System.out.println(new Gson().toJson(verify(g)));
 }
}
