package sample.lambda;

import java.util.function.Function;

public class HigherOrderExample {
    public static Function<String, String> createTransformer(boolean upper) {
        if (upper) {
            return String::toUpperCase;
        } else {
            return String::toLowerCase;
        }
    }

    public static String useHigherOrder(String input) {
        Function<String, String> fn = createTransformer(true);
        return fn.apply(input);
    }
}
