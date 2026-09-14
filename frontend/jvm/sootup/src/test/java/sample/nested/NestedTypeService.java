package sample.nested;

import java.util.List;
import java.util.ArrayList;

/**
 * Test class for analyzing nested type structures.
 *
 * Scenario: API responses with nested generic types and Object fields.
 *
 * Type hierarchy:
 * - ApiResponse<T> { T data; Object metadata; }
 *   - ApiResponse<PageData<User>> where PageData<E> { List<E> items; Object extra; }
 *     - PageData.items is List<User>
 *     - PageData.extra could be PageMetadata or null
 *
 * The analysis should discover:
 * 1. What concrete types are used for generic parameters
 * 2. What actual types are assigned to Object fields
 * 3. Nested generic type resolution (e.g., ApiResponse<PageData<User>> -> data.items is List<User>)
 */
public class NestedTypeService {

    // ========== Simple generic response ==========

    /**
     * Returns ApiResponse<User>
     * - data field: User
     * - metadata field: assigned RequestMetadata
     */
    public Object getUserResponse(String userId) {
        ApiResponse<User> response = new ApiResponse<>();
        response.setData(new User(userId, "John"));
        response.setMetadata(new RequestMetadata("req-123", System.currentTimeMillis()));
        return response;
    }

    /**
     * Returns ApiResponse<Order>
     * - data field: Order
     * - metadata field: assigned ResponseMetadata
     */
    public Object getOrderResponse(String orderId) {
        ApiResponse<Order> response = new ApiResponse<>();
        response.setData(new Order(orderId, 99.99));
        response.setMetadata(new ResponseMetadata(200, "OK"));
        return response;
    }

    // ========== Nested generic response ==========

    /**
     * Returns ApiResponse<PageData<User>>
     * - data field: PageData<User>
     *   - items field: List<User>
     *   - extra field: assigned PageMetadata
     * - metadata field: assigned RequestMetadata
     */
    public Object getUserListResponse() {
        PageData<User> pageData = new PageData<>();
        List<User> users = new ArrayList<>();
        users.add(new User("1", "John"));
        users.add(new User("2", "Jane"));
        pageData.setItems(users);
        pageData.setExtra(new PageMetadata(1, 10, 100));

        ApiResponse<PageData<User>> response = new ApiResponse<>();
        response.setData(pageData);
        response.setMetadata(new RequestMetadata("req-456", System.currentTimeMillis()));
        return response;
    }

    /**
     * Returns ApiResponse<PageData<Order>>
     * - data field: PageData<Order>
     *   - items field: List<Order>
     *   - extra field: assigned null (testing null case)
     */
    public Object getOrderListResponse() {
        PageData<Order> pageData = new PageData<>();
        List<Order> orders = new ArrayList<>();
        orders.add(new Order("o1", 50.0));
        orders.add(new Order("o2", 75.0));
        pageData.setItems(orders);
        pageData.setExtra(null);  // extra is null

        ApiResponse<PageData<Order>> response = new ApiResponse<>();
        response.setData(pageData);
        return response;
    }

    // ========== Deep nested types ==========

    /**
     * Returns ApiResponse<PageData<UserDetail>>
     * Where UserDetail contains:
     * - profile: Object (assigned ProfileInfo or AdminInfo)
     * - addresses: List<Address>
     */
    public Object getUserDetailResponse(String userId, boolean isAdmin) {
        UserDetail detail = new UserDetail();
        detail.setId(userId);
        detail.setName("Detailed User");

        if (isAdmin) {
            detail.setProfile(new AdminInfo("admin", List.of("READ", "WRITE", "DELETE")));
        } else {
            detail.setProfile(new ProfileInfo("user", "Regular user profile"));
        }

        List<Address> addresses = new ArrayList<>();
        addresses.add(new Address("123 Main St", "City", "12345"));
        detail.setAddresses(addresses);

        PageData<UserDetail> pageData = new PageData<>();
        List<UserDetail> details = new ArrayList<>();
        details.add(detail);
        pageData.setItems(details);
        pageData.setExtra(new PageMetadata(1, 1, 1));

        ApiResponse<PageData<UserDetail>> response = new ApiResponse<>();
        response.setData(pageData);
        response.setMetadata(new RequestMetadata("req-789", System.currentTimeMillis()));
        return response;
    }

    // ========== Generic container types ==========

    /**
     * Generic response wrapper pattern.
     * Declared: Object
     * Actual: Result<User, ErrorInfo>
     */
    public Object getResultWrapper(String userId, boolean success) {
        if (success) {
            return Result.success(new User(userId, "Success User"));
        } else {
            return Result.failure(new ErrorInfo("USER_NOT_FOUND", "User not found: " + userId));
        }
    }

    // ========== Model classes ==========

    /**
     * Generic API response wrapper.
     * @param <T> The type of the data payload
     */
    public static class ApiResponse<T> {
        private T data;
        private Object metadata;  // Could be RequestMetadata, ResponseMetadata, etc.

        public T getData() { return data; }
        public void setData(T data) { this.data = data; }
        public Object getMetadata() { return metadata; }
        public void setMetadata(Object metadata) { this.metadata = metadata; }
    }

    /**
     * Generic paginated data container.
     * @param <E> The type of items in the page
     */
    public static class PageData<E> {
        private List<E> items;
        private Object extra;  // Could be PageMetadata, sorting info, etc.

        public List<E> getItems() { return items; }
        public void setItems(List<E> items) { this.items = items; }
        public Object getExtra() { return extra; }
        public void setExtra(Object extra) { this.extra = extra; }
    }

    /**
     * Generic result type (Either pattern).
     */
    public static class Result<T, E> {
        private final T value;
        private final E error;
        private final boolean success;

        private Result(T value, E error, boolean success) {
            this.value = value;
            this.error = error;
            this.success = success;
        }

        public static <T, E> Result<T, E> success(T value) {
            return new Result<>(value, null, true);
        }

        public static <T, E> Result<T, E> failure(E error) {
            return new Result<>(null, error, false);
        }

        public T getValue() { return value; }
        public E getError() { return error; }
        public boolean isSuccess() { return success; }
    }

    // ========== Domain classes ==========

    public static class User {
        private final String id;
        private final String name;
        public User(String id, String name) { this.id = id; this.name = name; }
        public String getId() { return id; }
        public String getName() { return name; }
    }

    public static class UserDetail {
        private String id;
        private String name;
        private Object profile;        // Could be ProfileInfo or AdminInfo
        private List<Address> addresses;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Object getProfile() { return profile; }
        public void setProfile(Object profile) { this.profile = profile; }
        public List<Address> getAddresses() { return addresses; }
        public void setAddresses(List<Address> addresses) { this.addresses = addresses; }
    }

    public static class Order {
        private final String orderId;
        private final double amount;
        public Order(String orderId, double amount) { this.orderId = orderId; this.amount = amount; }
        public String getOrderId() { return orderId; }
        public double getAmount() { return amount; }
    }

    public static class Address {
        private final String street;
        private final String city;
        private final String zipCode;
        public Address(String street, String city, String zipCode) {
            this.street = street; this.city = city; this.zipCode = zipCode;
        }
        public String getStreet() { return street; }
        public String getCity() { return city; }
        public String getZipCode() { return zipCode; }
    }

    public static class ProfileInfo {
        private final String type;
        private final String description;
        public ProfileInfo(String type, String description) { this.type = type; this.description = description; }
        public String getType() { return type; }
        public String getDescription() { return description; }
    }

    public static class AdminInfo {
        private final String role;
        private final List<String> permissions;
        public AdminInfo(String role, List<String> permissions) { this.role = role; this.permissions = permissions; }
        public String getRole() { return role; }
        public List<String> getPermissions() { return permissions; }
    }

    public static class RequestMetadata {
        private final String requestId;
        private final long timestamp;
        public RequestMetadata(String requestId, long timestamp) { this.requestId = requestId; this.timestamp = timestamp; }
        public String getRequestId() { return requestId; }
        public long getTimestamp() { return timestamp; }
    }

    public static class ResponseMetadata {
        private final int statusCode;
        private final String message;
        public ResponseMetadata(int statusCode, String message) { this.statusCode = statusCode; this.message = message; }
        public int getStatusCode() { return statusCode; }
        public String getMessage() { return message; }
    }

    public static class PageMetadata {
        private final int page;
        private final int size;
        private final int total;
        public PageMetadata(int page, int size, int total) { this.page = page; this.size = size; this.total = total; }
        public int getPage() { return page; }
        public int getSize() { return size; }
        public int getTotal() { return total; }
    }

    public static class ErrorInfo {
        private final String code;
        private final String message;
        public ErrorInfo(String code, String message) { this.code = code; this.message = message; }
        public String getCode() { return code; }
        public String getMessage() { return message; }
    }
}
