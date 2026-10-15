import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.google.gson.GsonBuilder;

/** Export only the bootstrap marker interface, without initializing app classes. */
public final class ExportSerializable {
    static String sha(Path p) throws Exception {
        var d=MessageDigest.getInstance("SHA-256");
        try(var in=Files.newInputStream(p)) {byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        return HexFormat.of().formatHex(d.digest());
    }
    static void require(boolean ok,String message) {if(!ok)throw new IllegalArgumentException(message);}
    public static void main(String[] args) throws Exception {
        require(args.length==4,"expected java home, modules hash, class output and receipt required");
        Path expectedHome=Path.of(args[0]).toRealPath(),home=Path.of(System.getProperty("java.home")).toRealPath();
        require(home.equals(expectedHome),"actual producer JDK home");Path modules=home.resolve("lib/modules");
        require(sha(modules).equals(args[1]),"actual producer module image");
        Class<?> marker=java.io.Serializable.class;
        require(marker.getClassLoader()==null && marker.getModule().getName().equals("java.base"),"bootstrap java.base marker");
        Path output=Path.of(args[2]),receipt=Path.of(args[3]);require(!Files.exists(output) && !Files.exists(receipt),"fresh outputs");
        byte[] raw;try(var in=marker.getModule().getResourceAsStream("java/io/Serializable.class")) {require(in!=null,"exact module resource");raw=in.readAllBytes();}
        Files.write(output,raw,StandardOpenOption.CREATE_NEW);
        require(sha(modules).equals(args[1]),"module image changed");
        var result=new LinkedHashMap<String,Object>();result.put("status","PASS_EXACT_BOOTSTRAP_SERIALIZABLE_EXPORT");
        result.put("className","java.io.Serializable");result.put("module","java.base");result.put("resource","java/io/Serializable.class");
        result.put("javaHome",home.toString());result.put("modulesPath",modules.toString());result.put("modulesSha256",args[1]);
        result.put("rawClassFile",output.toString());result.put("classBytesSha256",sha(output));result.put("bootstrapClassLoader",true);
        result.put("performanceClaim",false);
        Files.writeString(receipt,new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n",StandardOpenOption.CREATE_NEW);
    }
}
