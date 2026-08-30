package com.contacts.cleaner.app;

import com.contacts.cleaner.application.ImportContactsUseCase;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ContactImportApp {
    private ContactImportApp() {
    }

    public static void main(String[] args) throws IOException {
        Path rootDir = args.length > 0 ? Paths.get(args[0]) : Paths.get(".");
        Path inputDir = rootDir.resolve("input");
        Path outputDir = args.length > 1 ? Paths.get(args[1]) : rootDir.resolve("output");
        run(inputDir, outputDir);
    }

    public static void run(Path inputDir, Path outputDir) throws IOException {
        new ImportContactsUseCase().run(inputDir, outputDir);
    }
}
