package sample.ab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test fixture for various List construction patterns.
 * Tests that backward slice can trace constants through all common ways of creating Lists.
 */
public class ListConstructionPatterns {

    private final AbClient abClient = new AbClient();

    // ==================== JDK Patterns ====================

    /**
     * Pattern: Arrays.asList(...)
     * Expected: 1001, 1002
     */
    public void arraysAsList() {
        abClient.getOptions(Arrays.asList(1001, 1002));
    }

    /**
     * Pattern: List.of(...) - Java 9+
     * Expected: 2001, 2002
     */
    public void listOf() {
        abClient.getOptions(List.of(2001, 2002));
    }

    /**
     * Pattern: Collections.singletonList(...)
     * Expected: 3001
     */
    public void collectionsSingletonList() {
        abClient.getOptions(Collections.singletonList(3001));
    }

    // ==================== Unsupported Patterns (SootUpAdapter limitation) ====================
    // These patterns are not currently tracked because SootUpAdapter doesn't model:
    // - ArrayList.add() method calls
    // - Constructor wrapping (new ArrayList<>(collection))
    // - Stream intermediate operations

    /**
     * Pattern: new ArrayList<>() with add()
     * NOT SUPPORTED - add() calls are not tracked as dataflow
     */
    public void arrayListWithAdd() {
        List<Integer> list = new ArrayList<>();
        list.add(4001);
        list.add(4002);
        abClient.getOptions(list);
    }

    /**
     * Pattern: new ArrayList<>(Arrays.asList(...))
     * NOT SUPPORTED - constructor argument is not tracked as dataflow
     */
    public void arrayListFromAsList() {
        List<Integer> list = new ArrayList<>(Arrays.asList(5001, 5002));
        abClient.getOptions(list);
    }

    /**
     * Pattern: Stream.of(...).collect(Collectors.toList())
     * NOT SUPPORTED - Stream intermediate operations not tracked
     */
    public void streamCollect() {
        List<Integer> list = Stream.of(6001, 6002).collect(Collectors.toList());
        abClient.getOptions(list);
    }

    /**
     * Pattern: Stream.of(...).toList() - Java 16+
     * Note: This compiles to Stream.toList() which is different from Collectors.toList()
     * Expected: 7001, 7002
     */
    public void streamToList() {
        List<Integer> list = Stream.of(7001, 7002).toList();
        abClient.getOptions(list);
    }

    // ==================== Enum Patterns ====================

    /**
     * Pattern: Arrays.asList with enum
     * Expected: NEW_HOMEPAGE, CHECKOUT_V2
     */
    public void arraysAsListEnum() {
        abClient.getOptionsByEnum(Arrays.asList(ExperimentId.NEW_HOMEPAGE, ExperimentId.CHECKOUT_V2));
    }

    /**
     * Pattern: List.of with enum
     * Expected: PREMIUM_FEATURES, DARK_MODE
     */
    public void listOfEnum() {
        abClient.getOptionsByEnum(List.of(ExperimentId.PREMIUM_FEATURES, ExperimentId.DARK_MODE));
    }

    /**
     * Pattern: Collections.singletonList with enum
     * Expected: NEW_HOMEPAGE
     */
    public void singletonListEnum() {
        abClient.getOptionsByEnum(Collections.singletonList(ExperimentId.NEW_HOMEPAGE));
    }

    // ==================== Static Field Patterns ====================

    private static final List<Integer> STATIC_LIST = Arrays.asList(8001, 8002);
    private static final List<Integer> STATIC_LIST_OF = List.of(8003, 8004);

    /**
     * Pattern: Static field initialized with Arrays.asList
     * Expected: 8001, 8002
     */
    public void staticFieldArraysAsList() {
        abClient.getOptions(STATIC_LIST);
    }

    /**
     * Pattern: Static field initialized with List.of
     * Expected: 8003, 8004
     */
    public void staticFieldListOf() {
        abClient.getOptions(STATIC_LIST_OF);
    }
}
