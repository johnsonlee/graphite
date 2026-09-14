package sample.lambda;

/**
 * Java 9+ compiles string concatenation to invokedynamic with
 * {@code makeConcatWithConstants} bootstrap method. Unlike lambda/method-reference
 * invokedynamic instructions, the bootstrap arguments contain String templates
 * instead of MethodHandle references, so the {@code targetMethods} list in
 * {@code processDynamicInvoke} will be empty, exercising the fallback path.
 */
public class StringConcatExample {

    public static String greet(String name) {
        // String concatenation compiles to invokedynamic makeConcatWithConstants
        return "Hello, " + name + "!";
    }

    public static String formatMessage(String prefix, int count) {
        // Multiple concatenations with mixed types
        return prefix + ": " + count + " items";
    }

    public static void printGreeting(String name) {
        // String concat whose result is passed to another method (void context)
        System.out.println("Welcome, " + name + "!");
    }
}
