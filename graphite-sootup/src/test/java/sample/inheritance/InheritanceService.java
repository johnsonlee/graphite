package sample.inheritance;

/**
 * Test class for cross-method field tracking and inheritance.
 */
public class InheritanceService {

    // ========== Cross-method field tracking ==========

    /**
     * Scenario: Field set in one method, returned in another.
     */
    public static class CacheService {
        private Object cachedData;

        public void initCache() {
            // Field is set here
            cachedData = new UserData("cached-user", 100);
        }

        public Object getCache() {
            // Field is returned here - should discover UserData type
            return cachedData;
        }

        public void updateCache(boolean isOrder) {
            // Conditional assignment - should find both types
            if (isOrder) {
                cachedData = new OrderData("order-123", 99.99);
            } else {
                cachedData = new UserData("user-456", 50);
            }
        }
    }

    // ========== Inheritance hierarchy ==========

    /**
     * Base class with Object field.
     */
    public static class BaseResponse {
        protected Object payload;
        protected Object meta;

        public Object getPayload() {
            return payload;
        }

        public void setPayload(Object payload) {
            this.payload = payload;
        }

        public Object getMeta() {
            return meta;
        }

        public void setMeta(Object meta) {
            this.meta = meta;
        }
    }

    /**
     * Subclass that assigns specific types to parent's Object fields.
     */
    public static class UserResponse extends BaseResponse {
        public UserResponse(String userId) {
            // Assigns to parent's Object field
            this.payload = new UserData(userId, 0);
            this.meta = new UserMeta("user-response");
        }

        public static UserResponse create(String userId, int score) {
            UserResponse response = new UserResponse(userId);
            response.setPayload(new UserData(userId, score));
            return response;
        }
    }

    /**
     * Another subclass with different types.
     */
    public static class OrderResponse extends BaseResponse {
        public OrderResponse(String orderId, double amount) {
            this.payload = new OrderData(orderId, amount);
            this.meta = new OrderMeta("order-response", System.currentTimeMillis());
        }
    }

    // ========== Service methods ==========

    public Object getUserResponse(String userId) {
        return UserResponse.create(userId, 100);
    }

    public Object getOrderResponse(String orderId) {
        return new OrderResponse(orderId, 50.0);
    }

    public Object getCachedData() {
        CacheService cache = new CacheService();
        cache.initCache();
        return cache.getCache();
    }

    // ========== Data classes ==========

    public static class UserData {
        private final String userId;
        private final int score;

        public UserData(String userId, int score) {
            this.userId = userId;
            this.score = score;
        }

        public String getUserId() { return userId; }
        public int getScore() { return score; }
    }

    public static class OrderData {
        private final String orderId;
        private final double amount;

        public OrderData(String orderId, double amount) {
            this.orderId = orderId;
            this.amount = amount;
        }

        public String getOrderId() { return orderId; }
        public double getAmount() { return amount; }
    }

    public static class UserMeta {
        private final String type;

        public UserMeta(String type) {
            this.type = type;
        }

        public String getType() { return type; }
    }

    public static class OrderMeta {
        private final String type;
        private final long timestamp;

        public OrderMeta(String type, long timestamp) {
            this.type = type;
            this.timestamp = timestamp;
        }

        public String getType() { return type; }
        public long getTimestamp() { return timestamp; }
    }
}
