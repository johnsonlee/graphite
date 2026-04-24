package sample.resources;

import java.util.ListResourceBundle;

public class ProviderMessagesBundle_ko_KR extends ListResourceBundle {
    @Override
    protected Object[][] getContents() {
        return new Object[][]{
            {"hello", "annyeong-provider"}
        };
    }
}
