package sample.resources;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.Reader;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.Properties;
import java.util.ResourceBundle.Control;
import java.util.ResourceBundle;

public class ResourceConfig {
    public String featureMode() {
        Properties properties = new Properties();
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
        }
        return properties.getProperty("feature.mode");
    }

    public String featureModeXml() {
        Properties properties = new Properties();
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("application.xml")) {
            if (input != null) {
                properties.loadFromXML(input);
            }
        } catch (IOException ignored) {
        }
        return properties.getProperty("feature.mode");
    }

    public String featureModeReader() {
        Properties properties = new Properties();
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                try (Reader reader = new InputStreamReader(input)) {
                    properties.load(reader);
                }
            }
        } catch (IOException ignored) {
        }
        return properties.getProperty("feature.mode");
    }

    @SuppressWarnings("unchecked")
    public Boolean jsonFeatureEnabled() {
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("application.json")) {
            if (input != null) {
                try (Reader reader = new InputStreamReader(input)) {
                    Object parsed = new Gson().fromJson(reader, Object.class);
                    if (parsed instanceof java.util.Map<?, ?> root) {
                        Object feature = root.get("feature");
                        if (feature instanceof java.util.Map<?, ?> featureMap) {
                            Object enabled = featureMap.get("enabled");
                            if (enabled instanceof Boolean value) {
                                return value;
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public String xmlServiceEndpoint() {
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("config.xml")) {
            if (input != null) {
                return javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(input)
                    .getElementsByTagName("endpoint")
                    .item(0)
                    .getTextContent();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public String message() {
        return ResourceBundle.getBundle("messages").getString("hello");
    }

    public Object messageObject() {
        return ResourceBundle.getBundle("messages").getObject("hello");
    }

    public String messageKeys() {
        return ResourceBundle.getBundle("messages").getKeys().nextElement();
    }

    public String messageKo() {
        return ResourceBundle.getBundle("messages", Locale.KOREA).getString("hello");
    }

    public String messageKoFromLocal() {
        Locale locale = Locale.KOREA;
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageKoFromTag() {
        Locale locale = Locale.forLanguageTag("ko-KR");
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageKoFromCtor() {
        Locale locale = new Locale("ko", "KR");
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageKoWithClassLoader() {
        return ResourceBundle.getBundle("messages", Locale.KOREA, ResourceConfig.class.getClassLoader()).getString("hello");
    }

    public String messageKoWithControl() {
        Control control = Control.getNoFallbackControl(Control.FORMAT_PROPERTIES);
        return ResourceBundle.getBundle("messages", Locale.KOREA, control).getString("hello");
    }

    public String messageKoWithControlAlias() {
        java.util.List<String> formats = Control.FORMAT_PROPERTIES;
        Control control = Control.getNoFallbackControl(formats);
        Control alias = control;
        return ResourceBundle.getBundle("messages", Locale.KOREA, alias).getString("hello");
    }

    public String messageKoWithDefaultControlAlias() {
        java.util.List<String> formats = Control.FORMAT_DEFAULT;
        Control control = Control.getControl(formats);
        Control alias = control;
        return ResourceBundle.getBundle("messages", Locale.KOREA, alias).getString("hello");
    }

    public String messageKoFromBuilder() {
        Locale locale = new Locale.Builder()
            .setLanguage("ko")
            .setRegion("KR")
            .build();
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageKoFromBuilderTag() {
        Locale.Builder builder = new Locale.Builder();
        builder.setLanguageTag("ko-KR");
        Locale locale = builder.build();
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageKoFromBuilderSetLocale() {
        Locale.Builder builder = new Locale.Builder();
        builder.setLocale(Locale.KOREA);
        Locale locale = builder.build();
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageKoFromBuilderReset() {
        Locale.Builder builder = new Locale.Builder();
        builder.setLanguage("en");
        builder.clear();
        builder.setLanguage("ko");
        builder.setRegion("KR");
        Locale locale = builder.build();
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageKoFromBuilderVariant() {
        Locale.Builder builder = new Locale.Builder();
        builder.setLanguage("ko");
        builder.setRegion("KR");
        builder.setVariant("POSIX");
        Locale locale = builder.build();
        return ResourceBundle.getBundle("messages", locale).getString("hello");
    }

    public String messageClassOnlyControlNoCandidate() {
        try {
            Control control = Control.getControl(Control.FORMAT_CLASS);
            return ResourceBundle.getBundle("messages", Locale.KOREA, control).getString("hello");
        } catch (Exception ignored) {
        }
        return null;
    }

    public String messageClassOnlyCustomControlNoCandidate() {
        try {
            return ResourceBundle.getBundle("messages", Locale.KOREA, new ClassOnlyControl()).getString("hello");
        } catch (Exception ignored) {
        }
        return null;
    }

    public String messageKoWithCustomCandidateControl() {
        return ResourceBundle.getBundle("messages", Locale.KOREA, new KoreanOnlyControl()).getString("hello");
    }

    public String messagePropertyBundle() {
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("messages.properties")) {
            if (input != null) {
                return new PropertyResourceBundle(input).getString("hello");
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public String messagePropertyBundleReader() {
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("messages.properties")) {
            if (input != null) {
                try (Reader reader = new InputStreamReader(input)) {
                    return new PropertyResourceBundle(reader).getString("hello");
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public String messagePropertyBundleKeys() {
        try (InputStream input = ResourceConfig.class.getClassLoader().getResourceAsStream("messages.properties")) {
            if (input != null) {
                return new PropertyResourceBundle(input).getKeys().nextElement();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    public String listBundleMessage() {
        return ResourceBundle.getBundle("sample.resources.MessagesListBundle", Locale.KOREA).getString("hello");
    }

    public String listBundleKeys() {
        return ResourceBundle.getBundle("sample.resources.MessagesListBundle").getKeys().nextElement();
    }

    public String providerBundleMessage() {
        return ResourceBundle.getBundle("sample.resources.ProviderMessagesBundle", Locale.KOREA).getString("hello");
    }

    public String providerBundleKeys() {
        return ResourceBundle.getBundle("sample.resources.ProviderMessagesBundle").getKeys().nextElement();
    }
}
