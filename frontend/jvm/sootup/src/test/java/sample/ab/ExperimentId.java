package sample.ab;

/**
 * Enum representing AB experiment IDs.
 */
public enum ExperimentId {
    NEW_HOMEPAGE(1001),
    CHECKOUT_V2(1002),
    PREMIUM_FEATURES(1003),
    DARK_MODE(1004);

    private final int id;

    ExperimentId(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}
