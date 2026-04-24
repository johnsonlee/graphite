package sample.resources;

import java.util.ListResourceBundle;

public class MessagesListBundle extends ListResourceBundle {
    @Override
    protected Object[][] getContents() {
        return new Object[][]{
            {"hello", "world-list"}
        };
    }
}
