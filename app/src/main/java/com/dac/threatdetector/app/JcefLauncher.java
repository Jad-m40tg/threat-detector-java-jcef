package com.dac.threatdetector.app;

/** jpackage entry point for this edition: always start the Chromium backend. */
public final class JcefLauncher {
    private JcefLauncher() {
    }

    public static void main(String[] args) {
        String[] merged = new String[args.length + 1];
        merged[0] = "--jcef";
        System.arraycopy(args, 0, merged, 1, args.length);
        Launcher.main(merged);
    }
}
