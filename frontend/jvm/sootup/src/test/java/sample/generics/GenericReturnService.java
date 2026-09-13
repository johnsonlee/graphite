package sample.generics;

import java.util.*;

/**
 * Test class for analyzing generic return types.
 *
 * Tests:
 * 1. List<T> return types
 * 2. Map<K, V> return types
 * 3. Optional<T> return types
 * 4. Nested generics like List<Map<String, User>>
 */
public class GenericReturnService {

    // ========== List<T> patterns ==========

    /**
     * Declared: List (raw), Actual: List<User>
     */
    public List getUsers() {
        List<User> users = new ArrayList<>();
        users.add(new User("1", "John"));
        users.add(new User("2", "Jane"));
        return users;
    }

    /**
     * Declared: Object, Actual: List<User> (via method call)
     */
    public Object getUsersAsObject() {
        return fetchUsers();
    }

    private List<User> fetchUsers() {
        return Arrays.asList(new User("1", "John"));
    }

    // ========== Map<K, V> patterns ==========

    /**
     * Declared: Map (raw), Actual: Map<String, Order>
     */
    public Map getOrderMap() {
        Map<String, Order> orders = new HashMap<>();
        orders.put("order-1", new Order("order-1", 100.0));
        return orders;
    }

    /**
     * Declared: Object, Actual: Map<String, User>
     */
    public Object getUserMapAsObject() {
        Map<String, User> userMap = new HashMap<>();
        userMap.put("user-1", new User("1", "John"));
        return userMap;
    }

    // ========== Optional<T> patterns ==========

    /**
     * Declared: Optional (raw), Actual: Optional<User>
     */
    public Optional findUserById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.of(new User(id, "Found User"));
    }

    /**
     * Declared: Object, Actual: Optional<Order>
     */
    public Object findOrderAsObject(String orderId) {
        Optional<Order> result = Optional.of(new Order(orderId, 50.0));
        return result;
    }

    // ========== Nested generics ==========

    /**
     * Declared: Object, Actual: List<Map<String, User>>
     */
    public Object getNestedGeneric() {
        List<Map<String, User>> result = new ArrayList<>();
        Map<String, User> group1 = new HashMap<>();
        group1.put("admin", new User("1", "Admin"));
        result.add(group1);
        return result;
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
}
