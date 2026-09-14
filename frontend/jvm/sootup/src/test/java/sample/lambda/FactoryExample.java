package sample.lambda;

import java.util.function.Function;

public class FactoryExample {
    public static String transform(String s) {
        return s.toUpperCase();
    }

    public static Function<String, String> createTransformer() {
        return FactoryExample::transform;
    }

    public static String useFactory(String input) {
        Function<String, String> fn = createTransformer();
        return fn.apply(input);
    }
}
