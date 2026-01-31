package sample.simple;

import java.util.List;
import java.util.Map;

/**
 * A simple service class used in JAR/WAR packaging tests.
 * Has both generic fields and methods for BytecodeSignatureReader coverage.
 */
public class SimpleService {

    private List<String> items;
    private Map<String, Integer> scores;

    public SimpleService() {
        this.items = new java.util.ArrayList<>();
        this.scores = new java.util.HashMap<>();
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items;
    }

    public Map<String, Integer> getScores() {
        return scores;
    }

    public String processItem(String item) {
        items.add(item);
        return item.toUpperCase();
    }

    public static void main(String[] args) {
        SimpleService service = new SimpleService();
        service.processItem("test");
    }
}
