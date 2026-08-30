package com.contacts.cleaner;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Thin bootstrap for the refactored enterprise-style architecture.
 * The real workflow now lives in the package-based application layer.
 */
public class Main {
    public static void main(String[] args) throws IOException {
        Path rootDir = args.length > 0 ? Paths.get(args[0]) : Paths.get(".");
        Path inputDir = rootDir.resolve("input");
        Path outputDir = args.length > 1 ? Paths.get(args[1]) : rootDir.resolve("output");

        com.contacts.cleaner.app.ContactImportApp.run(inputDir, outputDir);
    }
}
