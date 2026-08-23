package com.centrallite.dashboard;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Conservative controller for old Android/MediaTek charging drivers.
 * It only writes to a closed list of well-known sysfs nodes and requires root.
 */
final class PowerControl {
    private static final String[] CONTROL_NODES = new String[] {
            "/sys/class/power_supply/usb/input_suspend",
            "/sys/class/power_supply/ac/input_suspend",
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/charge_enabled",
            "/sys/class/power_supply/battery/charger_enable",
            "/sys/devices/platform/mt-battery/power_supply/battery/charging_enabled",
            "/sys/devices/platform/battery/power_supply/battery/charging_enabled"
    };

    private PowerControl() { }

    static boolean hasRoot() {
        CommandResult result = run(new String[] {"su", "-c", "id"});
        return result.success && result.output.contains("uid=0");
    }

    static String findControlNode() {
        for (String path : CONTROL_NODES) {
            if (new File(path).exists()) return path;
        }
        return null;
    }

    static boolean isFullInputControl(String path) {
        return path != null && path.endsWith("input_suspend");
    }

    static String readNode(String path) {
        if (path == null) return "indisponível";
        CommandResult result = run(new String[] {"su", "-c", "cat " + path});
        return result.success ? result.output.trim() : "sem permissão";
    }

    static boolean setInputEnabled(boolean enabled) {
        String path = findControlNode();
        if (path == null || !hasRoot()) return false;

        String value;
        if (isFullInputControl(path)) {
            value = enabled ? "0" : "1";
        } else {
            value = enabled ? "1" : "0";
        }

        return run(new String[] {"su", "-c", "echo " + value + " > " + path}).success;
    }

    static boolean turnScreenOff() {
        if (!hasRoot()) return false;
        return run(new String[] {"su", "-c", "input keyevent 26"}).success;
    }

    private static CommandResult run(String[] command) {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(command);
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
            int exit = process.waitFor();
            return new CommandResult(exit == 0, output.toString());
        } catch (Exception error) {
            return new CommandResult(false, error.getClass().getSimpleName());
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) { }
            if (process != null) process.destroy();
        }
    }

    private static final class CommandResult {
        final boolean success;
        final String output;

        CommandResult(boolean success, String output) {
            this.success = success;
            this.output = output == null ? "" : output;
        }
    }
}
