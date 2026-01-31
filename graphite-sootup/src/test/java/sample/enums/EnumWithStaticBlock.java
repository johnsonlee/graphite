package sample.enums;

/**
 * An enum with a static initializer block that generates various
 * JInvokeStmt entries in the {@code <clinit>} method beyond the
 * standard enum constant initialization.
 *
 * This exercises:
 * - Non-AbstractInstanceInvokeExpr (static invocations like Thread.sleep) in findEnumInitValues
 * - Non-init method names in findEnumInitValues
 * - Local alias tracking patterns
 */
public enum EnumWithStaticBlock {
    FIRST(1, "first-item"),
    SECOND(2, "second-item");

    private final int code;
    private final String label;

    // Static block generates additional JInvokeStmt entries in <clinit>
    static {
        System.out.println("EnumWithStaticBlock initialized with " + values().length + " values");
        // Static method call (generates JStaticInvokeExpr in JInvokeStmt)
        Math.random();
    }

    EnumWithStaticBlock(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() { return code; }
    public String getLabel() { return label; }
}
