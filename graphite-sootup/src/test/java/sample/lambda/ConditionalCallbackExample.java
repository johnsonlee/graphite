package sample.lambda;

import java.util.function.Function;

public class ConditionalCallbackExample {
    public static String upper(String s) { return s.toUpperCase(); }
    public static String lower(String s) { return s.toLowerCase(); }

    public static String process(String input, boolean useUpper) {
        Function<String, String> fn;
        if (useUpper) {
            fn = ConditionalCallbackExample::upper;
        } else {
            fn = ConditionalCallbackExample::lower;
        }
        return fn.apply(input);
    }
}
