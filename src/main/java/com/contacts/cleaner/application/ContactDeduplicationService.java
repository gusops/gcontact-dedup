package com.contacts.cleaner.application;

import com.contacts.cleaner.ContactRow;
import com.contacts.cleaner.GoogleReimportWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class ContactDeduplicationService {
    public void exportGoogleReimport(Path outputFile, List<String> unifiedHeader, List<ContactRow> rows) throws IOException {
        GoogleReimportWriter.write(outputFile, unifiedHeader, rows);
    }
}
