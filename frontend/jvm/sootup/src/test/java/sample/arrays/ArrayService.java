package sample.arrays;

/**
 * Test class exercising array operations.
 * Used to test ARRAY_STORE and ARRAY_LOAD edge coverage.
 */
public class ArrayService {

    /**
     * Creates and populates an int array.
     */
    public int[] createIntArray() {
        int[] arr = new int[3];
        arr[0] = 10;
        arr[1] = 20;
        arr[2] = 30;
        return arr;
    }

    /**
     * Creates and populates a String array.
     */
    public String[] createStringArray() {
        String[] arr = new String[2];
        arr[0] = "hello";
        arr[1] = "world";
        return arr;
    }

    /**
     * Reads elements from an array.
     */
    public String readArrayElement(String[] arr) {
        String first = arr[0];
        return first;
    }

    /**
     * Copies one array element to another position.
     */
    public void copyElement(int[] arr) {
        int val = arr[0];
        arr[1] = val;
    }
}
