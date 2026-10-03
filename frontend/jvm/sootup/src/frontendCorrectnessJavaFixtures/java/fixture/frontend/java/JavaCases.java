package fixture.frontend.java;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.function.Function;

/** Controlled JVM bytecode inputs for the frontend correctness gate. */
@SchemaMarker(name = "java-class", count = 3)
public final class JavaCases {
    @SchemaMarker(name = "java-field", count = 5)
    private static int stored;

    private JavaCases() {}

    @SchemaMarker(name = "java-method", count = 7)
    public static String sink(String value) {
        return value;
    }

    public static int sink(int value) {
        return value;
    }

    public static String branch(boolean enabled) {
        if (enabled) {
            return sink("java-true");
        }
        return sink("java-false");
    }

    public static int choose(int value) {
        switch (value) {
            case 1: return 11;
            case 2: return 22;
            default: return 33;
        }
    }

    public static int sparseSwitch(int value) {
        switch (value) {
            case -100: return 41;
            case 1000: return 42;
            default: return 43;
        }
    }

    public static String parameterReturn(String selected, String other) {
        return selected;
    }

    public static String nullReturn() {
        return null;
    }

    public static double constantNodes(long input) {
        long longValue = 7000000001L;
        float floatValue = 1.25f;
        double doubleValue = 2.5d;
        String stringValue = "schema-string";
        Object nullValue = null;
        if (nullValue == null && stringValue != null) {
            return longValue + floatValue + doubleValue + input;
        }
        return -1.0d;
    }

    public static long primaryLongConstant() {
        return 7000000001L;
    }

    public static long siblingLongConstant() {
        return 7000000002L;
    }

    public static FixtureMode enumNode() {
        return FixtureMode.PRIMARY;
    }

    public static String staticCall() {
        return sink("java-static");
    }

    public static String virtualCall(String value) {
        return new Worker("java:").format(value);
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
            return "recursive-end";
        }
        return recursiveCall(depth - 1);
    }

    public static int fieldsAndArrays(int[] values, int value) {
        stored = value;
        values[0] = value;
        return stored + values[0];
    }

    public static String lambda(String value) {
        Function<String, String> decorate = item -> sink("lambda:" + item);
        return decorate.apply(value);
    }

    public static String capturingLambda(String prefix, String value) {
        Function<String, String> decorate = item -> sink(prefix + item);
        return decorate.apply(value);
    }

    public static String recover(String value) {
        try {
            return sink(value);
        } catch (IllegalArgumentException ignored) {
            return sink("java-catch");
        }
    }

    public static String resources() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = JavaCases.class.getResourceAsStream("/frontend-correctness.properties")) {
            properties.load(input);
        }
        Enumeration<?> names = properties.propertyNames();
        if (names.hasMoreElements()) {
            names.nextElement();
        }
        ResourceBundle bundle = ResourceBundle.getBundle("fixture.frontend.java.messages");
        bundle.getKeys().hasMoreElements();
        return properties.getProperty("gate") + bundle.getString("message");
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
            return sink(prefix + value);
        }
    }

    static final class Box {
        int value;

        Box(int value) {
            this.value = value;
        }
    }
}
