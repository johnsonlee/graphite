package sample.enums;

/**
 * An enum whose constructor takes a direct field reference without local variable assignment.
 * This ensures the JFieldRef branch in extractValueFromArg is hit.
 *
 * The bytecode should directly pass the static field as an argument to the constructor,
 * rather than assigning it to a local first.
 */
public enum DirectFieldRefEnum {
    // These directly reference enum constants from another enum
    ITEM_1(10, "item-1"),
    ITEM_2(20, "item-2");

    public static final int DEFAULT_CODE = 0;

    private final int code;
    private final String name;

    DirectFieldRefEnum(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() { return code; }
    public String getItemName() { return name; }
}
