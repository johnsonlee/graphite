package demo;

public final class Checkout {
    public static void main(String[] args) {
        startCheckout();
    }

    static void startCheckout() {
        enableFeature(42);
    }

    static void enableFeature(int flagId) {
        System.out.println(flagId);
    }
}
