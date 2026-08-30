package com.contacts.cleaner.application;

import com.contacts.cleaner.ContactRow;
import com.contacts.cleaner.PhoneNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ContactNormalizationService {
    private static final Pattern PHONE_VALUE_PATTERN = Pattern.compile("^Phone \\d+ - Value$");

    public void enrichPhones(List<ContactRow> rows, List<String> unifiedHeader) {
        List<Integer> phoneColumnIndexes = new ArrayList<>();
        List<Integer> phoneLabelColumnIndexes = new ArrayList<>();

        for (int i = 0; i < unifiedHeader.size(); i++) {
            if (PHONE_VALUE_PATTERN.matcher(unifiedHeader.get(i)).matches()) {
                phoneColumnIndexes.add(i);
                phoneLabelColumnIndexes.add(unifiedHeader.indexOf(unifiedHeader.get(i).replace(" - Value", " - Label")));
            }
        }

        for (ContactRow row : rows) {
            for (int i = 0; i < phoneColumnIndexes.size(); i++) {
                int idx = phoneColumnIndexes.get(i);
                String value = row.values[idx];
                if (value == null || value.trim().isEmpty()) {
                    continue;
                }

                int labelIdx = phoneLabelColumnIndexes.get(i);
                String label = (labelIdx >= 0 && labelIdx < row.values.length) ? row.values[labelIdx] : null;
                PhoneNormalizer.FieldParseResult parsed = PhoneNormalizer.parseField(value);
                for (PhoneNormalizer.NormalizedPhone phone : parsed.phones) {
                    phone.sourceLabel = label;
                    row.phones.add(phone);
                }
            }
        }
    }
}
