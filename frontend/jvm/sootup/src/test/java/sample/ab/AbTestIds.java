package sample.ab;

/**
 * Constants class containing all AB test IDs.
 * This is a common pattern in real-world code.
 */
public final class AbTestIds {

    private AbTestIds() {}

    // Platform-specific tests
    public static final int ANDROID_HOMEPAGE = 3001;
    public static final int IOS_HOMEPAGE = 3002;

    // Feature tests
    public static final int NEW_ONBOARDING = 3003;
    public static final int DARK_THEME = 3004;

    // Enum-based IDs
    public static final ExperimentId ANDROID_CHECKOUT = ExperimentId.CHECKOUT_V2;
    public static final ExperimentId IOS_CHECKOUT = ExperimentId.PREMIUM_FEATURES;
}
