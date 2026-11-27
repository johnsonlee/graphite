// Builds the input named by -Din four times in this JVM with each revision's JavaProjectLoader
// defaults, saving build i to <-Dout>/g0i. Kotlin default arguments are reached through their
// synthetic constructors, so one script serves both revisions.
import io.johnsonlee.graphite.sootup.JavaProjectLoader;
import io.johnsonlee.graphite.input.LoaderConfig;
import io.johnsonlee.graphite.webgraph.GraphStore;
import java.nio.file.*;
import java.lang.reflect.*;
LoaderConfig defaults() throws Exception {
    for (Constructor<?> c : LoaderConfig.class.getDeclaredConstructors()) {
        Class<?>[] t = c.getParameterTypes();
        if (t.length > 0 && t[t.length - 1].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) {
            Object[] args = new Object[t.length];
            for (int i = 0; i < t.length; i++) {
                if (t[i] == boolean.class) args[i] = false;
                else if (t[i] == int.class) args[i] = -1;
                else if (t[i] == long.class) args[i] = 0L;
                else if (t[i] == double.class) args[i] = 0.0;
                else args[i] = null;
            }
            return (LoaderConfig) c.newInstance(args);
        }
    }
    throw new IllegalStateException("no default constructor");
}
JavaProjectLoader loader(LoaderConfig config) throws Exception {
    for (Constructor<?> c : JavaProjectLoader.class.getDeclaredConstructors()) {
        Class<?>[] t = c.getParameterTypes();
        if (t.length > 1 && t[0] == LoaderConfig.class && t[t.length - 1].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")) {
            Object[] args = new Object[t.length];
            args[0] = config;
            for (int i = 1; i < t.length; i++) args[i] = t[i] == int.class ? -2 : (t[i] == boolean.class ? false : null);
            return (JavaProjectLoader) c.newInstance(args);
        }
    }
    throw new IllegalStateException("no loader constructor");
}
var out = Path.of(System.getProperty("out"));
var input = Path.of(System.getProperty("in"));
for (int i = 0; i < 4; i++) {
    var g = loader(defaults()).load(input);
    GraphStore.INSTANCE.save(g, out.resolve(String.format("g%02d", i)), 2);
}
System.out.println("built 4");
/exit
