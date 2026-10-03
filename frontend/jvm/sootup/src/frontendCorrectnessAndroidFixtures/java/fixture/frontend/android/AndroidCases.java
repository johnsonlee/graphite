package fixture.frontend.android;

/** Controlled Java-to-DEX inputs for the Android frontend correctness gate. */
public final class AndroidCases {
    private static int stored;

    private AndroidCases() {}

    public static String sink(String value, int code) {
        return value + code;
    }

    public static int sink(int value) {
        return value;
    }

    public static String staticArguments() {
        return sink("android", 7);
    }

    public static String branch(boolean enabled) {
        if (enabled) {
            return trueBranch();
        }
        return falseBranch();
    }

    public static String relationalBranch(int value) {
        if (value < 10) {
            return lower();
        }
        return upper();
    }

    public static int denseSwitch(int value) {
        switch (value) {
            case 1: return 501;
            case 2: return 502;
            default: return 503;
        }
    }

    public static int sparseSwitch(int value) {
        switch (value) {
            case -100: return 601;
            case 1000: return 602;
            default: return 603;
        }
    }

    public static String parameterReturn(String selected, String other) {
        return selected;
    }

    public static String nullReturn() {
        return null;
    }

    public static double constantNodes(long input) {
        long longValue = 9000000001L;
        float floatValue = 2.25f;
        double doubleValue = 5.5d;
        return longValue + floatValue + doubleValue + input;
    }

    public static long primaryLongConstant() {
        return 9000000001L;
    }

    public static long siblingLongConstant() {
        return 9000000002L;
    }

    public static String virtualCall(String value) {
        return new Worker("android:").format(value);
    }

    public static String interfaceCall(String value) {
        Formatter formatter = new Worker("interface:");
        return formatter.format(value);
    }

    public static int constructorCall(int value) {
        return new Box(value).value;
    }

    public static int instanceField(int value) {
        Box box = new Box(0);
        box.value = value;
        return box.value;
    }

    public static String recursiveCall(int depth) {
        if (depth == 0) {
            return "android-end";
        }
        return recursiveCall(depth - 1);
    }

    public static long wideArguments() {
        return wideSink(7L, 3.5d);
    }

    private static long wideSink(long value, double ratio) {
        return value + (long) ratio;
    }

    private static String trueBranch() {
        return "android-true";
    }

    private static String falseBranch() {
        return "android-false";
    }

    private static String lower() {
        return "lower";
    }

    private static String upper() {
        return "upper";
    }

    public static void fieldStore(int value) {
        stored = value;
    }

    public static int fieldLoad() {
        return stored;
    }

    public static void arrayStore(int[] values, int value) {
        values[0] = value;
    }

    public static int arrayLoad(int[] values) {
        return values[0];
    }

    private interface Formatter {
        String format(String value);
    }

    private static final class Worker implements Formatter {
        private final String prefix;

        private Worker(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String format(String value) {
            return sink(prefix + value, 8);
        }
    }

    static final class Box {
        int value;

        Box(int value) {
            this.value = value;
        }
    }
}
