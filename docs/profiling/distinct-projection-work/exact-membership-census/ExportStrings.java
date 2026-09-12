import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import it.unimi.dsi.fastutil.io.BinIO;
import it.unimi.dsi.util.FrontCodedStringList;
public class ExportStrings {
 public static void main(String[] args) throws Exception {
  long total=0; int gi=0;
  try (DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(args[1])))))) {
   out.writeInt(0x47535431);
   for(String line:Files.readAllLines(Path.of(args[0]))) {
    if(line.isEmpty()||line.startsWith("#")) continue;
    String[] cols=line.split("\t"); Path p=Path.of(cols[1],"graph.strings");
    byte[] before=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
    FrontCodedStringList list=(FrontCodedStringList)BinIO.loadObject(p.toString());
    out.writeInt(gi); out.writeInt(list.size());
    for(int id=0;id<list.size();id++) {
     String value=list.get(id).toString(); out.writeInt(value.length());
     // UTF-16 code units preserve Java strings exactly, including unpaired surrogates.
     for(int c=0;c<value.length();c++) out.writeChar(value.charAt(c));
    }
    byte[] after=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
    if(!Arrays.equals(before,after)) throw new IllegalStateException("Input changed: "+p);
    System.out.println(gi+"\t"+cols[0]+"\t"+list.size()+"\t"+HexFormat.of().formatHex(before));
    gi++; total+=list.size();
   }
  }
  if(gi!=64||total!=2793940L)throw new IllegalStateException("Unexpected fixture dimensions");
  System.err.println("Exported "+total+" strings across "+gi+" graphs; input hashes unchanged.");
 }
}
