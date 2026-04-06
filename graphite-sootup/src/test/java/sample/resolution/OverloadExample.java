package sample.resolution;

public class OverloadExample {
    public int compute(int x) {
        return x * 2;
    }

    public int compute(int x, int y) {
        return x + y;
    }

    public String compute(String s) {
        return s.toUpperCase();
    }

    public void callOverloads() {
        compute(42);
        compute(1, 2);
        compute("hello");
    }
}
