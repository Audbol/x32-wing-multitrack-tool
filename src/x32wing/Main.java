package x32wing;

public final class Main {
    /** Release version; build.ps1 reads it from here. */
    public static final String VERSION = "1.2.0";

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            Gui.launch();
            return;
        }
        try {
            System.exit(Cli.run(args));
        } catch (Exception e) {
            System.err.println("\nError: " + e.getMessage());
            System.exit(1);
        }
    }
}
