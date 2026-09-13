package com.acme.shop.api;

import com.acme.shop.orders.OrderService;

public class ShopApi {
    private final OrderService orders;

    public ShopApi(OrderService orders) {
        this.orders = orders;
    }

    public String handlePlace(String sku, String quantity, String price) {
        return orders.place(sku, Integer.parseInt(quantity), Long.parseLong(price));
    }

    public String handleCancel(String orderId) {
        return orders.cancel(orderId);
    }

    public String handleCount() {
        return Integer.toString(orders.count());
    }
}
