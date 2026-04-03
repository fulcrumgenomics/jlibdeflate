package com.fulcrumgenomics.jlibdeflate;

import java.util.Arrays;

/** Entry point for the jlibdeflate JAR. Dispatches to subcommands. */
public class Main {

    /**
     * Parses the first argument as a command name and delegates to the
     * appropriate subcommand, passing all remaining arguments through.
     *
     * @param args command-line arguments; the first element is the command name
     * @throws Exception if the subcommand throws
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String[] remainingArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "benchmark":
                LibdeflateBenchmark.main(remainingArgs);
                break;
            default:
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
        }
    }

    /** Prints top-level usage information listing all available subcommands to stderr. */
    private static void printUsage() {
        System.err.println("Usage: java -jar jlibdeflate.jar <command> [options]");
        System.err.println();
        System.err.println("Commands:");
        System.err.println("  benchmark    Benchmark libdeflate vs. JDK zlib compression/decompression");
    }
}
