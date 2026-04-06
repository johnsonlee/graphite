package sample.advanced;

public class RecursionExample {

    public int factorial(int n) {
        if (n <= 1) return 1;
        return n * factorial(n - 1);
    }

    // Mutual recursion
    public boolean isEven(int n) {
        if (n == 0) return true;
        return isOdd(n - 1);
    }

    public boolean isOdd(int n) {
        if (n == 0) return false;
        return isEven(n - 1);
    }
}
