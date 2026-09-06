// Correctness only: java -Xmx128m -cp <frozen-main-explore.jar>
// GenerateStringTables.java <output-directory>
import it.unimi.dsi.util.FrontCodedStringList;
import it.unimi.dsi.fastutil.io.BinIO;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import io.johnsonlee.graphite.core.StringConstant;
import io.johnsonlee.graphite.graph.DefaultGraph;
import io.johnsonlee.graphite.webgraph.GraphStore;

class GenerateStringTables {
    static List<Map<String,Object>> records = new ArrayList<>();
    static void emit(Path root, String name, List<String> strings, int ratio, boolean utf8) throws Exception {
        Path path=root.resolve(name+".strings");
        BinIO.storeObject(new FrontCodedStringList(strings.iterator(),ratio,utf8),path.toString());
        inspect(root,name);
    }
    static void inspect(Path root,String name) throws Exception {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("name",name);
        String phase="load";
        try {
            var table=(FrontCodedStringList)BinIO.loadObject(root.resolve(name+".strings").toString());
            phase="get";
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            DataOutputStream data=new DataOutputStream(output);
            data.writeInt(table.size());
            for(int i=0;i<table.size();i++) {
                String text=table.get(i).toString();
                data.writeInt(text.length());
                for(int j=0;j<text.length();j++)data.writeChar(text.charAt(j));
            }
            byte[] units=output.toByteArray();
            try(var gzip=new GZIPOutputStream(Files.newOutputStream(root.resolve(name+".utf16.gz")))){gzip.write(units);}
            row.put("count",table.size());row.put("utf16SHA256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(units)));
        } catch(Exception e) {
            row.put("errorPhase",phase);row.put("errorClass",e.getClass().getSimpleName());row.put("errorMessage",e.getMessage());
        }
        records.add(row);
    }
    static void mutateUID(Path root,String source,String name,String className) throws Exception {
        byte[] data=Files.readAllBytes(root.resolve(source+".strings"));
        byte[] needle=className.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:for(int i=3;i+needle.length<data.length;i++) {
            if(data[i-3]!=0x72)continue;
            for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j])continue outer;
            data[i+needle.length]^=1;break;
        }
        Files.write(root.resolve(name+".strings"),data);inspect(root,name);
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        List<String> normal=List.of("","A\0B","\u4e16\u754c","é","\ud83d\ude00","\ud83d\ude00\u4e16","\ud83d\ude00é","\ud83d\ude00\ud83d\ude00","\ud800","\udc00","A\ud800B","\ud800\ud800","\udc00\ud800","\uffff","prefix\ud83d\ude00");
        for(int ratio:new int[]{1,3,128}) {
            emit(root,"char-ratio-"+ratio,normal,ratio,false);
            emit(root,"utf8-ratio-"+ratio,normal,ratio,true);
        }
        List<String> lengths=new ArrayList<>();
        for(int n:new int[]{0,1,127,128,16383,16384,32767,32768,40000})lengths.add("a".repeat(n));
        lengths.add("a".repeat(40000)+"b");
        emit(root,"char-lengths",lengths,8,false);emit(root,"utf8-lengths",lengths,8,true);
        emit(root,"char-empty",List.of(),8,false);emit(root,"utf8-empty",List.of(),8,true);
        emit(root,"utf8-astral-ascii",List.of("\ud83d\ude00A"),8,true);
        for(String source:List.of("char-ratio-3","utf8-ratio-3")) {
            Path target=root.resolve(source+"-trailing.strings");Files.copy(root.resolve(source+".strings"),target,StandardCopyOption.REPLACE_EXISTING);
            Files.write(target,new byte[]{1,2,3,4},StandardOpenOption.APPEND);inspect(root,source+"-trailing");
        }
        mutateUID(root,"char-ratio-3","char-root-uid","it.unimi.dsi.util.FrontCodedStringList");
        mutateUID(root,"char-ratio-3","char-list-uid","it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList");
        mutateUID(root,"utf8-ratio-3","utf8-list-uid","it.unimi.dsi.fastutil.bytes.ByteArrayFrontCodedList");
        mutateUID(root,"char-ratio-3","char-array-uid","[[C");
        mutateUID(root,"utf8-ratio-3","byte-array-uid","[[B");
        var builder=new DefaultGraph.Builder();
        builder.addNode((StringConstant)StringConstant.class.getConstructors()[0].newInstance(0,"A\ud800B",null));
        builder.addNode((StringConstant)StringConstant.class.getConstructors()[0].newInstance(1,"\udc00",null));
        builder.addNode((StringConstant)StringConstant.class.getConstructors()[0].newInstance(2,"\ud83d\ude00",null));
        GraphStore.INSTANCE.save(builder.build(),root.resolve("node-store"),3,false);
        Files.writeString(root.resolve("oracle.json"),new GsonBuilder().setPrettyPrinting().create().toJson(records)+"\n");
    }
}
