import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
import java.io.*;
import java.util.*;
import java.security.*;
import it.unimi.dsi.util.FrontCodedStringList;
/** Independent wire decoder: no Graphite classes, query engine, or declared-type reader. */
public class CountBindings {
    record Key(String owner,String name,String descriptor){
    }
    static ByteBuffer map(Path p)throws Exception{
        try(var c=FileChannel.open(p)){
            return c.map(FileChannel.MapMode.READ_ONLY,0,c.size());
        }
    }
    static String text(ByteBuffer b){
        byte[] a=new byte[b.getInt()];
        b.get(a);
        return new String(a,java.nio.charset.StandardCharsets.UTF_8);
    }
    static void ids(ByteBuffer b){
        int n=b.getInt();
        b.position(b.position()+4*n);
    }
    static void params(ByteBuffer b){
        int n=b.getInt();
        for (int i=0; i<n; i++){
            text(b);
            text(b);
            ids(b);
        }
    }
    static Key key(ByteBuffer b){
        return new Key(text(b),text(b),text(b));
    }
    static String descriptor(String s){
        String prefix="";
        while(s.endsWith("[]")){
            prefix+="[";
            s=s.substring(0,s.length()-2);
        }
        String value=switch(s){
            case "void"->"V";
            case "boolean"->"Z";
            case "byte"->"B";
            case "char"->"C";
            case "short"->"S";
            case "int"->"I";
            case "long"->"J";
            case "float"->"F";
            case "double"->"D";
            default->"L"+s.replace('.','/')+";";
        };
        return prefix+value;
    }
    static Key method(ByteBuffer b,String[] s){
        String owner=s[b.getInt()],name=s[b.getInt()];
        StringBuilder d=new StringBuilder("(");
        int n=b.getInt();
        for (int i=0; i<n; i++)d.append(descriptor(s[b.getInt()]));
        d.append(')').append(descriptor(s[b.getInt()]));
        return new Key(owner,name,d.toString());
    }
    public static void main(String[] args)throws Exception{
        System.out.println("graph\tfields\tparameters\treturns\tboundFields\tboundParameters\tboundReturns\ttypesSha256");
        for(String arg:args){
            Path p=Path.of(arg);
            Set<Key> fields=new HashSet<>();
            Map<Key,Integer> methods=new HashMap<>();
            ByteBuffer t=map(p.resolve("graph.types"));
            if(t.getInt()!=0x47545901)throw new AssertionError();
            t.position(36);
            int count=t.getInt();
            for (int i=0; i<count; i++){
                text(t);
                text(t);
                text(t);
                t.getInt();
                t.getInt();
                text(t);
                ids(t);
            }
            count=t.getInt();
            for (int i=0; i<count; i++){
                if(!fields.add(key(t)))throw new AssertionError();
                t.getInt();
            }
            count=t.getInt();
            for (int i=0; i<count; i++){
                Key k=key(t);
                int n=t.getInt();
                t.position(t.position()+4*n);
                t.getInt();
                params(t);
                if(methods.put(k,n)!=null)throw new AssertionError();
            }
            count=t.getInt();
            for (int i=0; i<count; i++){
                text(t);
                params(t);
                t.getInt();
                ids(t);
            }
            if(t.hasRemaining())throw new AssertionError();
            FrontCodedStringList strings;
            try(var in=new ObjectInputStream(new BufferedInputStream(Files.newInputStream(p.resolve("graph.strings"))))){
                strings=(FrontCodedStringList)in.readObject();
            }
            String[] s=new String[strings.size()];
            for (int i=0; i<s.length; i++)s[i]=strings.get(i).toString();
            ByteBuffer offsets=map(p.resolve("graph.nodeoffsets")),data=map(p.resolve("graph.nodedata"));
            offsets.getInt();
            count=offsets.getInt();
            long[] totals=new long[3],bound=new long[3];
            for (int id=0; id<count; id++){
                long off=offsets.getLong();
                if(off==0)continue;
                data.position(Math.toIntExact(off-1));
                if(data.getInt()!=id)throw new AssertionError();
                int tag=Byte.toUnsignedInt(data.get());
                if(tag<9||tag>11)continue;
                totals[tag-9]++;
                boolean found;
                if(tag==9){
                    Key k=new Key(s[data.getInt()],s[data.getInt()],descriptor(s[data.getInt()]));
                    found=fields.contains(k);
                }
                else{
                    int index=-1;
                    if(tag==10){
                        index=data.getInt();
                        data.getInt();
                    }
                    Key k=method(data,s);
                    Integer n=methods.get(k);
                    found=n!=null&&(tag==11||index>=0&&index<n);
                }
                if(found)bound[tag-9]++;
            }
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p.resolve("graph.types"))));
            System.out.printf("%s\t%d\t%d\t%d\t%d\t%d\t%d\t%s%n",p,totals[0],totals[1],totals[2],bound[0],bound[1],bound[2],hash);
        }
    }
}
