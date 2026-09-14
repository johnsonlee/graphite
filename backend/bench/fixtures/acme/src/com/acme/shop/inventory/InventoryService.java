package com.acme.shop.inventory;

import com.acme.shop.common.Ids;
import java.util.HashMap;
import java.util.Map;

public class InventoryService {
    private final Map<String, Integer> stock = new HashMap<>();

    public String register(String sku, int quantity) {
        String id = Ids.next("stock");
        stock.put(sku, quantity);
        return id;
    }

    public boolean reserve(String sku, int quantity) {
        int available = stock.getOrDefault(sku, 0);
        if (available < quantity) {
            return false;
        }
        stock.put(sku, available - quantity);
        return true;
    }

    public int available(String sku) {
        return stock.getOrDefault(sku, 0);
    }
}
