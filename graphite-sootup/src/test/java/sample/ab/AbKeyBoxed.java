package sample.ab;

/**
 * Enum with boxed Integer type for constructor parameter.
 * This tests the boxing pattern: Integer.valueOf(int) in bytecode.
 */
public enum AbKeyBoxed {
    BOXED_TEST_A(1111),
    BOXED_TEST_B(2222),
    BOXED_TEST_C(3333);

    private final Integer id;  // Boxed Integer, not primitive int

    AbKeyBoxed(Integer id) {
        this.id = id;
    }

    public Integer getId() {
        return id;
    }
}
