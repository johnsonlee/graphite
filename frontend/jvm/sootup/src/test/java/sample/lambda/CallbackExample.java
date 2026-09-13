package sample.lambda;

import java.util.function.Function;

public class CallbackExample {
    public static String transform(String s) {
        return s.toUpperCase();
    }

    public static String processWithCallback(String input, Function<String, String> callback) {
        return callback.apply(input);
    }

    public static String useCallback(String input) {
        return processWithCallback(input, CallbackExample::transform);
    }
}
