package com.contacts.cleaner;

import java.util.ArrayList;
import java.util.List;

/** One source data row, mapped into the unified header column space. */
public class ContactRow {
    public final String sourceFile;
    public final int sourceRowNumber; // 1-based line number in the original file (header = line 1)
    public final String[] values; // aligned to the unified header list

    public final List<PhoneNormalizer.NormalizedPhone> phones = new ArrayList<>();

    public ContactRow(String sourceFile, int sourceRowNumber, String[] values) {
        this.sourceFile = sourceFile;
        this.sourceRowNumber = sourceRowNumber;
        this.values = values;
    }
}
