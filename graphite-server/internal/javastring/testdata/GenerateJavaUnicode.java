import com.google.gson.Gson;
import java.util.*;
public class GenerateJavaUnicode {
 public static void main(String[] args) throws Exception {
  Map<String,Object> result=new LinkedHashMap<>();Map<String,String> lower=new LinkedHashMap<>(),upper=new LinkedHashMap<>();Map<String,Integer> digits=new LinkedHashMap<>();List<Integer> whitespace=new ArrayList<>(),cased=new ArrayList<>(),ignorable=new ArrayList<>();
  java.lang.reflect.Method isCased=Class.forName("java.lang.ConditionalSpecialCasing").getDeclaredMethod("isCased",int.class);isCased.setAccessible(true);
  java.text.BreakIterator words=java.text.BreakIterator.getWordInstance(Locale.ROOT);Class<?> wordClass=words.getClass();java.lang.reflect.Method category=wordClass.getDeclaredMethod("lookupCategory",int.class);category.setAccessible(true);List<int[]> categories=new ArrayList<>();int previous=-999;
  for(int cp=0;cp<=Character.MAX_CODE_POINT;cp++){int value=(Integer)category.invoke(words,cp);if(value!=previous){categories.add(new int[]{cp,value});previous=value;}}
  result.put("wordCategories",categories);
  for(String field:List.of("stateTable","endStates","lookaheadStates","numCategories")){java.lang.reflect.Field f=wordClass.getDeclaredField(field);f.setAccessible(true);result.put(field,f.get(words));}
  for(int cp=0;cp<=Character.MAX_CODE_POINT;cp++){
   if(cp>=0xd800&&cp<=0xdfff)continue;
   String text=new String(Character.toChars(cp)),lo=text.toLowerCase(Locale.ROOT),up=text.toUpperCase(Locale.ROOT);
   if(!text.equals(lo))lower.put(Integer.toString(cp),lo);if(!text.equals(up))upper.put(Integer.toString(cp),up);
   if(Character.isWhitespace(cp)||Character.isSpaceChar(cp))whitespace.add(cp);
   if((Boolean)isCased.invoke(null,cp))cased.add(cp);
   int digit=Character.digit(cp,10);if(digit>=0)digits.put(Integer.toString(cp),digit);
   int type=Character.getType(cp);if(type==Character.NON_SPACING_MARK||type==Character.ENCLOSING_MARK||type==Character.FORMAT||type==Character.MODIFIER_LETTER||type==Character.MODIFIER_SYMBOL)ignorable.add(cp);
  }
  result.put("digits",digits);result.put("lower",lower);result.put("upper",upper);result.put("whitespace",whitespace);result.put("cased",cased);result.put("ignorable",ignorable);System.out.println(new Gson().toJson(result));
 }
}
