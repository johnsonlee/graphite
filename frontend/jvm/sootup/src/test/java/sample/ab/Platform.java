package sample.ab;

/**
 * Platform detection utility.
 */
public class Platform {

    public boolean isAndroid() {
        return System.getProperty("os.name").contains("Android");
    }

    public boolean isIOS() {
        return System.getProperty("os.name").contains("iOS");
    }
}
