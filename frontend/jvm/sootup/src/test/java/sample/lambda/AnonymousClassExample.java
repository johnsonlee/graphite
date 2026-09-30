package sample.lambda;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
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

    /** The implementation is inherited from a class that does not itself implement the interface. */
    public static class Base {
        public String invoke(Function<String, String> fn, String input) {
            return fn.apply(input);
        }
    }

    public static class Child extends Base implements Invoker {
    }

    public static String inheritedInvokeTarget(String s) {
        return s.concat("!");
    }

    public static String useInheritedInvoker(Invoker invoker, String input) {
        return invoker.invoke(AnonymousClassExample::inheritedInvokeTarget, input);
    }

    public static class FactoryBase {
        public Function<String, String> make() {
            return AnonymousClassExample::inheritedFactoryTarget;
        }
    }

    public static class FactoryChild extends FactoryBase implements Factory {
    }

    public static String inheritedFactoryTarget(String s) {
        return s.repeat(2);
    }

    public static String useInheritedFactory(Factory factory, String input) {
        return factory.make().apply(input);
    }

    /** A method reference to a method that takes a function value: the argument reaches its parameter. */
    public static String run(Function<String, String> fn) {
        return fn.apply("x");
    }

    public static String feedbackTarget(String s) {
        return s.toUpperCase();
    }

    public static Function<String, String> seedFeedback() {
        return AnonymousClassExample::feedbackTarget;
    }

    public static String useInvokerHandle() {
        Function<Function<String, String>, String> invoker = AnonymousClassExample::run;
        return invoker.apply(seedFeedback());
    }

    /** A method reference to a method that returns a function value: the result carries it. */
    public static String useMakerHandle(String input) {
        Supplier<Function<String, String>> maker = AnonymousClassExample::seedFeedback;
        return maker.get().apply(input);
    }

    /** An unbound reference to the function type's own method: the first argument is the receiver. */
    public static String useUnboundApply(String input) {
        BiFunction<Function<String, String>, String, String> invoke = Function::apply;
        return invoke.apply(seedFeedback(), input);
    }

    /** An instance helper named like a static interface method implements nothing. */
    public static Function<String, String> plainFunction() {
        return new Function<String, String>() {
            @Override
            public String apply(String s) {
                return anonymousTarget(s);
            }

            public Function<String, String> identity() {
                return seedFeedback();
            }
        };
    }

    /** Five distinct re-bindings of one function value, none of them a cycle. */
    public static UnaryOperator<String> a1(Function<String, String> fn) {
        return fn::apply;
    }

    public static UnaryOperator<String> a2(Function<String, String> fn) {
        return fn::apply;
    }

    public static UnaryOperator<String> a3(Function<String, String> fn) {
        return fn::apply;
    }

    public static UnaryOperator<String> a4(Function<String, String> fn) {
        return fn::apply;
    }

    public static UnaryOperator<String> a5(Function<String, String> fn) {
        return fn::apply;
    }

    public static String chainTarget(String s) {
        return s.trim();
    }

    public static String useChain(String input) {
        return a5(a4(a3(a2(a1(AnonymousClassExample::chainTarget))))).apply(input);
    }

    /** The same site re-binding its own result: must terminate, and still resolve. */
    public static String useLoop(String input, int n) {
        Function<String, String> fn = AnonymousClassExample::chainTarget;
        for (int i = 0; i < n; i++) {
            fn = fn::apply;
        }
        return fn.apply(input);
    }

    /** An anonymous class whose supertype is outside the view: only its `run` is a callback. */
    public static Runnable plainRunnable() {
        return new Runnable() {
            @Override
            public void run() {
            }

            public String neverCalledHelper() {
                return "never";
            }
        };
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
