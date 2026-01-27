package sample.lombok;

import lombok.Builder;
import lombok.Data;
import lombok.Value;
import lombok.With;

/**
 * Test class for analyzing Lombok-generated code.
 *
 * Tests:
 * 1. @Data generated getters/setters
 * 2. @Builder pattern
 * 3. @Value (immutable) with static of() factory
 * 4. @With for immutable updates
 * 5. Builder.build() return type tracing
 */
public class LombokService {

    // ========== @Data usage ==========

    /**
     * Returns a @Data class instance.
     * Lombok generates: getters, setters, equals, hashCode, toString
     * Declared: Object, Actual: UserData
     */
    public Object createUserData(String id, String name) {
        UserData user = new UserData();
        user.setId(id);
        user.setName(name);
        user.setEmail(name.toLowerCase() + "@example.com");
        return user;
    }

    /**
     * Returns via getter chain.
     * Declared: Object, Actual: String (from getName())
     */
    public Object getUserName(UserData user) {
        return user.getName();
    }

    // ========== @Builder usage ==========

    /**
     * Returns a @Builder built instance.
     * Declared: Object, Actual: UserWithBuilder
     */
    public Object createUserWithBuilder(String id, String name, String email) {
        return UserWithBuilder.builder()
                .id(id)
                .name(name)
                .email(email)
                .build();
    }

    /**
     * Returns builder result via intermediate variable.
     * Declared: Object, Actual: UserWithBuilder
     */
    public Object createUserWithBuilderViaVariable(String id, String name) {
        UserWithBuilder.UserWithBuilderBuilder builder = UserWithBuilder.builder();
        builder.id(id);
        builder.name(name);
        builder.email(name + "@example.com");
        UserWithBuilder user = builder.build();
        return user;
    }

    /**
     * Nested builder calls.
     * Declared: Object, Actual: UserWithBuilder
     */
    public Object createNestedBuilderUser(String id) {
        return buildUser(id);
    }

    private UserWithBuilder buildUser(String id) {
        return UserWithBuilder.builder()
                .id(id)
                .name("Built User")
                .email("built@example.com")
                .build();
    }

    // ========== @Value with static of() ==========

    /**
     * Returns a @Value class via static of() factory.
     * Declared: Object, Actual: ImmutableUser
     */
    public Object createImmutableUser(String id, String name) {
        return ImmutableUser.of(id, name);
    }

    /**
     * Returns via nested of() call.
     * Declared: Object, Actual: ImmutableUser
     */
    public Object createImmutableUserNested(String id, String name) {
        return wrapImmutableUser(id, name);
    }

    private Object wrapImmutableUser(String id, String name) {
        return ImmutableUser.of(id, name);
    }

    // ========== @With for immutable updates ==========

    /**
     * Returns updated @With instance.
     * Declared: Object, Actual: ImmutableUser
     */
    public Object updateUserName(ImmutableUser user, String newName) {
        return user.withName(newName);
    }

    /**
     * Chain of @With calls.
     * Declared: Object, Actual: ImmutableUser
     */
    public Object updateUserFully(ImmutableUser user, String newId, String newName) {
        ImmutableUser updated = user.withId(newId).withName(newName);
        return updated;
    }

    // ========== Complex patterns ==========

    /**
     * Builder + method chain + return.
     * Declared: Object, Actual: UserWithBuilder
     */
    public Object complexBuilderPattern(String id) {
        return processBuilder(
                UserWithBuilder.builder()
                        .id(id)
                        .name("Complex")
        );
    }

    private UserWithBuilder processBuilder(UserWithBuilder.UserWithBuilderBuilder builder) {
        return builder.email("complex@example.com").build();
    }

    // ========== Lombok model classes ==========

    /**
     * @Data generates: getters, setters, equals, hashCode, toString, required args constructor
     */
    @Data
    public static class UserData {
        private String id;
        private String name;
        private String email;
    }

    /**
     * @Builder generates: builder pattern with fluent API
     */
    @Data
    @Builder
    public static class UserWithBuilder {
        private String id;
        private String name;
        private String email;
    }

    /**
     * @Value generates: immutable class with all-args constructor
     * Static of() is a common pattern for immutable objects
     */
    @Value
    @With
    public static class ImmutableUser {
        String id;
        String name;

        public static ImmutableUser of(String id, String name) {
            return new ImmutableUser(id, name);
        }
    }
}
