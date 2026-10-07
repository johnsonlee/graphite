package fx;

import java.util.function.Supplier;

/** Two experiments evaluated through one helper: the call on the function value is shared. */
public class Lambdas {
    static boolean run(Supplier<Boolean> gate) { return gate.get(); }
    static boolean checkoutEnabled() { return System.getProperty("checkout") != null; }
    static boolean darkModeEnabled() { return System.getProperty("dark") != null; }
    static void newCheckout() { }
    static void darkMode() { }

    public static void main(String[] args) {
        if (run(() -> checkoutEnabled())) newCheckout();
        if (run(() -> darkModeEnabled())) darkMode();
    }
}
