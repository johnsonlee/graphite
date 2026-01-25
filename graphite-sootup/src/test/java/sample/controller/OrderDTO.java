package sample.controller;

import java.math.BigDecimal;

/**
 * Data transfer object for Order.
 */
public class OrderDTO {
    private String orderId;
    private String userId;
    private BigDecimal amount;

    public OrderDTO() {}

    public OrderDTO(String orderId, String userId, BigDecimal amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
}
