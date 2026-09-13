package sample.advanced;

public class EdgeCaseExample {

    // Empty method
    public void emptyMethod() {
    }

    // Method with only return
    public int returnOnly() {
        return 42;
    }

    // Void method with explicit return
    public void voidReturn() {
        if (true) return;
        System.out.println("unreachable");
    }

    // Synchronized method
    public synchronized int syncMethod(int x) {
        return x + 1;
    }

    // Synchronized block
    private final Object lock = new Object();
    public int syncBlock(int x) {
        synchronized (lock) {
            return x + 1;
        }
    }

    // Method with many parameters
    public int manyParams(int a, int b, int c, int d, int e,
                          int f, int g, int h, int i, int j) {
        return a + b + c + d + e + f + g + h + i + j;
    }

    // Null constant usage
    public String nullCheck(String input) {
        if (input == null) {
            return null;
        }
        return input;
    }
}
