package sample.lambda;

import java.util.function.Function;
import java.util.function.UnaryOperator;

public class AnonymousClassExample {
    public static String anonymousTarget(String s) {
        return s.trim();
    }

    public static String adaptedTarget(String s) {
        return s.strip();
    }

    public static String call(Function<String, String> fn, String input) {
        return fn.apply(input);
    }

    public static String useAnonymous(String input) {
        return call(new Function<String, String>() {
            @Override
            public String apply(String s) {
                return anonymousTarget(s);
            }
        }, input);
    }

    /** A method reference to a function value's own method re-binds it: {@code fn::apply}. */
    public static String useAdapted(String input) {
        Function<String, String> fn = AnonymousClassExample::adaptedTarget;
        UnaryOperator<String> adapted = fn::apply;
        return adapted.apply(input);
    }

    public static String transform(String s) {
        return s.toUpperCase();
    }

    public static Function<String, String> composeDefault() {
        Function<String, String> fn = AnonymousClassExample::transform;
        return fn.andThen(String::trim);
    }

    /** An anonymous class that inherits its implementation from an abstract superclass. */
    public abstract static class BaseFunction implements Function<String, String> {
        @Override
        public String apply(String s) {
            return inheritedTarget(s);
        }
    }

    public static String inheritedTarget(String s) {
        return s.intern();
    }

    public static String useInherited(String input) {
        return call(new BaseFunction() { }, input);
    }

    /** Re-binds a function value it did not create: what {@code fn::apply} runs is only known from callers. */
    public static UnaryOperator<String> adapt(Function<String, String> fn) {
        return fn::apply;
    }

    public static String deferredAdaptedTarget(String s) {
        return s.stripLeading();
    }

    public static String useDeferredAdapted(String input) {
        return adapt(AnonymousClassExample::deferredAdaptedTarget).apply(input);
    }

    /** A function value crosses an interface: the caller sees {@code Invoker}, the body is in {@code Impl}. */
    public interface Invoker {
        String invoke(Function<String, String> fn, String input);
    }

    public static class Impl implements Invoker {
        @Override
        public String invoke(Function<String, String> fn, String input) {
            return fn.apply(input);
        }
    }

    public static String overrideTarget(String s) {
        return s.toLowerCase();
    }

    public static String useThroughInterface(Invoker invoker, String input) {
        return invoker.invoke(AnonymousClassExample::overrideTarget, input);
    }

    /** A function value returned across an interface: the caller sees {@code Factory.make}. */
    public interface Factory {
        Function<String, String> make();
    }

    public static class FactoryImpl implements Factory {
        @Override
        public Function<String, String> make() {
            return AnonymousClassExample::factoryTarget;
        }
    }

    public static String factoryTarget(String s) {
        return s.stripTrailing();
    }

    public static String useFactory(Factory factory, String input) {
        return factory.make().apply(input);
    }

    /** An anonymous class that is not a function value: allocating it runs none of its own methods. */
    public static Object plainAnonymous() {
        return new Object() {
            public String neverCalled() {
                return "never";
            }

            @Override
            public String toString() {
                return "plain";
            }
        };
    }
}
