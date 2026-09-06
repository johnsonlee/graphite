import java.util.Locale;
public class ASCIILowerOracle {
 static String hex(String s) { StringBuilder b=new StringBuilder(); for(char c:s.toCharArray()) for(int shift=12;shift>=0;shift-=4) b.append(Character.forDigit((c>>shift)&15,16)); return b.toString(); }
 public static void main(String[] args) {
  for(int a=0;a<128;a++) for(int b=0;b<128;b++) { String s=new String(new char[]{(char)a,'A','z',(char)b}); System.out.println(hex(s)+"\t"+hex(s.toLowerCase(Locale.ROOT))); }
 }
}
