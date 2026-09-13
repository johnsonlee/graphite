package sample.resources;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.spi.ResourceBundleProvider;

public class MessagesProvider implements ResourceBundleProvider {
    @Override
    public ResourceBundle getBundle(String baseName, Locale locale) {
        if (!"sample.resources.ProviderMessagesBundle".equals(baseName)) {
            return null;
        }
        if (Locale.KOREA.equals(locale)) {
            return new ProviderMessagesBundle_ko_KR();
        }
        return new ProviderMessagesBundle();
    }
}
