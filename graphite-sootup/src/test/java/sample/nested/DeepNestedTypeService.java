package sample.nested;

/**
 * Test class for 10-level deeply nested generic types.
 *
 * Type hierarchy (10 levels):
 * L1<L2<L3<L4<L5<L6<L7<L8<L9<L10>>>>>>>>>
 *
 * Each level wraps the next level in a generic type parameter.
 */
public class DeepNestedTypeService {

    /**
     * Returns a 10-level deeply nested generic type.
     * L1<L2<L3<L4<L5<L6<L7<L8<L9<L10>>>>>>>>>
     */
    public Object getDeepNestedResponse() {
        L10 l10 = new L10("deepest-value");
        L9<L10> l9 = new L9<>(l10);
        L8<L9<L10>> l8 = new L8<>(l9);
        L7<L8<L9<L10>>> l7 = new L7<>(l8);
        L6<L7<L8<L9<L10>>>> l6 = new L6<>(l7);
        L5<L6<L7<L8<L9<L10>>>>> l5 = new L5<>(l6);
        L4<L5<L6<L7<L8<L9<L10>>>>>> l4 = new L4<>(l5);
        L3<L4<L5<L6<L7<L8<L9<L10>>>>>>> l3 = new L3<>(l4);
        L2<L3<L4<L5<L6<L7<L8<L9<L10>>>>>>>> l2 = new L2<>(l3);
        L1<L2<L3<L4<L5<L6<L7<L8<L9<L10>>>>>>>>> l1 = new L1<>(l2);
        return l1;
    }

    /**
     * Returns a 5-level nested generic type for comparison.
     * L1<L2<L3<L4<L5<String>>>>>
     */
    public Object getMediumNestedResponse() {
        L5<String> l5 = new L5<>("medium-value");
        L4<L5<String>> l4 = new L4<>(l5);
        L3<L4<L5<String>>> l3 = new L3<>(l4);
        L2<L3<L4<L5<String>>>> l2 = new L2<>(l3);
        L1<L2<L3<L4<L5<String>>>>> l1 = new L1<>(l2);
        return l1;
    }

    // ========== Wrapper classes (10 levels) ==========

    public static class L1<T> {
        private final T value;
        public L1(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L2<T> {
        private final T value;
        public L2(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L3<T> {
        private final T value;
        public L3(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L4<T> {
        private final T value;
        public L4(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L5<T> {
        private final T value;
        public L5(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L6<T> {
        private final T value;
        public L6(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L7<T> {
        private final T value;
        public L7(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L8<T> {
        private final T value;
        public L8(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L9<T> {
        private final T value;
        public L9(T value) { this.value = value; }
        public T getValue() { return value; }
    }

    public static class L10 {
        private final String data;
        public L10(String data) { this.data = data; }
        public String getData() { return data; }
    }
}
