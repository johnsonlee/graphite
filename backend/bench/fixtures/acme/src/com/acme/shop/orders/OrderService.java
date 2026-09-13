package com.acme.shop.orders;

import com.acme.shop.billing.BillingService;
import com.acme.shop.common.Ids;
import com.acme.shop.inventory.InventoryService;
import java.util.ArrayList;
import java.util.List;

public class OrderService {
    private final InventoryService inventory;
    private final BillingService billing;
    private final List<String> orders = new ArrayList<>();

    public OrderService(InventoryService inventory, BillingService billing) {
        this.inventory = inventory;
        this.billing = billing;
    }

    public String place(String sku, int quantity, long unitPrice) {
        if (!inventory.reserve(sku, quantity)) {
            throw new IllegalStateException("out of stock: " + sku);
        }
        String invoice = billing.invoice(sku, quantity, unitPrice);
        String id = Ids.next("order");
        orders.add(id + "=" + invoice);
        return id;
    }

    public String cancel(String orderId) {
        orders.remove(orderId);
        return billing.refund(orderId);
    }

    public int count() {
        return orders.size();
    }
}
