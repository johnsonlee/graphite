package sample.advanced;

public class InnerClassExample {

    private int outerField = 10;

    // Static inner class
    public static class StaticHelper {
        public int compute(int x) {
            return x * 2;
        }
    }

    // Instance inner class
    public class InstanceHelper {
        public int addOuter(int x) {
            return x + outerField;
        }
    }

    public int useStaticInner() {
        StaticHelper helper = new StaticHelper();
        return helper.compute(5);
    }

    public int useInstanceInner() {
        InstanceHelper helper = new InstanceHelper();
        return helper.addOuter(5);
    }

    // Anonymous class
    public Runnable createAnonymous() {
        return new Runnable() {
            @Override
            public void run() {
                System.out.println("anonymous: " + outerField);
            }
        };
    }
}
