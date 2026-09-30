package sample.constdefs;

/**
 * Fixtures for branch-side constant definitions ({@code local = <constant>} inside a branch).
 */
public class ConstantDefinitionExample {

    private int field = 7;

    static boolean f() {
        return System.nanoTime() % 2 == 0;
    }

    static boolean g() {
        return System.nanoTime() % 3 == 0;
    }

    static boolean gate() {
        return System.nanoTime() % 5 == 0;
    }

    static int treatment() {
        return 1;
    }

    static int control() {
        return 2;
    }

    static void sink(int value) {
        System.out.println(value);
    }

    // boolean on = f() && g(); if (on) A else B
    public int shortCircuitAnd() {
        boolean on = f() && g();
        if (on) {
            return treatment();
        } else {
            return control();
        }
    }

    // boolean on = f() || g(); if (on) A else B
    public int shortCircuitOr() {
        boolean on = f() || g();
        if (on) {
            return treatment();
        } else {
            return control();
        }
    }

    // if (p) { if (q) x = 1 else x = 2 } else x = 3
    public int nested(boolean p, boolean q) {
        int x;
        if (p) {
            if (q) {
                x = 1;
            } else {
                x = 2;
            }
        } else {
            x = 3;
        }
        return x;
    }

    // Same side assigns the same constant twice.
    public int repeated(boolean p) {
        int x = 0;
        if (p) {
            x = 1;
            sink(x);
            x = 1;
        }
        return x;
    }

    // x = 5 outside any branch; the branch only assigns x = 6.
    public int straightLine(boolean p) {
        int x = 5;
        if (p) {
            x = 6;
        }
        return x;
    }

    // Non-constant right-hand sides never produce a definition.
    public int nonConstant(boolean p, int y, int a) {
        int x;
        if (p) {
            x = y;
        } else {
            x = a + 1;
        }
        if (x > 0) {
            x = this.field;
        }
        return x;
    }

    // javac keeps the conditional jump of an empty then block, and both of its targets are the
    // statement after it: the writes there execute either way and belong to neither side.
    public int emptyThen(boolean c) {
        if (c) {
        }
        int x = 1;
        sink(x);
        return x;
    }

    // The write after the merge point is the branch's false successor but is also reached through
    // the true body: it executes either way and belongs to neither side.
    public int postMerge(boolean c) {
        int x = 0;
        if (c) {
            sink(x);
        }
        x = 1;
        sink(x);
        return x;
    }

    // The write after a loop is the loop condition's exit successor but is also reached through the
    // body: it executes either way and belongs to neither side.
    public int loopExit(int n) {
        int x = 0;
        int i = 0;
        while (i < n) {
            i++;
        }
        x = 1;
        sink(x);
        return x;
    }

    // A definition inside a loop body belongs to no branch side.
    public int loop(int n) {
        int x = 0;
        int i = 0;
        while (i < n) {
            x = 1;
            i++;
        }
        return x;
    }

    // Parsing overloads share the valueOf name but are not boxing: only Integer.valueOf(2) defines a constant.
    public Integer parsedBoxing(boolean p) {
        Integer n;
        if (p) {
            n = Integer.valueOf("1");
        } else {
            n = Integer.valueOf(2);
        }
        Boolean b = p ? Boolean.valueOf("true") : Boolean.FALSE;
        sink(b ? 1 : 0);
        return n;
    }

    // x = 1 then a branch with x = 2 on one side and the non-constant x = y on the other.
    public int nonConstantSurvivor(boolean p, int y) {
        int x = 1;
        if (p) {
            x = 2;
        } else {
            x = y;
        }
        return x;
    }

    // A parameter local re-assigned a constant on one side: the parameter binding is a non-constant write.
    public int parameterWrite(boolean p, int n) {
        if (p) {
            n = 5;
        }
        return n;
    }

    // The motivating shape: on = gate() && (a || b); if (on) A else B
    public int sample(boolean a, boolean b) {
        boolean on = gate() && (a || b);
        if (on) {
            return treatment();
        } else {
            return control();
        }
    }
}
