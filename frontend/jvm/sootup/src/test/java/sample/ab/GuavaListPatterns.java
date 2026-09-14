package sample.ab;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * Test fixture for Guava List construction patterns.
 */
public class GuavaListPatterns {

    private final AbClient abClient = new AbClient();

    /**
     * Pattern: ImmutableList.of(...)
     * Expected: 9001, 9002
     */
    public void immutableListOf() {
        abClient.getOptions(ImmutableList.of(9001, 9002));
    }

    /**
     * Pattern: ImmutableList.of(...) with single element
     * Expected: 9003
     */
    public void immutableListOfSingle() {
        abClient.getOptions(ImmutableList.of(9003));
    }

    /**
     * Pattern: Lists.newArrayList(...)
     * Expected: 9101, 9102
     */
    public void listsNewArrayList() {
        abClient.getOptions(Lists.newArrayList(9101, 9102));
    }

    /**
     * Pattern: ImmutableList.builder().add(...).build()
     * Expected: 9201, 9202
     */
    public void immutableListBuilder() {
        List<Integer> list = ImmutableList.<Integer>builder()
            .add(9201)
            .add(9202)
            .build();
        abClient.getOptions(list);
    }

    /**
     * Pattern: ImmutableList.copyOf(...)
     * Expected: 9301, 9302
     */
    public void immutableListCopyOf() {
        List<Integer> source = Lists.newArrayList(9301, 9302);
        abClient.getOptions(ImmutableList.copyOf(source));
    }

    // ==================== Enum Patterns ====================

    /**
     * Pattern: ImmutableList.of with enum
     * Expected: NEW_HOMEPAGE, CHECKOUT_V2
     */
    public void immutableListOfEnum() {
        abClient.getOptionsByEnum(ImmutableList.of(ExperimentId.NEW_HOMEPAGE, ExperimentId.CHECKOUT_V2));
    }

    /**
     * Pattern: Lists.newArrayList with enum
     * Expected: PREMIUM_FEATURES, DARK_MODE
     */
    public void listsNewArrayListEnum() {
        abClient.getOptionsByEnum(Lists.newArrayList(ExperimentId.PREMIUM_FEATURES, ExperimentId.DARK_MODE));
    }

    // ==================== Static Field Patterns ====================

    private static final ImmutableList<Integer> STATIC_IMMUTABLE = ImmutableList.of(9401, 9402);

    /**
     * Pattern: Static field initialized with ImmutableList.of
     * Expected: 9401, 9402
     */
    public void staticFieldImmutableList() {
        abClient.getOptions(STATIC_IMMUTABLE);
    }
}
