package com.acme.shop.common;

public final class Ids {
    private static long next = 1;

    private Ids() {}

    public static synchronized String next(String prefix) {
        return prefix + "-" + (next++);
    }
}
