package sample.resources;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

public class KoreanOnlyControl extends ResourceBundle.Control {
    @Override
    public List<Locale> getCandidateLocales(String baseName, Locale locale) {
        return List.of(Locale.KOREA);
    }
}
