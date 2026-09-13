package com.acme.shop.billing;

import com.acme.shop.common.Ids;
import com.acme.shop.inventory.InventoryService;

public class BillingService {
    private final InventoryService inventory;

    public BillingService(InventoryService inventory) {
        this.inventory = inventory;
    }

    public String invoice(String sku, int quantity, long unitPrice) {
        if (inventory.available(sku) < 0) {
            throw new IllegalStateException("negative stock for " + sku);
        }
        String id = Ids.next("invoice");
        long total = unitPrice * quantity;
        return id + ":" + total;
    }

    public String refund(String invoiceId) {
        return Ids.next("refund") + " for " + invoiceId;
    }
}
