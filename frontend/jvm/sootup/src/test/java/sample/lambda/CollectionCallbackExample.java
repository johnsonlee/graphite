package sample.lambda;

import java.util.function.Function;

public class CollectionCallbackExample {
    public static String upper(String s) { return s.toUpperCase(); }
    public static String lower(String s) { return s.toLowerCase(); }

    @SafeVarargs
    public static String processFirst(String input, Function<String, String>... fns) {
        return fns[0].apply(input);
    }

    public static String useVarargs(String input) {
        return processFirst(input, CollectionCallbackExample::upper, CollectionCallbackExample::lower);
    }
}
