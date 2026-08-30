package com.contacts.cleaner;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses raw "Phone N - Value" cell contents (which may contain multiple
 * "::: "-separated variants of the same number) into normalized phone
 * results, following this precedence:
 *
 * 1. "+52..." (Mexico) -> strip legacy mobile "1" if present -> last 10 digits, key type TEN_DIGIT.
 * 2. "+1..." 11 digits (US/CA) -> last 10 digits, key type TEN_DIGIT.
 * 3. Other "+country..." -> full E.164 digit string, key type E164_FULL.
 * 4. "00..." international prefix -> re-run the plus-number logic on the remainder.
 * 5. "01..." Mexico toll-free/long-distance prefix -> strip "01" -> last 10 digits, TEN_DIGIT.
 * 6. Bare 10 digits -> TEN_DIGIT (country unknown/ambiguous).
 * 7. Bare 11 digits starting with "1" -> TEN_DIGIT (US).
 * 8. Bare 12 digits starting with "52" -> TEN_DIGIT (MX).
 * 9. Bare 13 digits starting with "521" -> TEN_DIGIT (MX legacy mobile).
 * 10. 5-9 digit or <=4 digit tokens (short/service/star codes) -> RAW_SHORT, keyed by the
 *     cleaned original text (not just digits) so distinct short codes are not conflated.
 * 11. Anything else (no digits, or an unrecognized digit length) -> anomaly.
 */
public class PhoneNormalizer {

    public enum KeyType { TEN_DIGIT, E164_FULL, RAW_SHORT }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public static class NormalizedPhone {
        public String raw;
        public String cleanedDisplay;
        public String canonicalKey;
        public KeyType keyType; // null => this is an anomaly, not a usable phone
        public String country;
        public String category;
        public Confidence confidence;
        public String warning;
        public String sourceLabel; // e.g. "Mobile"/"Home"; set by the caller, not by parseField itself
    }

    public static class TokenAnomaly {
        public final String rawToken;
        public final String reason;

        public TokenAnomaly(String rawToken, String reason) {
            this.rawToken = rawToken;
            this.reason = reason;
        }
    }

    public static class FieldParseResult {
        public final List<NormalizedPhone> phones = new ArrayList<>();
        public final List<TokenAnomaly> anomalies = new ArrayList<>();
    }

    private static final Pattern SPLIT_PATTERN = Pattern.compile("\\s*:::\\s*");

    public static FieldParseResult parseField(String rawField) {
        FieldParseResult result = new FieldParseResult();
        if (rawField == null) {
            return result;
        }
        String trimmedField = rawField.trim();
        if (trimmedField.isEmpty()) {
            return result;
        }
        String[] tokens = SPLIT_PATTERN.split(trimmedField);
        for (String tok : tokens) {
            String t = tok.trim();
            if (t.isEmpty()) {
                continue;
            }
            NormalizedPhone np = normalizeToken(t);
            if (np.keyType == null) {
                result.anomalies.add(new TokenAnomaly(t, np.warning));
            } else {
                result.phones.add(np);
            }
        }
        return result;
    }

    private static NormalizedPhone normalizeToken(String raw) {
        String cleanedDisplay = raw
                .replace('\u00A0', ' ')
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .trim();
        boolean hasPlus = cleanedDisplay.startsWith("+");
        String digitsOnly = cleanedDisplay.replaceAll("[^0-9]", "");

        if (digitsOnly.isEmpty()) {
            return anomaly(raw, cleanedDisplay, "No digits found in token");
        }

        if (hasPlus) {
            return normalizePlus(raw, cleanedDisplay, digitsOnly);
        }

        // International dialing prefix "00" -> treat remainder like a plus-number.
        if (digitsOnly.length() >= 4 && digitsOnly.startsWith("00")) {
            String rest = digitsOnly.substring(2);
            if (rest.length() >= 8) {
                NormalizedPhone np = normalizePlus(raw, cleanedDisplay, rest);
                np.category = "INTL_00_PREFIX(" + np.category + ")";
                return np;
            }
        }

        // Mexico toll-free / long-distance "01" prefix.
        if (digitsOnly.length() >= 10 && digitsOnly.startsWith("01")) {
            String rest = digitsOnly.substring(2);
            if (rest.length() >= 10) {
                return build(raw, cleanedDisplay, last10(rest), KeyType.TEN_DIGIT, "MX", "MX_TOLLFREE_01", Confidence.MEDIUM, null);
            }
        }

        if (digitsOnly.length() == 10) {
            return build(raw, cleanedDisplay, digitsOnly, KeyType.TEN_DIGIT, "UNKNOWN", "BARE_10", Confidence.MEDIUM, null);
        }
        if (digitsOnly.length() == 11 && digitsOnly.startsWith("1")) {
            return build(raw, cleanedDisplay, digitsOnly.substring(1), KeyType.TEN_DIGIT, "US", "BARE_11_LEADING1", Confidence.MEDIUM, null);
        }
        if (digitsOnly.length() == 12 && digitsOnly.startsWith("52")) {
            return build(raw, cleanedDisplay, last10(digitsOnly.substring(2)), KeyType.TEN_DIGIT, "MX", "BARE_12_COUNTRY52", Confidence.MEDIUM, null);
        }
        if (digitsOnly.length() == 13 && digitsOnly.startsWith("521")) {
            return build(raw, cleanedDisplay, last10(digitsOnly.substring(3)), KeyType.TEN_DIGIT, "MX", "BARE_13_COUNTRY521", Confidence.MEDIUM, null);
        }
        if (digitsOnly.length() >= 5 && digitsOnly.length() <= 9) {
            return build(raw, cleanedDisplay, cleanedDisplay, KeyType.RAW_SHORT, "UNKNOWN", "SHORT_CODE_LEN" + digitsOnly.length(), Confidence.LOW, null);
        }
        if (digitsOnly.length() <= 4) {
            return build(raw, cleanedDisplay, cleanedDisplay, KeyType.RAW_SHORT, "UNKNOWN", "VERY_SHORT_CODE_LEN" + digitsOnly.length(), Confidence.LOW, null);
        }

        return anomaly(raw, cleanedDisplay, "Unrecognized digit length: " + digitsOnly.length());
    }

    private static NormalizedPhone normalizePlus(String raw, String cleanedDisplay, String digitsOnly) {
        if (digitsOnly.startsWith("52")) {
            String rest = digitsOnly.substring(2);
            if (rest.length() == 11 && rest.startsWith("1")) {
                rest = rest.substring(1); // drop legacy mobile "1"
            }
            if (rest.length() >= 10) {
                return build(raw, cleanedDisplay, last10(rest), KeyType.TEN_DIGIT, "MX", "E164_MX", Confidence.HIGH, null);
            }
            return anomaly(raw, cleanedDisplay, "MX (+52) number too short: " + digitsOnly);
        }
        if (digitsOnly.startsWith("1") && digitsOnly.length() == 11) {
            return build(raw, cleanedDisplay, digitsOnly.substring(1), KeyType.TEN_DIGIT, "US", "E164_US", Confidence.HIGH, null);
        }
        if (digitsOnly.length() >= 8) {
            return build(raw, cleanedDisplay, "+" + digitsOnly, KeyType.E164_FULL, "OTHER", "E164_OTHER_COUNTRY", Confidence.HIGH, null);
        }
        return anomaly(raw, cleanedDisplay, "Plus-prefixed number too short: " + digitsOnly);
    }

    private static String last10(String s) {
        return s.length() > 10 ? s.substring(s.length() - 10) : s;
    }

    private static NormalizedPhone build(String raw, String cleanedDisplay, String canonicalKey, KeyType keyType,
                                          String country, String category, Confidence confidence, String warning) {
        NormalizedPhone np = new NormalizedPhone();
        np.raw = raw;
        np.cleanedDisplay = cleanedDisplay;
        np.canonicalKey = canonicalKey;
        np.keyType = keyType;
        np.country = country;
        np.category = category;
        np.confidence = confidence;
        np.warning = warning;
        return np;
    }

    private static NormalizedPhone anomaly(String raw, String cleanedDisplay, String reason) {
        NormalizedPhone np = new NormalizedPhone();
        np.raw = raw;
        np.cleanedDisplay = cleanedDisplay;
        np.keyType = null;
        np.category = "ANOMALY";
        np.confidence = Confidence.LOW;
        np.warning = reason;
        return np;
    }
}
