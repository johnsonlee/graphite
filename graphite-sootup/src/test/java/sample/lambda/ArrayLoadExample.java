package sample.lambda;

import java.util.function.Function;

/**
 * Tests array load of dynamic targets within the same method.
 * The compiler creates bytecode that stores method references into an array,
 * then loads them back to invoke.
 */
public class ArrayLoadExample {

    public static String upper(String s) { return s.toUpperCase(); }
    public static String lower(String s) { return s.toLowerCase(); }

    /**
     * Stores a method reference into an array, loads it back, and invokes it.
     * This exercises the array load dynamic target propagation path.
     */
    public static String loadFromArray(String input) {
        @SuppressWarnings("unchecked")
        Function<String, String>[] fns = new Function[2];
        fns[0] = ArrayLoadExample::upper;
        fns[1] = ArrayLoadExample::lower;
        Function<String, String> fn = fns[0];
        return fn.apply(input);
    }

    /**
     * Passes an array of method references to another method where array elements
     * are accessed - exercises array dynamic targets in argument passing.
     */
    public static String passArrayArg(String input) {
        @SuppressWarnings("unchecked")
        Function<String, String>[] fns = new Function[1];
        fns[0] = ArrayLoadExample::upper;
        return applyFirst(input, fns);
    }

    /**
     * Loads from an array that has NO dynamic targets stored.
     * This exercises the null/false branch of array load target lookup.
     */
    public static String loadFromPlainArray(String input, Function<String, String>[] fns) {
        Function<String, String> fn = fns[0];
        return fn.apply(input);
    }

    /**
     * Stores a plain (non-lambda) local into an array.
     * This exercises the array store branch where the right-side local has no dynamic targets.
     */
    public static void storeNonLambdaToArray(String[] arr, String value) {
        arr[0] = value;
    }

    private static String applyFirst(String input, Function<String, String>[] fns) {
        return fns[0].apply(input);
    }
}
