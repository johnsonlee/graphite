import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import com.google.gson.Gson;
import sootup.core.jimple.common.Local;
import sootup.core.jimple.common.Value;
import sootup.core.jimple.common.expr.JNewExpr;
import sootup.core.jimple.common.stmt.JAssignStmt;
import sootup.core.model.*;
import sootup.core.types.*;
import io.johnsonlee.graphite.sootup.ParsedClassLocation;
import io.johnsonlee.graphite.sootup.GraphiteJavaViewKt;

/** Read raw frontend types only. No adapter, graphTypeName, graph builder or saved type input. */
public final class ExportRawLocals {
    static final Gson JSON = new Gson();
    static void need(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
    static Map<String,Object> row(Object... items) {
        var out = new LinkedHashMap<String,Object>();
        for (int i=0;i<items.length;i+=2) out.put((String)items[i],items[i+1]);
        return out;
    }
    static Map<String,Object> type(Type t) {
        if (t instanceof ArrayType a) {
            need(a.getDimension()>=1 && a.getDimension()<=255 && !(a.getBaseType() instanceof ArrayType), "flat raw ArrayType shape");
            return row("kind","array","dimension",a.getDimension(),"base",type(a.getBaseType()));
        }
        if (t instanceof ClassType c) return row("kind","class","name",c.getFullyQualifiedName());
        if (t instanceof PrimitiveType p) return row("kind","primitive","name",p.getName());
        if (t instanceof VoidType) return row("kind","void");
        // Null/unknown types are evidence, never guessed from textual output.
        return row("kind","unsupported","runtimeClass",t.getClass().getName());
    }
    static String descriptor(Map<String,Object> t) {
        return switch ((String)t.get("kind")) {
            case "array" -> "[".repeat((Integer)t.get("dimension")) + descriptor(cast(t.get("base")));
            case "class" -> "L"+((String)t.get("name")).replace('.','/')+";";
            case "void" -> "V";
            case "primitive" -> switch ((String)t.get("name")) {
                case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C"; case "short" -> "S";
                case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
                default -> throw new IllegalArgumentException("unknown primitive");
            };
            default -> throw new IllegalArgumentException("unsupported raw method signature type");
        };
    }
    @SuppressWarnings("unchecked") static Map<String,Object> cast(Object o) {return (Map<String,Object>)o;}
    static List<String> method(SootMethod m) {
        var signature = new StringBuilder("(");
        for (Type t:m.getParameterTypes()) signature.append(descriptor(type(t)));
        signature.append(')').append(descriptor(type(m.getReturnType())));
        return List.of(m.getDeclaringClassType().getFullyQualifiedName(),m.getName(),signature.toString());
    }
    static void local(Local l, String origin, Map<String,List<Map<String,Object>>> locals) {
        locals.computeIfAbsent(l.getName(), ignored -> new ArrayList<>()).add(row("type",type(l.getType()),"origin",origin));
    }
    static void value(Value v, String origin, Map<String,List<Map<String,Object>>> locals, Set<Value> seen) {
        if (!seen.add(v)) return;
        if (v instanceof Local l) local(l,origin,locals);
        for (Value use:v.getUses()) value(use,origin,locals,seen);
    }
    static void graph(Path shard, String graphId, Path output, String bytecodeHash, int classCount) throws Exception {
        try (var sink=Files.newBufferedWriter(output,StandardOpenOption.CREATE_NEW)) {
            sink.write(JSON.toJson(row("record","header","graphId",graphId,"shardBytecodeSha256",bytecodeHash,
                    "classCount",classCount,"scope","fixture64-no-fold","folding",false))+"\n");
            int count=0;
            try (var location=new ParsedClassLocation(shard,SourceType.Application,List.of())) {
                var view=GraphiteJavaViewKt.createJavaView(List.of(location));
                try (var classes=view.getClasses()) {
                    var iterator=classes.iterator();
                    while(iterator.hasNext()) {
                        var klass=iterator.next();
                        for (var m:klass.getMethods()) {
                            if (!m.hasBody()) continue;
                            var key=method(m);
                            var locals=new LinkedHashMap<String,List<Map<String,Object>>>();
                            var allocations=new LinkedHashMap<String,List<Map<String,Object>>>();
                            var assignments=new LinkedHashMap<String,List<Map<String,Object>>>();
                            // Materialise once; streamed bodies and encounter order must not be rebuilt.
                            var body=m.getBody();
                            for (var l:body.getLocals()) local(l,"body.locals",locals);
                            int ordinal=0;
                            for (var stmt:body.getControlFlowGraph().getStmts()) {
                                var seen=Collections.newSetFromMap(new IdentityHashMap<Value,Boolean>());
                                for(var v:stmt.getUsesAndDefs()) value(v,"stmt:"+ordinal,locals,seen);
                                if(stmt instanceof JAssignStmt assign && assign.getLeftOp() instanceof Local left) {
                                    if (assign.getRightOp() instanceof JNewExpr allocation) {
                                        local(left,"typed-allocation:"+ordinal,locals);
                                        allocations.computeIfAbsent(left.getName(),ignored->new ArrayList<>()).add(row("ordinal",ordinal,"type",type(allocation.getType())));
                                    } else {
                                        local(left,"ordinary-assignment:"+ordinal,locals);
                                        assignments.computeIfAbsent(left.getName(),ignored->new ArrayList<>()).add(row("ordinal",ordinal,"type",type(left.getType())));
                                    }
                                }
                                ordinal++;
                            }
                            sink.write(JSON.toJson(row("record","method","method",key,"locals",locals,"typedAllocations",allocations,"ordinaryAssignments",assignments,"statementCount",ordinal))+"\n");
                            sink.flush(); count++;
                        }
                    }
                }
            }
            sink.write(JSON.toJson(row("record","complete","methodCount",count))+"\n");
        }
    }
    /** corpus sourceJar originalFixtureProvenance output. Actual writer splitter supplies identical inputs. */
    public static void main(String[] args) throws Exception {
        need(args.length==4,"corpus, source JAR, original fixture provenance, fresh output required");
        String corpus=args[0];Path source=Path.of(args[1]),provenance=Path.of(args[2]),out=Path.of(args[3]);
        need(!Files.exists(out),"fresh output");Files.createDirectories(out);
        var lines=Files.readAllLines(provenance);String[] header=lines.get(0).split("\t",-1);
        var rows=new TreeMap<Integer,Map<String,String>>();
        for(String line:lines.subList(1,lines.size())) {
            if(line.isBlank()) continue;
            var cells=line.split("\t",-1);need(cells.length==header.length,"provenance width");
            var row=new LinkedHashMap<String,String>();for(int i=0;i<cells.length;i++)row.put(header[i],cells[i]);
            if(!corpus.equals(row.get("corpus")))continue;
            need(source.getFileName().toString().equals(row.get("sourceJar")),"same source JAR name");
            int index=Integer.parseInt(row.get("shard"));need(index>=0 && index<16 && rows.put(index,row)==null,"unique shard");
        }
        need(rows.size()==16,"complete corpus16 provenance");
        Path shards=Files.createDirectory(out.resolve("shards"));
        Class<?> fixture=Class.forName("io.johnsonlee.graphite.webgraph.Fixture64GraphPreparation");
        Method split=fixture.getDeclaredMethod("splitFixtureJar",Path.class,Path.class);split.setAccessible(true);
        // Calls input construction only, never buildGraph/save or a type rendering function.
        var values=(List<?>)split.invoke(fixture.getField("INSTANCE").get(null),source,shards);
        need(values.size()==16,"writer splitter16");
        for(int i=0;i<16;i++) {
            Object shard=values.get(i);Class<?> shape=shard.getClass();
            Method path=shape.getDeclaredMethod("getPath"),hash=shape.getDeclaredMethod("getBytecodeSha256"),count=shape.getDeclaredMethod("getClassCount");
            path.setAccessible(true);hash.setAccessible(true);count.setAccessible(true);
            var expected=rows.get(i);String digest=(String)hash.invoke(shard);int classes=((Number)count.invoke(shard)).intValue();
            need(expected.get("shardBytecodeSha256").equals(digest) && Integer.parseInt(expected.get("classCount"))==classes,"original shard bytecode identity");
            String id=expected.get("graphId");need(id.equals("fixture-"+corpus+"-"+String.format(Locale.ROOT,"%02d",i)),"graph identity");
            graph((Path)path.invoke(shard),id,out.resolve(id+".jsonl"),digest,classes);
            Files.delete((Path)path.invoke(shard));
        }
        Files.delete(shards);
    }
}
