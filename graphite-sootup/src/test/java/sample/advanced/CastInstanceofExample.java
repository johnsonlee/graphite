package sample.advanced;

public class CastInstanceofExample {

    public String safeCast(Object obj) {
        if (obj instanceof String) {
            return ((String) obj).toLowerCase();
        }
        return obj.toString();
    }

    public int processList(Object obj) {
        if (obj instanceof java.util.List) {
            return ((java.util.List<?>) obj).size();
        }
        return -1;
    }

    // Multiple type checks
    public String classify(Object obj) {
        if (obj instanceof Integer) {
            return "integer:" + obj;
        } else if (obj instanceof String) {
            return "string:" + obj;
        } else if (obj instanceof Boolean) {
            return "boolean:" + obj;
        }
        return "unknown";
    }
}
