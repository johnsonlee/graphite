package fx;

import java.util.function.BiFunction;
import java.util.function.Function;

/** A call on a function value reached through a reference to the function type's own method. */
public class Unbound {
    static Function<String, String> seed() { return Unbound::target; }
    static String target(String s) { return s + "!"; }

    public static String use(String input) {
        BiFunction<Function<String, String>, String, String> invoke = Function::apply;
        return invoke.apply(seed(), input);
    }
}
