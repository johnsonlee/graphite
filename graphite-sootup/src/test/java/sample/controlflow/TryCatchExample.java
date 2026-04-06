package sample.controlflow;

public class TryCatchExample {

    public int tryCatchBasic(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String tryCatchFinally(String input) {
        String result;
        try {
            result = input.toUpperCase();
        } catch (Exception e) {
            result = "error";
        } finally {
            System.out.println("done");
        }
        return result;
    }

    public int multiCatch(Object input) {
        try {
            String s = (String) input;
            return Integer.parseInt(s);
        } catch (ClassCastException e) {
            return -1;
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    public int nestedTryCatch(String input) {
        try {
            try {
                return Integer.parseInt(input);
            } catch (NumberFormatException e) {
                return input.length();
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
