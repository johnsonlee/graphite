package com.acme.shop.api;

import com.acme.shop.billing.BillingService;
import com.acme.shop.inventory.InventoryService;
import com.acme.shop.orders.OrderService;

public class Main {
    public static void main(String[] args) {
        InventoryService inventory = new InventoryService();
        inventory.register("widget", 10);
        BillingService billing = new BillingService(inventory);
        OrderService orders = new OrderService(inventory, billing);
        ShopApi api = new ShopApi(orders);
        System.out.println(api.handlePlace("widget", "2", "5"));
        System.out.println(api.handleCount());
    }
}
