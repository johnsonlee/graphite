package sample.controlflow;

/**
 * The enum {@link SwitchExample#enumSwitch} switches on, in a compilation unit of its own on
 * purpose. javac lowers a switch over an enum of another compilation unit through a synthetic
 * {@code $SwitchMap$} class ({@code SwitchExample$1}) in every release; for an enum nested in
 * the switching class, javac 21 and later switch on {@code ordinal()} directly and emit no such
 * class. The graph parity baseline records what the bytecode holds, so a sample whose bytecode
 * depends on the compiler's version made the baseline hold only under the JDK that wrote it.
 */
enum SwitchStatus { ACTIVE, INACTIVE }
