package com.contacts.cleaner.application;

import com.contacts.cleaner.ContactRow;
import com.contacts.cleaner.PhoneNormalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds one {@link MergedContact} per duplicate cluster: picks a "base" row
 * (most non-blank identity fields), backfills blank scalar columns from other
 * cluster members, collects deduplicated phones/emails/websites, and
 * preserves alternate name spellings for later name-field collapsing.
 */
public final class ContactMergeService {
    private static final int PHONE_SLOT_CAP = 20;

    private static final Pattern PHONE_ANY = Pattern.compile("^Phone \\d+ - (Label|Value)$");
    private static final Pattern EMAIL_ANY = Pattern.compile("^E-mail \\d+ - (Label|Value)$");
    private static final Pattern WEBSITE_ANY = Pattern.compile("^Website \\d+ - (Label|Value)$");

    private ContactMergeService() {
    }

    /** Column indexes that hold a single scalar value per contact (i.e. not phone/email/website/notes/labels groups). */
    public static List<Integer> computeScalarColumnIndexes(List<String> unifiedHeader) {
        List<Integer> scalarColumnIndexes = new ArrayList<>();
        for (int i = 0; i < unifiedHeader.size(); i++) {
            String h = unifiedHeader.get(i);
            if (PHONE_ANY.matcher(h).matches() || EMAIL_ANY.matcher(h).matches() || WEBSITE_ANY.matcher(h).matches()
                    || h.equals("Notes") || h.equals("Labels")) {
                continue;
            }
            scalarColumnIndexes.add(i);
        }
        return scalarColumnIndexes;
    }

    public static final class Indexes {
        public final int idxFirst;
        public final int idxLast;
        public final int idxOrgName;
        public final int idxEmail1Value;
        public final int idxAddrFormatted;
        public final int idxPhoto;
        public final int idxNotes;

        public Indexes(int idxFirst, int idxLast, int idxOrgName, int idxEmail1Value,
                       int idxAddrFormatted, int idxPhoto, int idxNotes) {
            this.idxFirst = idxFirst;
            this.idxLast = idxLast;
            this.idxOrgName = idxOrgName;
            this.idxEmail1Value = idxEmail1Value;
            this.idxAddrFormatted = idxAddrFormatted;
            this.idxPhoto = idxPhoto;
            this.idxNotes = idxNotes;
        }
    }

    public static MergedContact mergeCluster(List<Integer> members, List<ContactRow> allRows, List<String> unifiedHeader,
                                              List<Integer> scalarColumnIndexes, Indexes idx) {
        int baseIdx = members.get(0);
        int bestScore = -1;
        for (int i : members) {
            String[] v = allRows.get(i).values;
            int score = (nonBlank(v, idx.idxFirst) ? 1 : 0) + (nonBlank(v, idx.idxLast) ? 1 : 0)
                    + (nonBlank(v, idx.idxOrgName) ? 1 : 0) + (nonBlank(v, idx.idxEmail1Value) ? 1 : 0)
                    + (nonBlank(v, idx.idxAddrFormatted) ? 1 : 0) + (nonBlank(v, idx.idxPhoto) ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                baseIdx = i;
            }
        }
        String[] baseValues = allRows.get(baseIdx).values;

        MergedContact mc = new MergedContact();
        mc.scalarValues = new String[unifiedHeader.size()];
        for (int colIdx : scalarColumnIndexes) {
            String v = baseValues[colIdx];
            if (v == null || v.trim().isEmpty()) {
                for (int i : members) {
                    if (i == baseIdx) {
                        continue;
                    }
                    String alt = allRows.get(i).values[colIdx];
                    if (alt != null && !alt.trim().isEmpty()) {
                        v = alt;
                        break;
                    }
                }
            }
            mc.scalarValues[colIdx] = NameFormattingService.stripAccents(v == null ? "" : v);
        }

        LinkedHashSet<String> nameVariants = new LinkedHashSet<>();
        for (int i : members) {
            String[] v = allRows.get(i).values;
            String full = NameFormattingService.stripAccents((safe(v, idx.idxFirst) + " " + safe(v, idx.idxLast)).trim());
            if (!full.isEmpty()) {
                nameVariants.add(full);
            }
        }
        nameVariants.remove(NameFormattingService.stripAccents((safe(baseValues, idx.idxFirst) + " " + safe(baseValues, idx.idxLast)).trim()));
        mc.nameVariants = new ArrayList<>(nameVariants);

        LinkedHashSet<String> notesSet = new LinkedHashSet<>();
        for (int i : members) {
            String note = safe(allRows.get(i).values, idx.idxNotes);
            if (!note.isEmpty()) {
                notesSet.add(note);
            }
        }
        StringBuilder notesOut = new StringBuilder(String.join("\n\n", notesSet));
        mc.notes = "";

        LinkedHashMap<String, String[]> phoneMap = new LinkedHashMap<>();
        for (int i : members) {
            for (PhoneNormalizer.NormalizedPhone np : allRows.get(i).phones) {
                String key = np.keyType + "::" + np.canonicalKey;
                if (phoneMap.containsKey(key)) {
                    continue;
                }
                String label = (np.sourceLabel == null || np.sourceLabel.trim().isEmpty()) ? "Mobile" : np.sourceLabel.trim();
                phoneMap.put(key, new String[] { label, renderPhoneValue(np) });
            }
        }
        List<String[]> allPhones = new ArrayList<>(phoneMap.values());
        if (allPhones.size() > PHONE_SLOT_CAP) {
            List<String> overflow = new ArrayList<>();
            for (int i = PHONE_SLOT_CAP; i < allPhones.size(); i++) {
                String[] p = allPhones.get(i);
                overflow.add(p[1] + " (" + p[0] + ")");
            }
            appendSection(notesOut, "Additional numbers (" + overflow.size()
                    + ", not imported as phone fields due to volume): " + String.join(" ::: ", overflow));
            mc.notes = "";
            mc.phones = new ArrayList<>(allPhones.subList(0, PHONE_SLOT_CAP));
        } else {
            mc.phones = allPhones;
        }

        mc.emails = collectSlots(allRows, members, unifiedHeader, "E-mail", false);
        mc.websites = collectSlots(allRows, members, unifiedHeader, "Website", true);

        return mc;
    }

    /** Collapses Prefix/First/Middle/Last/Suffix/Nickname into First Name, joined with " - ", blanking the rest. */
    public static void mergeNameFields(MergedContact mc, int idxPrefix, int idxFirst, int idxMiddle, int idxLast,
                                        int idxSuffix, int idxNickname, List<String> extraNameVariants) {
        if (idxFirst < 0 || mc.scalarValues == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        NameFormattingService.addIfNonBlank(parts, mc.scalarValues, idxPrefix);
        NameFormattingService.addIfNonBlank(parts, mc.scalarValues, idxFirst);
        NameFormattingService.addIfNonBlank(parts, mc.scalarValues, idxMiddle);
        NameFormattingService.addIfNonBlank(parts, mc.scalarValues, idxLast);
        NameFormattingService.addIfNonBlank(parts, mc.scalarValues, idxSuffix);
        NameFormattingService.addIfNonBlank(parts, mc.scalarValues, idxNickname);
        if (extraNameVariants != null) {
            for (String variant : extraNameVariants) {
                String v = variant == null ? "" : NameFormattingService.stripAccents(variant.trim());
                if (!v.isEmpty()) {
                    parts.add(v);
                }
            }
        }
        mc.scalarValues[idxFirst] = String.join(" - ", NameFormattingService.dedupeNameTokens(parts));
        NameFormattingService.clearIfPresent(mc.scalarValues, idxPrefix);
        NameFormattingService.clearIfPresent(mc.scalarValues, idxMiddle);
        NameFormattingService.clearIfPresent(mc.scalarValues, idxLast);
        NameFormattingService.clearIfPresent(mc.scalarValues, idxSuffix);
        NameFormattingService.clearIfPresent(mc.scalarValues, idxNickname);
    }

    /** Ensures repeated display names remain unique in the final CSV by appending 2, 3, etc. to later duplicates. */
    public static void addDuplicateFirstNameSuffixes(List<MergedContact> merged, int idxFirst) {
        if (idxFirst < 0 || merged == null || merged.isEmpty()) {
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (MergedContact mc : merged) {
            if (mc == null || mc.scalarValues == null || idxFirst >= mc.scalarValues.length) {
                continue;
            }
            String base = NameFormattingService.stripAccents(mc.scalarValues[idxFirst] == null ? "" : mc.scalarValues[idxFirst]).trim();
            if (base.isEmpty()) {
                continue;
            }
            String displayKey = base.split(" - ", 2)[0].trim();
            if (displayKey.isEmpty()) {
                displayKey = base;
            }
            String key = displayKey.toLowerCase(Locale.ROOT);
            int count = counts.getOrDefault(key, 0) + 1;
            counts.put(key, count);
            if (count > 1) {
                mc.scalarValues[idxFirst] = base + " " + count;
            }
        }
    }

    /** Collects unique, non-blank "Group N - Label/Value" values across all cluster members. */
    private static List<String[]> collectSlots(List<ContactRow> allRows, List<Integer> members, List<String> unifiedHeader,
                                                String groupPrefix, boolean keyCaseSensitive) {
        LinkedHashMap<String, String[]> map = new LinkedHashMap<>();
        for (int idx : members) {
            String[] v = allRows.get(idx).values;
            for (int slot = 1; ; slot++) {
                int valIdx = unifiedHeader.indexOf(groupPrefix + " " + slot + " - Value");
                if (valIdx < 0) {
                    break;
                }
                int labIdx = unifiedHeader.indexOf(groupPrefix + " " + slot + " - Label");
                String val = safe(v, valIdx);
                if (val.isEmpty()) {
                    continue;
                }
                String dedupeKey = keyCaseSensitive ? val : val.toLowerCase();
                String label = labIdx >= 0 ? safe(v, labIdx) : "";
                map.putIfAbsent(dedupeKey, new String[] { label.isEmpty() ? "Other" : label, val });
            }
        }
        return new ArrayList<>(map.values());
    }

    /** Renders a normalized phone as Google's compact E.164-style value, per the MX/US/other rules. */
    private static String renderPhoneValue(PhoneNormalizer.NormalizedPhone np) {
        switch (np.keyType) {
            case TEN_DIGIT:
                if ("MX".equals(np.country)) {
                    return "+52" + np.canonicalKey;
                }
                if ("US".equals(np.country)) {
                    return "+1" + np.canonicalKey;
                }
                return np.canonicalKey; // ambiguous bare number: no assumed country, left as-is
            case E164_FULL:
            case RAW_SHORT:
            default:
                return np.canonicalKey;
        }
    }

    private static void appendSection(StringBuilder sb, String section) {
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append('[').append(section).append(']');
    }

    private static boolean nonBlank(String[] values, int idx) {
        return idx >= 0 && idx < values.length && values[idx] != null && !values[idx].trim().isEmpty();
    }

    private static String safe(String[] values, int idx) {
        return (idx >= 0 && idx < values.length && values[idx] != null) ? values[idx].trim() : "";
    }
}
