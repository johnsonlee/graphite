package fixture.frontend.android;

/** D8 reuses one register for these distinct function objects. */
public final class RegisterReuse {
    private static int observed;

    private static void sink(int value) { observed = value; }

    public static void pair() {
        Runnable action = () -> sink(1);
        action.run();
        action = () -> sink(2);
        action.run();
    }

    // More than the adapter's 64-target limit: each call must still resolve to one function.
    public static void saturated() {
        Runnable action;
        action = () -> sink(0); action.run();
        action = () -> sink(1); action.run();
        action = () -> sink(2); action.run();
        action = () -> sink(3); action.run();
        action = () -> sink(4); action.run();
        action = () -> sink(5); action.run();
        action = () -> sink(6); action.run();
        action = () -> sink(7); action.run();
        action = () -> sink(8); action.run();
        action = () -> sink(9); action.run();
        action = () -> sink(10); action.run();
        action = () -> sink(11); action.run();
        action = () -> sink(12); action.run();
        action = () -> sink(13); action.run();
        action = () -> sink(14); action.run();
        action = () -> sink(15); action.run();
        action = () -> sink(16); action.run();
        action = () -> sink(17); action.run();
        action = () -> sink(18); action.run();
        action = () -> sink(19); action.run();
        action = () -> sink(20); action.run();
        action = () -> sink(21); action.run();
        action = () -> sink(22); action.run();
        action = () -> sink(23); action.run();
        action = () -> sink(24); action.run();
        action = () -> sink(25); action.run();
        action = () -> sink(26); action.run();
        action = () -> sink(27); action.run();
        action = () -> sink(28); action.run();
        action = () -> sink(29); action.run();
        action = () -> sink(30); action.run();
        action = () -> sink(31); action.run();
        action = () -> sink(32); action.run();
        action = () -> sink(33); action.run();
        action = () -> sink(34); action.run();
        action = () -> sink(35); action.run();
        action = () -> sink(36); action.run();
        action = () -> sink(37); action.run();
        action = () -> sink(38); action.run();
        action = () -> sink(39); action.run();
        action = () -> sink(40); action.run();
        action = () -> sink(41); action.run();
        action = () -> sink(42); action.run();
        action = () -> sink(43); action.run();
        action = () -> sink(44); action.run();
        action = () -> sink(45); action.run();
        action = () -> sink(46); action.run();
        action = () -> sink(47); action.run();
        action = () -> sink(48); action.run();
        action = () -> sink(49); action.run();
        action = () -> sink(50); action.run();
        action = () -> sink(51); action.run();
        action = () -> sink(52); action.run();
        action = () -> sink(53); action.run();
        action = () -> sink(54); action.run();
        action = () -> sink(55); action.run();
        action = () -> sink(56); action.run();
        action = () -> sink(57); action.run();
        action = () -> sink(58); action.run();
        action = () -> sink(59); action.run();
        action = () -> sink(60); action.run();
        action = () -> sink(61); action.run();
        action = () -> sink(62); action.run();
        action = () -> sink(63); action.run();
        action = () -> sink(64); action.run();
        action = () -> sink(65); action.run();
    }
}
