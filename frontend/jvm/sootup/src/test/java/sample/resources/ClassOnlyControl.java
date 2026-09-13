package sample.resources;

import java.util.List;
import java.util.ResourceBundle;

public class ClassOnlyControl extends ResourceBundle.Control {
    @Override
    public List<String> getFormats(String baseName) {
        return FORMAT_CLASS;
    }
}
