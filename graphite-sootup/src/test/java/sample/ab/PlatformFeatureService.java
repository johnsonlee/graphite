package sample.ab;

/**
 * Service that uses AB test IDs from a separate constants class.
 * Tests cross-class constant references with conditional branches.
 */
public class PlatformFeatureService {

    private final Platform platform;
    private final AbClient abClient;

    public PlatformFeatureService(Platform platform, AbClient abClient) {
        this.platform = platform;
        this.abClient = abClient;
    }

    /**
     * Pattern: Cross-class constants with conditional branches.
     * Both branches should be detected: AbTestIds.ANDROID_HOMEPAGE (3001) and AbTestIds.IOS_HOMEPAGE (3002)
     */
    public String getHomepageVariant() {
        if (platform.isAndroid()) {
            return abClient.getOption(AbTestIds.ANDROID_HOMEPAGE);
        }
        return abClient.getOption(AbTestIds.IOS_HOMEPAGE);
    }

    /**
     * Pattern: Cross-class constants with ternary operator.
     * Both values should be detected: 3003 and 3004
     */
    public String getOnboardingVariant() {
        int testId = platform.isAndroid() ? AbTestIds.NEW_ONBOARDING : AbTestIds.DARK_THEME;
        return abClient.getOption(testId);
    }

    /**
     * Pattern: Cross-class enum constants with conditional branches.
     * Both should be detected: CHECKOUT_V2 and PREMIUM_FEATURES
     */
    public String getCheckoutVariant() {
        if (platform.isAndroid()) {
            return abClient.getOption(AbTestIds.ANDROID_CHECKOUT);
        } else {
            return abClient.getOption(AbTestIds.IOS_CHECKOUT);
        }
    }

    /**
     * Pattern: Multiple conditions with different constants.
     */
    public String getFeatureVariant(String feature) {
        if ("homepage".equals(feature)) {
            return abClient.getOption(AbTestIds.ANDROID_HOMEPAGE);
        } else if ("onboarding".equals(feature)) {
            return abClient.getOption(AbTestIds.NEW_ONBOARDING);
        } else {
            return abClient.getOption(AbTestIds.DARK_THEME);
        }
    }
}
