package sample.resolution;

// Interface with a method
interface IService {
    String process(int value);
}

// Abstract class implementing the interface
abstract class AbstractService implements IService {
    @Override
    public String process(int value) {
        return transform(value);
    }

    protected String transform(int value) {
        return String.valueOf(value);
    }
}

// Concrete class that does NOT override process() - inherits from AbstractService
class ConcreteService extends AbstractService {
    // No override of process() - should resolve to AbstractService.process()

    // Overrides transform()
    @Override
    protected String transform(int value) {
        return "concrete:" + value;
    }
}

// Controller that uses the concrete type (not the interface)
public class MethodResolutionExample {
    private ConcreteService service = new ConcreteService();

    public String handle(int input) {
        // Bytecode: invokevirtual ConcreteService.process(I)
        // But process() is defined in AbstractService
        return service.process(input);
    }

    // Static method calls
    public static int computeStatic(int a, int b) {
        return Math.max(a, b);
    }

    public int useStatic(int x) {
        return computeStatic(x, 10);
    }

    // Constructor call tracking
    public ConcreteService createService() {
        return new ConcreteService();
    }

    // Super method call
    public String callSuper() {
        return service.toString(); // toString() defined in Object
    }

    // Interface default method (Java 8+)
    public String useDefault() {
        Greeter g = new FormalGreeter();
        return g.greet("World");
    }
}

interface Greeter {
    default String greet(String name) {
        return "Hello, " + name;
    }
}

class FormalGreeter implements Greeter {
    // Does NOT override greet() - uses default
}
