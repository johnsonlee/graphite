package sample.jackson;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Test DTO for @JsonProperty(access = WRITE_ONLY) annotation extraction.
 * Fields with WRITE_ONLY should be treated as ignored in serialization.
 */
public class JacksonWriteOnlyDTO {

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty(value = "secret_token", access = JsonProperty.Access.WRITE_ONLY)
    private String secretToken;

    @JsonProperty(value = "internal_id", access = JsonProperty.Access.WRITE_ONLY)
    private int internalId;

    @JsonProperty("public_key")
    private String publicKey;

    public JacksonWriteOnlyDTO() {}

    public JacksonWriteOnlyDTO(String displayName, String secretToken, int internalId, String publicKey) {
        this.displayName = displayName;
        this.secretToken = secretToken;
        this.internalId = internalId;
        this.publicKey = publicKey;
    }

    @JsonProperty("display_name")
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    @JsonProperty(value = "secret_token", access = JsonProperty.Access.WRITE_ONLY)
    public String getSecretToken() { return secretToken; }
    public void setSecretToken(String secretToken) { this.secretToken = secretToken; }

    @JsonProperty(value = "internal_id", access = JsonProperty.Access.WRITE_ONLY)
    public int getInternalId() { return internalId; }
    public void setInternalId(int internalId) { this.internalId = internalId; }

    @JsonProperty("public_key")
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
}
