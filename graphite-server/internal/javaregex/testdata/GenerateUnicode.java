// Generates behavior tables from the pinned Java 17 development oracle.
// The generated data has no JVM runtime dependency.
import java.util.*;
import java.nio.file.*;
import com.google.gson.Gson;
import java.lang.reflect.Field;
public class GenerateUnicode {
 static Map<String,List<Integer>> ranges=new TreeMap<>();
 static void add(String key,int cp){List<Integer> a=ranges.computeIfAbsent(key,k->new ArrayList<>());int n=a.size();if(n>0&&a.get(n-1)==cp-1)a.set(n-1,cp);else{a.add(cp);a.add(cp);}}
 static final String[] categories={"Cn","Lu","Ll","Lt","Lm","Lo","Mn","Me","Mc","Nd","Nl","No","Zs","Zl","Zp","Cc","Cf","INVALID","Co","Cs","Pd","Ps","Pe","Pc","Po","Sm","Sc","Sk","So","Pi","Pf"};
 public static void main(String[] args)throws Exception {
  Map<Integer,Integer> lower=new TreeMap<>(),upper=new TreeMap<>();
  for(int cp=0;cp<=0x10ffff;cp++){
   int type=Character.getType(cp);add(categories[type],cp);
   add("sc:"+Character.UnicodeScript.of(cp).name(),cp);
   Character.UnicodeBlock block=Character.UnicodeBlock.of(cp);if(block!=null)add("blk:"+block.toString(),cp);
   int l=Character.toLowerCase(cp),u=Character.toUpperCase(cp);if(l!=cp)lower.put(cp,l);if(u!=cp)upper.put(cp,u);
   if(Character.isLowerCase(cp))add("javaLowerCase",cp);
   if(Character.isUpperCase(cp))add("javaUpperCase",cp);
   if(Character.isTitleCase(cp))add("javaTitleCase",cp);
   if(Character.isDigit(cp))add("javaDigit",cp);
   if(Character.isDefined(cp))add("javaDefined",cp);
   if(Character.isLetter(cp))add("javaLetter",cp);
   if(Character.isLetterOrDigit(cp))add("javaLetterOrDigit",cp);
   if(Character.isJavaIdentifierStart(cp))add("javaJavaIdentifierStart",cp);
   if(Character.isJavaIdentifierPart(cp))add("javaJavaIdentifierPart",cp);
   if(Character.isUnicodeIdentifierStart(cp))add("javaUnicodeIdentifierStart",cp);
   if(Character.isUnicodeIdentifierPart(cp))add("javaUnicodeIdentifierPart",cp);
   if(Character.isIdentifierIgnorable(cp))add("javaIdentifierIgnorable",cp);
   if(Character.isSpaceChar(cp))add("javaSpaceChar",cp);
   if(Character.isWhitespace(cp))add("javaWhitespace",cp);
   if(Character.isISOControl(cp))add("javaISOControl",cp);
   if(Character.isMirrored(cp))add("javaMirrored",cp);
   if(Character.isAlphabetic(cp))add("Alphabetic",cp);
   if(Character.isIdeographic(cp))add("Ideographic",cp);
  }
  Map<String,Object> result=new LinkedHashMap<>();result.put("javaVersion",System.getProperty("java.version"));result.put("unicodeVersion","13.0 (Java17 Character)");result.put("ranges",ranges);
  Field scriptAliases=Character.UnicodeScript.class.getDeclaredField("aliases");scriptAliases.setAccessible(true);Map<String,String> scripts=new TreeMap<>();for(var e:((Map<?,?>)scriptAliases.get(null)).entrySet())scripts.put(e.getKey().toString(),e.getValue().toString());for(var script:Character.UnicodeScript.values())scripts.put(script.name(),script.name());result.put("scriptAliases",scripts);
  Field blockAliases=Character.UnicodeBlock.class.getDeclaredField("map");blockAliases.setAccessible(true);Map<String,String> blocks=new TreeMap<>();for(var e:((Map<?,?>)blockAliases.get(null)).entrySet())blocks.put(e.getKey().toString(),e.getValue().toString());result.put("blockAliases",blocks);result.put("lower",lower);result.put("upper",upper);
  Files.writeString(Path.of(args[0]),new Gson().toJson(result)+"\n");
 }
}
