import java.util.*;
public class CapacityOracle { public static void main(String[] args) {for (int delta=16;delta>=0;delta--) {int count=Integer.MAX_VALUE-delta;try {new PriorityQueue<Object>(count,(a,b)->0);System.out.println(count+"\tOK");}catch(OutOfMemoryError error){System.out.println(count+"\t"+error.getMessage());}}} }
