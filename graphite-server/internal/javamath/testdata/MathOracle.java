// Correctness-only Java 17 Math / StrictMath / compiled-call bit comparison.
// No timings are measured. Warm calls intentionally test JIT semantic stability.
import java.util.*;
class MathOracle {
 static volatile double sink;
 static double value(int f,double x,double y,boolean strict) {
  if (strict) return switch(f) {
    case 0->StrictMath.sin(x);
    case 1->StrictMath.cos(x);
    case 2->StrictMath.tan(x);
    case 3->StrictMath.exp(x);
    case 4->StrictMath.log(x);
    case 5->StrictMath.log10(x);
    case 6->StrictMath.sqrt(x);
    case 7->StrictMath.asin(x);
    case 8->StrictMath.acos(x);
    case 9->StrictMath.atan(x);
    default->StrictMath.atan2(x,y);
  };
  return switch(f) {
    case 0->Math.sin(x);
    case 1->Math.cos(x);
    case 2->Math.tan(x);
    case 3->Math.exp(x);
    case 4->Math.log(x);
    case 5->Math.log10(x);
    case 6->Math.sqrt(x);
    case 7->Math.asin(x);
    case 8->Math.acos(x);
    case 9->Math.atan(x);
    default->Math.atan2(x,y);
  };
 }
 static String bits(double x){return String.format("%016x",Double.doubleToRawLongBits(x));}
 public static void main(String[] args){
  List<Double> values=new ArrayList<>();
  double[] fixed={0.0,-0.0,1,-1,0.5,-0.5,2,-2,Math.PI,-Math.PI,Math.PI/2,-Math.PI/2,Math.PI/4,Math.nextDown(1.0),Math.nextUp(1.0),1e-20,1e20,1e100,1e300,Double.MIN_VALUE,-Double.MIN_VALUE,Double.MIN_NORMAL,-Double.MIN_NORMAL,Double.MAX_VALUE,-Double.MAX_VALUE,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NaN,709,709.782712893384,710,-745,-746};
  for(double x:fixed){values.add(x);if(Double.isFinite(x)){values.add(Math.nextDown(x));values.add(Math.nextUp(x));}}
  Random random=new Random(0x47a0b17);for(int i=0;i<1024;i++)values.add(Double.longBitsToDouble(random.nextLong()));for(int i=0;i<1024;i++)values.add((random.nextDouble()-.5)*100);
  // Adjacent binary64 inputs at implementation seams and argument reduction multiples.
  long[] seams={0x3e300000L,0x3e400000L,0x3fd33333L,0x3fd62e42L,0x3fdc0000L,0x3fe60000L,0x3fe90000L,0x3fe921fbL,0x3fef3333L,0x3ff00000L,0x3ff0a2b2L,0x3ff30000L,0x3ff921fbL,0x4002d97cL,0x40038000L,0x40862e42L,0x413921fbL,0x44100000L};
  for(long high:seams) for(long low:new long[]{0,0xffffffffL}) for(int step=-1;step<=1;step++) {double x=Double.longBitsToDouble((high<<32)+low+step); values.add(x);values.add(-x);}
  for(int i=1;i<=64;i++){double x=i*(Math.PI/2);for(double a:new double[]{Math.nextDown(x),x,Math.nextUp(x)}){values.add(a);values.add(-a);}}
  String[] names={"sin","cos","tan","exp","log","log10","sqrt","asin","acos","atan","atan2"};
  long[][] cold=new long[11][values.size()];for(int f=0;f<11;f++)for(int i=0;i<values.size();i++)cold[f][i]=Double.doubleToRawLongBits(value(f,values.get(i),values.get((i+17)%values.size()),false));
  for(int i=0;i<50000;i++){int f=i%11,j=i%values.size();sink=value(f,values.get(j),values.get((j+17)%values.size()),false);}
  System.out.println("function\txBits\tyBits\tmathColdBits\tstrictBits\tmathWarmBits");
  for(int f=0;f<11;f++)for(int i=0;i<values.size();i++){double x=values.get(i),y=values.get((i+17)%values.size());System.out.println(names[f]+"\t"+bits(x)+"\t"+bits(y)+"\t"+String.format("%016x",cold[f][i])+"\t"+bits(value(f,x,y,true))+"\t"+bits(value(f,x,y,false)));}
  double[] axes={0.,-0.,1.,-1.,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NaN,Double.MIN_VALUE,-Double.MIN_VALUE,Double.MIN_NORMAL,-Double.MIN_NORMAL,Double.MAX_VALUE,-Double.MAX_VALUE};
  for(double x:axes)for(double y:axes)System.out.println("atan2\t"+bits(x)+"\t"+bits(y)+"\t"+bits(Math.atan2(x,y))+"\t"+bits(StrictMath.atan2(x,y))+"\t"+bits(Math.atan2(x,y)));
 }
}
