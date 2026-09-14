package sample.lambda;

import java.util.List;
import java.util.stream.Collectors;

public class LambdaExample {

    public interface Processor {
        String process(String input);
    }

    public static String transform(String input) {
        return input.toUpperCase();
    }

    public static List<String> processWithMethodRef(List<String> items) {
        return items.stream()
            .map(LambdaExample::transform)
            .collect(Collectors.toList());
    }

    public static List<String> processWithLambda(List<String> items) {
        return items.stream()
            .map(s -> s.toLowerCase())
            .collect(Collectors.toList());
    }

    public Processor getProcessor() {
        return LambdaExample::transform;
    }

    /**
     * Lambda that captures a local variable. The captured variable becomes an argument
     * to the invokedynamic instruction, exercising the argNodeIds.forEach branch
     * in processDynamicInvoke when targetMethods is non-empty.
     */
    public static Processor createPrefixProcessor(String prefix) {
        // 'prefix' is captured by the lambda -- it becomes an arg to invokedynamic
        return input -> prefix + input;
    }

    /**
     * Instance method reference that captures 'this'. The receiver becomes an argument
     * to the invokedynamic instruction.
     */
    public Processor getInstanceProcessor() {
        return this::instanceTransform;
    }

    public String instanceTransform(String input) {
        return input.trim();
    }
}
