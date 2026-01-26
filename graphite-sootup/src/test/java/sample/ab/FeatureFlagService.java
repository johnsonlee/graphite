package sample.ab;

import org.ff4j.FF4j;

/**
 * Sample service that uses various AB testing patterns.
 *
 * This demonstrates the real-world complexity of finding AB test IDs:
 * 1. Direct integer constants: getOption(1001)
 * 2. Enum constants: getOption(ExperimentId.NEW_HOMEPAGE)
 * 3. Local variable indirection: int id = 1002; getOption(id)
 * 4. Field constants: getOption(CHECKOUT_EXPERIMENT_ID)
 * 5. String constants with FF4J: ff4j.check("feature-key")
 */
public class FeatureFlagService {

    // Field constants
    private static final int CHECKOUT_EXPERIMENT_ID = 2001;
    private static final int PAYMENT_EXPERIMENT_ID = 2002;

    private final FF4j ff4j;
    private final AbClient abClient;

    public FeatureFlagService(FF4j ff4j, AbClient abClient) {
        this.ff4j = ff4j;
        this.abClient = abClient;
    }

    // ========== Pattern 1: Direct integer constants ==========

    public String getHomepageVariant() {
        // Direct integer constant: 1001
        return abClient.getOption(1001);
    }

    public boolean isNewSearchEnabled() {
        // Direct integer constant: 1002
        return abClient.isEnabled(1002);
    }

    // ========== Pattern 2: Enum constants ==========

    public String getCheckoutVariant() {
        // Enum constant: ExperimentId.CHECKOUT_V2
        return abClient.getOption(ExperimentId.CHECKOUT_V2);
    }

    public String getPremiumVariant() {
        // Enum constant: ExperimentId.PREMIUM_FEATURES
        return abClient.getOption(ExperimentId.PREMIUM_FEATURES);
    }

    public String getDarkModeVariant() {
        // Enum constant via local variable
        ExperimentId experiment = ExperimentId.DARK_MODE;
        return abClient.getOption(experiment);
    }

    // ========== Pattern 3: Local variable indirection ==========

    public String getRecommendationVariant() {
        // Integer via local variable: 1003
        int experimentId = 1003;
        return abClient.getOption(experimentId);
    }

    public boolean isNewCartEnabled() {
        // Integer via local variable: 1004
        Integer id = 1004;
        return abClient.isEnabled(id);
    }

    // ========== Pattern 4: Field constants ==========

    public String getCheckoutFlowVariant() {
        // Field constant: CHECKOUT_EXPERIMENT_ID = 2001
        return abClient.getOption(CHECKOUT_EXPERIMENT_ID);
    }

    public boolean isNewPaymentEnabled() {
        // Field constant: PAYMENT_EXPERIMENT_ID = 2002
        return abClient.isEnabled(PAYMENT_EXPERIMENT_ID);
    }

    // ========== Pattern 5: String constants with FF4J ==========

    public String getWelcomeMessage(String userId) {
        // String constant: "new-welcome-page"
        if (ff4j.check("new-welcome-page")) {
            return "Welcome to our new experience!";
        }
        return "Welcome!";
    }

    public double calculateDiscount(double price) {
        // String constants: "premium-discount", "basic-discount"
        if (ff4j.check("premium-discount")) {
            return price * 0.8;
        }
        if (ff4j.check("basic-discount")) {
            return price * 0.9;
        }
        return price;
    }

    public boolean shouldShowBanner() {
        // String constant: "promo-banner"
        return ff4j.check("promo-banner");
    }

    public String getCheckoutFlow() {
        // String constant via local variable: "new-checkout"
        String featureId = "new-checkout";
        if (ff4j.check(featureId)) {
            return "v2";
        }
        return "v1";
    }

    // ========== Pattern 6: Enum.getId() - method call on enum constant ==========

    private static final ExperimentId CHECKOUT_EXPERIMENT = ExperimentId.CHECKOUT_V2;

    public String getCheckoutIdVariant() {
        // Enum constant + method call: ExperimentId.CHECKOUT_V2.getId()
        return abClient.getOption(ExperimentId.CHECKOUT_V2.getId());
    }

    public String getCheckoutFieldIdVariant() {
        // Field reference + method call: CHECKOUT_EXPERIMENT.getId()
        return abClient.getOption(CHECKOUT_EXPERIMENT.getId());
    }
}
