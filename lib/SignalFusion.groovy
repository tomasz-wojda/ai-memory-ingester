class SignalFusion {

    static final double RRF_K = 60.0d
    static final Map<String, Integer> SIGNAL_WEIGHTS = [
        exact: 4,
        bm25: 3,
        bm25_relaxed: 2,
        recency: 2,
        authority: 2,
        impact: 1,
        rerank: 12,
        late: 3
    ].asImmutable()

    static List<Map> fuse(Map<String, List<Map>> signalPools, int limit = 20) {
        Map<String, Map> aggregated = [:].withDefault { String stableId ->
            [stable_id: stableId, signals: [:], candidate: null, fused_score: 0.0d]
        }
        signalPools.each { String signal, List<Map> pool ->
            int weight = SIGNAL_WEIGHTS[signal] ?: 1
            pool.eachWithIndex { Map candidate, int index ->
                String stableId = stableIdOf(candidate)
                Map entry = aggregated[stableId]
                entry.candidate = mergeCandidate(entry.candidate, candidate)
                entry.signals[signal] = index + 1
                entry.fused_score = (entry.fused_score as double) + (weight / (RRF_K + index + 1))
            }
        }
        List<Map> ranked = aggregated.values().collect { Map entry ->
            Map candidate = new LinkedHashMap(entry.candidate ?: [:])
            candidate.stable_id = entry.stable_id
            candidate.signals = new LinkedHashMap(entry.signals as Map)
            candidate.fused_score = entry.fused_score as double
            candidate.rrf_score = candidate.fused_score
            candidate
        }
        ranked.sort(SignalFusion.&compareRanked)
        return ranked.take(Math.max(limit, 1))
    }

    /**
     * Adds one ranked signal to candidates that are already fused, then sorts them again.
     *
     * @param rows Fused candidates
     * @param signal Signal name (its weight comes from SIGNAL_WEIGHTS)
     * @param ranked Candidates in signal order, best first; only these get the signal
     * @param weightOverride Weight to use instead of the one in SIGNAL_WEIGHTS, or null
     * @return The same rows, re-sorted
     */
    static List<Map> addSignal(List<Map> rows, String signal, List<Map> ranked, Integer weightOverride = null) {
        int weight = weightOverride ?: (SIGNAL_WEIGHTS[signal] ?: 1)
        Map<String, Integer> ranks = [:]
        ranked.eachWithIndex { Map row, int index -> ranks[stableIdOf(row)] = index + 1 }
        rows.each { Map row ->
            Integer rank = ranks[stableIdOf(row)]
            if (rank != null) {
                row.signals = new LinkedHashMap((row.signals ?: [:]) as Map)
                row.signals[signal] = rank
                row.fused_score = ((row.fused_score ?: 0.0d) as double) + (weight / (RRF_K + rank))
                row.rrf_score = row.fused_score
            }
        }
        rows.sort(SignalFusion.&compareRanked)
        return rows
    }

    /**
     * Adds one scored signal to candidates that are already fused, then sorts them again. A row gets
     * weight / (RRF_K + 1) multiplied by the logistic of its score, so a certain top candidate adds what a rank 1
     * candidate adds in addSignal at the same weight. The stored signal value is the rank by score.
     *
     * @param rows Fused candidates
     * @param signal Signal name (its weight comes from SIGNAL_WEIGHTS)
     * @param scores Model score by stable id; only these rows get the signal
     * @param weightOverride Weight to use instead of the one in SIGNAL_WEIGHTS, or null
     * @return The same rows, re-sorted
     */
    static List<Map> addScoreSignal(List<Map> rows, String signal, Map<String, Double> scores, Integer weightOverride = null) {
        int weight = weightOverride ?: (SIGNAL_WEIGHTS[signal] ?: 1)
        List<String> ordered = scores.keySet().toList().sort(false) { String a, String b -> (scores[b] as double) <=> (scores[a] as double) ?: a <=> b }
        Map<String, Integer> ranks = [:]
        ordered.eachWithIndex { String id, int index -> ranks[id] = index + 1 }
        rows.each { Map row ->
            String id = stableIdOf(row)
            Double score = scores[id]
            if (score != null) {
                double logistic = 1.0d / (1.0d + Math.exp(-(score as double)))
                row.signals = new LinkedHashMap((row.signals ?: [:]) as Map)
                row.signals[signal] = ranks[id]
                row.fused_score = ((row.fused_score ?: 0.0d) as double) + (weight / (RRF_K + 1)) * logistic
                row.rrf_score = row.fused_score
            }
        }
        rows.sort(SignalFusion.&compareRanked)
        return rows
    }

    static String stableIdOf(Map candidate) {
        if (candidate.origin_db && candidate.source_archive && candidate.file_path) {
            return "${candidate.origin_db}:${candidate.source_archive}:${candidate.file_path}"
        }
        if (candidate.logical_turn_key) return "turn:${candidate.logical_turn_key}"
        if (candidate.journal_observation_id) return "obs:${candidate.journal_observation_id}"
        if (candidate.stable_id) return candidate.stable_id.toString()
        return "${candidate.origin_db ?: ''}:${candidate.source_archive ?: ''}:${candidate.file_path ?: candidate.file_name ?: ''}"
    }

    static int compareRanked(Map left, Map right) {
        int byScore = (right.fused_score as double) <=> (left.fused_score as double)
        if (byScore != 0) return byScore
        int byExact = signalRank(left, 'exact') <=> signalRank(right, 'exact')
        if (byExact != 0) return byExact
        int byAuthority = signalRank(left, 'authority') <=> signalRank(right, 'authority')
        if (byAuthority != 0) return byAuthority
        int byRecency = signalRank(left, 'recency') <=> signalRank(right, 'recency')
        if (byRecency != 0) return byRecency
        int bySequence = ((right.sequence ?: 0L) as long) <=> ((left.sequence ?: 0L) as long)
        if (bySequence != 0) return bySequence
        return stableIdOf(left) <=> stableIdOf(right)
    }

    private static int signalRank(Map candidate, String signal) {
        Integer rank = candidate.signals?.get(signal) as Integer
        return rank ?: Integer.MAX_VALUE
    }

    private static Map mergeCandidate(Map existing, Map incoming) {
        if (!existing) return new LinkedHashMap(incoming)
        Map merged = new LinkedHashMap(existing)
        incoming.each { key, value ->
            if (value != null && (!merged.containsKey(key) || merged[key] == null)) {
                merged[key] = value
            }
        }
        return merged
    }
}
