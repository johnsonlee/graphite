package sample.resources;

import java.util.ListResourceBundle;

public class ProviderMessagesBundle extends ListResourceBundle {
    @Override
    protected Object[][] getContents() {
        return new Object[][]{
            {"hello", "world-provider"}
        };
    }
}
