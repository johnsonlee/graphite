package sample.lambda;

import java.util.function.Supplier;

public class ConstructorRefExample {
    private final String value;

    public ConstructorRefExample() {
        this.value = "default";
    }

    public String getValue() {
        return value;
    }

    public static Supplier<ConstructorRefExample> factory() {
        return ConstructorRefExample::new;
    }

    public static ConstructorRefExample create() {
        Supplier<ConstructorRefExample> supplier = ConstructorRefExample::new;
        return supplier.get();
    }
}
