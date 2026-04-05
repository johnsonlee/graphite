package sample.lambda;

import java.util.function.Function;

public class FieldCallbackExample {
    private Function<String, String> mapper;

    public static String transform(String s) {
        return s.toUpperCase();
    }

    public FieldCallbackExample() {
        this.mapper = FieldCallbackExample::transform;
    }

    public String process(String input) {
        return mapper.apply(input);
    }
}
