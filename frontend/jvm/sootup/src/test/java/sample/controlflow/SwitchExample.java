package sample.controlflow;

public class SwitchExample {

    public String intSwitch(int value) {
        switch (value) {
            case 1: return "one";
            case 2: return "two";
            case 3: return "three";
            default: return "other";
        }
    }

    public int stringSwitch(String cmd) {
        switch (cmd) {
            case "start": return 1;
            case "stop": return 2;
            case "pause": return 3;
            default: return 0;
        }
    }

    public String enumSwitch(Status status) {
        switch (status) {
            case ACTIVE: return "running";
            case INACTIVE: return "stopped";
            default: return "unknown";
        }
    }

    public int switchWithFallThrough(int x) {
        int result = 0;
        switch (x) {
            case 1:
            case 2:
                result = 10;
                break;
            case 3:
                result = 20;
                break;
        }
        return result;
    }

    enum Status { ACTIVE, INACTIVE }
}
