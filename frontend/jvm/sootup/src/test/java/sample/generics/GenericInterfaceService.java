package sample.generics;

import java.util.List;
import java.io.Serializable;
import java.util.Comparator;

/**
 * Test class with generic interface implementations.
 * Used to test parseClassSignature with interface type arguments.
 */
public class GenericInterfaceService implements Comparable<GenericInterfaceService>, Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    public GenericInterfaceService(String name) {
        this.name = name;
    }

    @Override
    public int compareTo(GenericInterfaceService other) {
        return this.name.compareTo(other.name);
    }

    public String getName() {
        return name;
    }

    /**
     * A generic container class implementing multiple generic interfaces.
     */
    public static class Container<T extends Comparable<T>> implements Iterable<T>, Serializable {

        private static final long serialVersionUID = 2L;
        private List<T> elements;

        public Container(List<T> elements) {
            this.elements = elements;
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return elements.iterator();
        }

        public List<T> getElements() {
            return elements;
        }
    }
}
