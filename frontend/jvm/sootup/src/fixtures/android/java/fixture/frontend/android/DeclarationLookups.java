package fixture.frontend.android;

/**
 * DEX shapes whose analysis asks for declarations: an enum read under a fold (enum
 * initialization safety), two enums whose constructors read each other (a declaration lookup
 * that could re-request the body being intercepted) and a function object with a bridge
 * (dispatch reads the bridge's body only).
 */
public final class DeclarationLookups {
    interface Transformer<T> {
        T apply(T value);
    }

    enum Mode { ON, OFF }

    enum Left {
        A;

        final Object peer;

        Left() {
            Right other = Right.B;
            peer = gate() ? other : null;
        }
    }

    enum Right {
        B;

        final Object peer;

        Right() {
            Left other = Left.A;
            peer = gate() ? other : null;
        }
    }

    static boolean gate() {
        return System.nanoTime() > 0;
    }

    public static int select() {
        Mode mode = Mode.ON;
        if (gate()) {
            return mode == Mode.ON ? 1 : 2;
        }
        return 0;
    }

    public static String transform(String value) {
        Transformer<String> exclaim = new Transformer<String>() {
            @Override
            public String apply(String text) {
                return text + "!";
            }
        };
        return exclaim.apply(value);
    }

    public static Object peer() {
        return Left.A.peer;
    }
}
