package com.contacts.cleaner.infrastructure.csv;

import com.contacts.cleaner.CsvUtil;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CsvContactRepository {
    public List<Path> findCsvFiles(Path inputDir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir, "*.csv")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        Collections.sort(files);
        return files;
    }

    public List<String[]> read(Path file) throws IOException {
        return CsvUtil.read(file);
    }
}
