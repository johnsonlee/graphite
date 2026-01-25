package sample.controller;

import org.springframework.http.ResponseEntity;

/**
 * Sample REST controller that demonstrates the return type analysis problem.
 *
 * Problem: Methods declare generic return types (Object, ResponseEntity<?>)
 * but actually return specific types (UserDTO, OrderDTO, etc.)
 *
 * Goal: Static analysis should find the actual types being returned.
 */
public class UserController {

    /**
     * Declared return type: Object
     * Actual return type: UserDTO
     */
    public Object getUser(String userId) {
        UserDTO user = new UserDTO(userId, "John", "john@example.com");
        return user;
    }

    /**
     * Declared return type: Object
     * Actual return types: UserDTO or ErrorResponse (conditional)
     */
    public Object getUserWithError(String userId) {
        if (userId == null) {
            return new ErrorResponse("400", "userId is required");
        }
        return new UserDTO(userId, "John", "john@example.com");
    }

    /**
     * Declared return type: ResponseEntity<?>
     * Actual return type: ResponseEntity<UserDTO>
     */
    public ResponseEntity<?> getUserAsResponseEntity(String userId) {
        UserDTO user = new UserDTO(userId, "John", "john@example.com");
        return ResponseEntity.ok(user);
    }

    /**
     * Declared return type: ResponseEntity<?>
     * Actual return types: ResponseEntity<UserDTO> or ResponseEntity<ErrorResponse>
     */
    public ResponseEntity<?> getUserWithErrorHandling(String userId) {
        if (userId == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("400", "userId is required"));
        }
        UserDTO user = new UserDTO(userId, "John", "john@example.com");
        return ResponseEntity.ok(user);
    }

    /**
     * Declared return type: Object
     * Actual return type: OrderDTO
     */
    public Object getOrder(String orderId) {
        return new OrderDTO(orderId, "user-1", new java.math.BigDecimal("99.99"));
    }

    /**
     * Local variable indirection case.
     * Declared return type: Object
     * Actual return type: UserDTO
     */
    public Object getUserViaLocalVariable(String userId) {
        Object result = new UserDTO(userId, "Jane", "jane@example.com");
        return result;
    }

    /**
     * Method call indirection case.
     * Declared return type: Object
     * Actual return type: UserDTO (from createUser method)
     */
    public Object getUserViaMethodCall(String userId) {
        return createUser(userId);
    }

    private UserDTO createUser(String userId) {
        return new UserDTO(userId, "Created", "created@example.com");
    }
}
