package sample.lambda;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class FunctionalInterfaceExample {

    public static String toUpperCase(String s) {
        return s.toUpperCase();
    }

    public static String processWithFunctionalInterface(String input) {
        Function<String, String> fn = FunctionalInterfaceExample::toUpperCase;
        return fn.apply(input);
    }

    public static String processWithLambdaInterface(String input) {
        Function<String, String> fn = s -> s.toLowerCase();
        return fn.apply(input);
    }

    public static Supplier<String> createSupplier() {
        return () -> "hello";
    }

    public static String useSupplier() {
        Supplier<String> supplier = createSupplier();
        return supplier.get();
    }

    public static void log(String message) {
        System.out.println(message);
    }

    public static void useConsumer(String input) {
        Consumer<String> c = FunctionalInterfaceExample::log;
        c.accept(input);
    }

    public static void useRunnable() {
        Runnable r = () -> System.out.println("running");
        r.run();
    }
}
