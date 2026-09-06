// Additional BMP Char semantics from Java 17 and the Kotlin runtime in main.
// This records primitive values, not an implementation copied from that runtime.
import java.nio.file.*;
import java.io.*;
import java.util.zip.GZIPOutputStream;
class GenerateJavaChar {
 public static void main(String[] args) throws Exception {
  try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(args[0]))))) {
   for(int i=0;i<=65535;i++) {
    char c=(char)i;
    out.writeByte((Character.isLowerCase(c)?1:0)|(Character.isUpperCase(c)?2:0)|((Character.isWhitespace(c)||Character.isSpaceChar(c))?4:0));
    String title=kotlin.text._OneToManyTitlecaseMappingsKt.titlecaseImpl(c);
    out.writeByte(title.length());for(int j=0;j<title.length();j++)out.writeChar(title.charAt(j));
   }
  }
 }
}
