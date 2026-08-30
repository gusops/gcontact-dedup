package com.contacts.cleaner.application;

import com.contacts.cleaner.ContactRow;
import com.contacts.cleaner.CsvUtil;
import com.contacts.cleaner.infrastructure.csv.CsvContactRepository;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ImportContactsUseCase {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final CsvContactRepository csvContactRepository = new CsvContactRepository();
    private final ContactNormalizationService contactNormalizationService = new ContactNormalizationService();
    private final ContactDeduplicationService contactDeduplicationService = new ContactDeduplicationService();

    public void run(Path inputDir, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        deleteStaleOutputCsvs(outputDir);

        if (!Files.isDirectory(inputDir)) {
            System.out.println("No input directory found at " + inputDir.toAbsolutePath()
                    + " - create it and place your source CSV files there.");
            return;
        }

        List<Path> csvFiles = csvContactRepository.findCsvFiles(inputDir);
        if (csvFiles.isEmpty()) {
            System.out.println("No input CSV files found in " + inputDir.toAbsolutePath());
            return;
        }

        Map<Path, List<String[]>> rawFileRows = new LinkedHashMap<>();
        List<String> unifiedHeader = new ArrayList<>();
        Set<String> headerSet = new LinkedHashSet<>();

        for (Path csvFile : csvFiles) {
            List<String[]> rows = CsvUtil.read(csvFile);
            rawFileRows.put(csvFile, rows);
            if (rows.isEmpty()) {
                continue;
            }
            for (String header : rows.get(0)) {
                if (headerSet.add(header)) {
                    unifiedHeader.add(header);
                }
            }
        }

        List<ContactRow> allRows = new ArrayList<>();
        for (Path csvFile : csvFiles) {
            List<String[]> rows = rawFileRows.get(csvFile);
            if (rows.isEmpty()) {
                continue;
            }

            String[] header = rows.get(0);
            int[] localToUnified = new int[header.length];
            for (int i = 0; i < header.length; i++) {
                localToUnified[i] = unifiedHeader.indexOf(header[i]);
            }

            String fileName = csvFile.getFileName().toString();
            for (int r = 1; r < rows.size(); r++) {
                String[] rowData = rows.get(r);
                String[] unifiedValues = new String[unifiedHeader.size()];
                Arrays.fill(unifiedValues, "");
                for (int i = 0; i < rowData.length && i < localToUnified.length; i++) {
                    int unifiedIndex = localToUnified[i];
                    if (unifiedIndex >= 0) {
                        unifiedValues[unifiedIndex] = rowData[i];
                    }
                }
                allRows.add(new ContactRow(fileName, r + 1, unifiedValues));
            }
        }

        contactNormalizationService.enrichPhones(allRows, unifiedHeader);

        String ts = LocalDateTime.now().format(FILE_TIMESTAMP);
        contactDeduplicationService.exportGoogleReimport(outputDir.resolve(ts + "_google_reimport.csv"), unifiedHeader, allRows);

        System.out.println("Processed " + allRows.size() + " contact rows from " + csvFiles.size() + " file(s).");
        System.out.println("Output written to " + outputDir.toAbsolutePath());
    }

    private void deleteStaleOutputCsvs(Path outputDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir, "*.csv")) {
            for (Path p : stream) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Ignore locked stale files on Windows so the fresh run can continue.
                }
            }
        }
    }
}
