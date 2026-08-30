package com.contacts.cleaner;

import com.contacts.cleaner.application.ContactClusterService;
import com.contacts.cleaner.application.ContactMergeService;
import com.contacts.cleaner.application.MergedContact;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a Google-Contacts-import-ready CSV: one row per real person,
 * duplicates merged, phone numbers rewritten to E.164-style values Google
 * itself uses ("+52..."/"+1..." with no spaces/punctuation), and no
 * diagnostic-only columns (this is the only output file this pipeline
 * writes; see {@link com.contacts.cleaner.application.ImportContactsUseCase}).
 *
 * Merge policy:
 * - Two source rows are clustered into the same contact when they share a
 *   phone number, including short values like "411" that are legitimate
 *   contact identifiers in the source data instead of service codes.
 * - Per cluster, a "base" row is chosen (most non-blank identity fields:
 *   name, org, email, address, photo); other scalar columns are backfilled
 *   from other cluster members only if the base is blank there.
 * - Every distinct phone/email/website found anywhere in the cluster is kept
 *   (deduplicated), reflowed into as many "Phone N/E-mail N/Website N" slots
 *   as the largest cluster needs.
 * - If cluster members disagree on First/Last name, the base wins for the
 *   real Name fields, and the alternates are preserved in Notes (not
 *   silently discarded) along with the merged source coordinates.
 * - Bare 10-digit numbers with no country code are left as-is (no assumed
 *   country), per explicit decision; MX/US numbers get "+52"/"+1" prefixes.
 * - Merging is transitive (if A shares a number with B, and B shares a
 *   DIFFERENT number with C, all three become one cluster). Left unchecked
 *   this can chain unrelated people together through shared/business
 *   numbers. To prevent that, a merge that would push a cluster past
 *   {@code MAX_CLUSTER_SIZE} (see {@link com.contacts.cleaner.application.ContactClusterService})
 *   is refused so widely-shared numbers can't silently fuse unrelated contacts.
 * - Any row whose First/Middle/Last/Prefix/Suffix/Nickname name field
 *   contains "SPAM" (case-insensitive) is treated as a catch-all/blocklist
 *   record, not a real person: it is skipped entirely from this output (and
 *   excluded from the normal duplicate clustering above, so its numbers
 *   can never bridge unrelated contacts).
 * - For every output contact, the Prefix/First/Middle/Last/Suffix/Nickname
 *   name parts are concatenated into a single "First Name" value (joined
 *   with " - "), and the other name columns are left blank, per explicit
 *   decision to keep exactly one populated name field. Words already seen
 *   in an earlier part are dropped so repeated pieces (e.g. a Nickname
 *   field that already restates the full name) don't duplicate.
 * - Accented characters (e.g. Spanish diacritics) are replaced with their
 *   plain-ASCII base letter everywhere in the output, since some
 *   downstream tools render the original accented characters incorrectly.
 * - Labels/tags are intentionally removed from this reimport output so
 *   Google contacts are imported without the source "* myContacts" metadata.
 */
public class GoogleReimportWriter {

    private static final Pattern PHONE_ANY = Pattern.compile("^Phone \\d+ - (Label|Value)$");
    private static final Pattern EMAIL_ANY = Pattern.compile("^E-mail \\d+ - (Label|Value)$");
    private static final Pattern WEBSITE_ANY = Pattern.compile("^Website \\d+ - (Label|Value)$");
    private static final Pattern PHONE_SLOT = Pattern.compile("^Phone (\\d+) - (Label|Value)$");
    private static final Pattern EMAIL_SLOT = Pattern.compile("^E-mail (\\d+) - (Label|Value)$");
    private static final Pattern WEBSITE_SLOT = Pattern.compile("^Website (\\d+) - (Label|Value)$");

    public static void write(Path outFile, List<String> unifiedHeader, List<ContactRow> allRows) throws IOException {
        int idxFirst = unifiedHeader.indexOf("First Name");
        int idxMiddle = unifiedHeader.indexOf("Middle Name");
        int idxLast = unifiedHeader.indexOf("Last Name");
        int idxPrefix = unifiedHeader.indexOf("Name Prefix");
        int idxSuffix = unifiedHeader.indexOf("Name Suffix");
        int idxNickname = unifiedHeader.indexOf("Nickname");

        boolean[] isSpam = ContactClusterService.computeSpamFlags(allRows, idxFirst, idxMiddle, idxLast, idxPrefix, idxSuffix, idxNickname);
        ContactClusterService.ClusterResult clusterResult = ContactClusterService.buildClusters(allRows, isSpam);
        Map<Integer, List<Integer>> clusters = clusterResult.clusters;

        int idxOrgName = unifiedHeader.indexOf("Organization Name");
        int idxEmail1Value = unifiedHeader.indexOf("E-mail 1 - Value");
        int idxAddrFormatted = unifiedHeader.indexOf("Address 1 - Formatted");
        int idxPhoto = unifiedHeader.indexOf("Photo");
        int idxNotes = unifiedHeader.indexOf("Notes");

        List<Integer> scalarColumnIndexes = ContactMergeService.computeScalarColumnIndexes(unifiedHeader);
        ContactMergeService.Indexes mergeIndexes = new ContactMergeService.Indexes(
                idxFirst, idxLast, idxOrgName, idxEmail1Value, idxAddrFormatted, idxPhoto, idxNotes);

        List<MergedContact> merged = new ArrayList<>();
        int maxPhones = 0;
        int maxEmails = 0;
        int maxWebsites = 0;

        for (List<Integer> members : clusters.values()) {
            MergedContact mc = ContactMergeService.mergeCluster(members, allRows, unifiedHeader, scalarColumnIndexes, mergeIndexes);
            maxPhones = Math.max(maxPhones, mc.phones.size());
            maxEmails = Math.max(maxEmails, mc.emails.size());
            maxWebsites = Math.max(maxWebsites, mc.websites.size());
            merged.add(mc);
        }

        for (MergedContact mc : merged) {
            ContactMergeService.mergeNameFields(mc, idxPrefix, idxFirst, idxMiddle, idxLast, idxSuffix, idxNickname, mc.nameVariants);
        }
        ContactMergeService.addDuplicateFirstNameSuffixes(merged, idxFirst);

        List<String> finalHeader = buildFinalHeader(unifiedHeader, Math.max(maxPhones, 1), Math.max(maxEmails, 1), Math.max(maxWebsites, 1));

        try (BufferedWriter w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            CsvUtil.writeRow(w, finalHeader);
            for (MergedContact mc : merged) {
                List<String> line = new ArrayList<>(finalHeader.size());
                for (String h : finalHeader) {
                    line.add(resolveColumn(h, mc, unifiedHeader));
                }
                CsvUtil.writeRow(w, line);
            }
        }

        int mergedClusters = 0;
        for (List<Integer> members : clusters.values()) {
            if (members.size() > 1) {
                mergedClusters++;
            }
        }
        System.out.println("google_reimport.csv: " + allRows.size() + " source rows -> " + merged.size()
                + " contacts (" + mergedClusters + " were merged duplicate clusters).");
    }

    private static List<String> buildFinalHeader(List<String> unifiedHeader, int phoneSlots, int emailSlots, int websiteSlots) {
        List<String> finalHeader = new ArrayList<>();
        boolean phoneEmitted = false;
        boolean emailEmitted = false;
        boolean websiteEmitted = false;
        for (String h : unifiedHeader) {
            if (PHONE_ANY.matcher(h).matches()) {
                if (!phoneEmitted) {
                    for (int k = 1; k <= phoneSlots; k++) {
                        finalHeader.add("Phone " + k + " - Label");
                        finalHeader.add("Phone " + k + " - Value");
                    }
                    phoneEmitted = true;
                }
                continue;
            }
            if (EMAIL_ANY.matcher(h).matches()) {
                if (!emailEmitted) {
                    for (int k = 1; k <= emailSlots; k++) {
                        finalHeader.add("E-mail " + k + " - Label");
                        finalHeader.add("E-mail " + k + " - Value");
                    }
                    emailEmitted = true;
                }
                continue;
            }
            if (WEBSITE_ANY.matcher(h).matches()) {
                if (!websiteEmitted) {
                    for (int k = 1; k <= websiteSlots; k++) {
                        finalHeader.add("Website " + k + " - Label");
                        finalHeader.add("Website " + k + " - Value");
                    }
                    websiteEmitted = true;
                }
                continue;
            }
            finalHeader.add(h);
        }
        return finalHeader;
    }

    private static String resolveColumn(String h, MergedContact mc, List<String> unifiedHeader) {
        Matcher m = PHONE_SLOT.matcher(h);
        if (m.matches()) {
            return slotValue(mc.phones, m);
        }
        m = EMAIL_SLOT.matcher(h);
        if (m.matches()) {
            return slotValue(mc.emails, m);
        }
        m = WEBSITE_SLOT.matcher(h);
        if (m.matches()) {
            return slotValue(mc.websites, m);
        }
        if (h.equals("Notes")) {
            return mc.notes;
        }
        if (h.equals("Labels")) {
            return "";
        }
        int idx = unifiedHeader.indexOf(h);
        return (idx >= 0 && mc.scalarValues[idx] != null) ? mc.scalarValues[idx] : "";
    }

    private static String slotValue(List<String[]> entries, Matcher m) {
        int k = Integer.parseInt(m.group(1)) - 1;
        if (k >= entries.size()) {
            return "";
        }
        return m.group(2).equals("Label") ? entries.get(k)[0] : entries.get(k)[1];
    }
}
