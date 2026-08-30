package com.contacts.cleaner.application;

import com.contacts.cleaner.ContactRow;
import com.contacts.cleaner.PhoneNormalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContactClusterService {
    private static final int MAX_CLUSTER_SIZE = 10;

    private ContactClusterService() {
    }

    /** Result of clustering: final contact groups (by row index) plus any merges refused for exceeding MAX_CLUSTER_SIZE. */
    public static final class ClusterResult {
        public final Map<Integer, List<Integer>> clusters;
        public final List<String[]> mergeConflicts;

        public ClusterResult(Map<Integer, List<Integer>> clusters, List<String[]> mergeConflicts) {
            this.clusters = clusters;
            this.mergeConflicts = mergeConflicts;
        }
    }

    public static boolean[] computeSpamFlags(List<ContactRow> allRows, int idxFirst, int idxMiddle, int idxLast,
                                              int idxPrefix, int idxSuffix, int idxNickname) {
        boolean[] isSpam = new boolean[allRows.size()];
        for (int i = 0; i < allRows.size(); i++) {
            isSpam[i] = isSpamRow(allRows.get(i).values, idxFirst, idxMiddle, idxLast, idxPrefix, idxSuffix, idxNickname);
        }
        return isSpam;
    }

    /**
     * Clusters rows sharing a phone identity using union-find, processing the
     * strongest (fewest-sharers) signals first, and refusing any merge that
     * would push a cluster past MAX_CLUSTER_SIZE so widely-shared numbers
     * can't transitively fuse unrelated people. SPAM rows never participate.
     */
    public static ClusterResult buildClusters(List<ContactRow> allRows, boolean[] isSpam) {
        int n = allRows.size();
        int[] parent = new int[n];
        int[] size = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        java.util.Arrays.fill(size, 1);

        Map<String, List<Integer>> keyToRows = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (isSpam[i]) {
                continue;
            }
            for (PhoneNormalizer.NormalizedPhone np : allRows.get(i).phones) {
                String key = np.keyType + "::" + np.canonicalKey;
                keyToRows.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }

        List<Map.Entry<String, List<Integer>>> orderedKeys = new ArrayList<>(keyToRows.entrySet());
        orderedKeys.sort((a, b) -> {
            int c = Integer.compare(a.getValue().size(), b.getValue().size());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });

        List<String[]> mergeConflicts = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : orderedKeys) {
            List<Integer> idxs = entry.getValue();
            if (idxs.size() < 2) {
                continue;
            }
            LinkedHashSet<Integer> roots = new LinkedHashSet<>();
            for (int idx : idxs) {
                roots.add(find(parent, idx));
            }
            if (roots.size() <= 1) {
                continue;
            }
            int total = 0;
            for (int r : roots) {
                total += size[r];
            }
            if (total > MAX_CLUSTER_SIZE) {
                List<String> coords = new ArrayList<>();
                for (int idx : idxs) {
                    ContactRow r = allRows.get(idx);
                    coords.add(r.sourceFile + ":" + r.sourceRowNumber);
                }
                mergeConflicts.add(new String[] {
                        entry.getKey(), String.valueOf(idxs.size()), String.valueOf(total), String.join(" | ", coords)
                });
                continue;
            }
            Iterator<Integer> it = roots.iterator();
            int first = it.next();
            while (it.hasNext()) {
                first = union(parent, size, first, it.next());
            }
        }

        Map<Integer, List<Integer>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (isSpam[i]) {
                continue;
            }
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        return new ClusterResult(clusters, mergeConflicts);
    }

    public static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    public static int union(int[] parent, int[] size, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) {
            return ra;
        }
        parent[ra] = rb;
        size[rb] += size[ra];
        return rb;
    }

    public static boolean containsSpam(String[] values, int idx) {
        if (idx < 0 || idx >= values.length || values[idx] == null) {
            return false;
        }
        return values[idx].toUpperCase(Locale.ROOT).contains("SPAM");
    }

    public static boolean isSpamRow(String[] values, int idxFirst, int idxMiddle, int idxLast,
                                    int idxPrefix, int idxSuffix, int idxNickname) {
        return containsSpam(values, idxFirst) || containsSpam(values, idxMiddle) || containsSpam(values, idxLast)
                || containsSpam(values, idxPrefix) || containsSpam(values, idxSuffix) || containsSpam(values, idxNickname);
    }
}
