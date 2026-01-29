package sample.ab;

/**
 * Enum representing AB test keys with integer IDs.
 * This pattern is common in real-world AB testing frameworks.
 */
public enum AbKey {
    SIMPLE_TEST_ID(1234),
    CHECKOUT_FLOW(5678),
    NEW_ONBOARDING(9999);

    private final int id;

    AbKey(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
