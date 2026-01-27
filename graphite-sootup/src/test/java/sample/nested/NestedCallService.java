package sample.nested;

/**
 * Test class for analyzing multi-level nested method calls.
 *
 * Tests interprocedural analysis depth:
 * 1. 2-level: a() -> b() -> Result
 * 2. 3-level: a() -> b() -> c() -> Result
 * 3. 4-level: a() -> b() -> c() -> d() -> Result
 * 4. Cross-class calls
 * 5. Mixed patterns (some levels return Object, some return concrete)
 */
public class NestedCallService {

    private final UserRepository userRepo = new UserRepository();
    private final OrderRepository orderRepo = new OrderRepository();

    // ========== 2-level nesting ==========

    /**
     * Level 1: getUser() -> fetchUser() -> User
     * Declared: Object, Actual: User
     */
    public Object getUser(String id) {
        return fetchUser(id);
    }

    private User fetchUser(String id) {
        return new User(id, "John");
    }

    // ========== 3-level nesting ==========

    /**
     * Level 1: getUserWrapped() -> wrapUser() -> createUser() -> User
     * Declared: Object, Actual: User
     */
    public Object getUserWrapped(String id) {
        return wrapUser(id);
    }

    private Object wrapUser(String id) {
        return createUser(id);
    }

    private User createUser(String id) {
        return new User(id, "Created User");
    }

    // ========== 4-level nesting ==========

    /**
     * Level 1: getDeepUser() -> level2() -> level3() -> level4() -> User
     * Declared: Object, Actual: User
     */
    public Object getDeepUser(String id) {
        return level2(id);
    }

    private Object level2(String id) {
        return level3(id);
    }

    private Object level3(String id) {
        return level4(id);
    }

    private User level4(String id) {
        return new User(id, "Deep User");
    }

    // ========== Cross-class calls ==========

    /**
     * Cross-class: getRepoUser() -> UserRepository.findById() -> User
     * Declared: Object, Actual: User
     */
    public Object getRepoUser(String id) {
        return userRepo.findById(id);
    }

    /**
     * Cross-class 2-level: getRepoUserWrapped() -> userRepo.fetchUser() -> userRepo.createUser() -> User
     * Declared: Object, Actual: User
     */
    public Object getRepoUserWrapped(String id) {
        return userRepo.fetchUser(id);
    }

    // ========== Mixed patterns (some concrete, some Object) ==========

    /**
     * Mixed: getOrder() -> processOrder() (returns Order) -> fetchOrderData() (returns Object) -> Order
     * Declared: Object, Actual: Order
     */
    public Object getOrder(String orderId) {
        return processOrder(orderId);
    }

    private Order processOrder(String orderId) {
        Object data = fetchOrderData(orderId);
        return (Order) data;
    }

    private Object fetchOrderData(String orderId) {
        return new Order(orderId, 99.99);
    }

    // ========== Multiple return paths with different depths ==========

    /**
     * Conditional with different nesting depths:
     * - Path 1: direct User
     * - Path 2: via createUser() -> User
     * Declared: Object, Actual: User
     */
    public Object getUserConditional(String id, boolean direct) {
        if (direct) {
            return new User(id, "Direct User");
        }
        return createUser(id);
    }

    // ========== Recursive-like pattern (but not infinite) ==========

    /**
     * Chain pattern: buildUser() -> addName() -> addEmail() -> User
     * Declared: Object, Actual: User
     */
    public Object buildUser(String id) {
        return addName(new User(id, null));
    }

    private Object addName(User user) {
        User named = new User(user.getId(), "Named");
        return addEmail(named);
    }

    private User addEmail(User user) {
        return new User(user.getId(), user.getName() + " with email");
    }

    // ========== Model classes ==========

    public static class User {
        private final String id;
        private final String name;

        public User(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public String getName() { return name; }
    }

    public static class Order {
        private final String orderId;
        private final double amount;

        public Order(String orderId, double amount) {
            this.orderId = orderId;
            this.amount = amount;
        }

        public String getOrderId() { return orderId; }
        public double getAmount() { return amount; }
    }

    // ========== Repository classes for cross-class testing ==========

    public static class UserRepository {
        public User findById(String id) {
            return new User(id, "Repo User");
        }

        public Object fetchUser(String id) {
            return createUser(id);
        }

        private User createUser(String id) {
            return new User(id, "Created by Repo");
        }
    }

    public static class OrderRepository {
        public Order findById(String orderId) {
            return new Order(orderId, 100.0);
        }
    }
}
