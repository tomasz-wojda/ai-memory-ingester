/**
 * Retrieval metrics for the evaluation harness: Recall@K, nDCG@10, MRR@10, Success@K,
 * zero-result rate and a paired bootstrap for the difference between two systems.
 * Relevance labels are graded 0 to 3. A document is "relevant" at grade 2 or higher.
 */
class EvalMetrics {

    static final int RELEVANT_GRADE = 2

    /**
     * @param ranked Document ids in result order
     * @param labels Grade by document id
     * @param k Cut-off
     * @return Share of relevant documents found in the top k, or null when the query has none
     */
    static Double recallAtK(List<String> ranked, Map<String, Integer> labels, int k) {
        Set<String> relevant = relevantIds(labels)
        if (relevant.isEmpty()) return null
        int found = ranked.take(k).unique().count { String id -> relevant.contains(id) } as int
        return found / (double) relevant.size()
    }

    /**
     * @param ranked Document ids in result order
     * @param labels Grade by document id
     * @param k Cut-off
     * @return nDCG with gain 2^grade - 1 and discount 1 / log2(rank + 1), or null when no document has a grade above 0
     */
    static Double ndcgAtK(List<String> ranked, Map<String, Integer> labels, int k) {
        List<Integer> ideal = labels.values().findAll { it > 0 }.sort { -it }.take(k)
        if (ideal.isEmpty()) return null
        double idealDcg = dcg(ideal)
        List<Integer> actual = ranked.take(k).unique().collect { String id -> labels.get(id, 0) as int }
        return dcg(actual) / idealDcg
    }

    /**
     * @param ranked Document ids in result order
     * @param labels Grade by document id
     * @param k Cut-off
     * @return Reciprocal rank of the first relevant document within k, 0 when none, null when the query has no relevant document
     */
    static Double reciprocalRank(List<String> ranked, Map<String, Integer> labels, int k) {
        Set<String> relevant = relevantIds(labels)
        if (relevant.isEmpty()) return null
        List<String> top = ranked.take(k)
        int index = top.findIndexOf { String id -> relevant.contains(id) }
        return index < 0 ? 0.0d : 1.0d / (index + 1)
    }

    /**
     * @return 1 when a relevant document is in the top k, 0 when not, null when the query has no relevant document
     */
    static Double successAtK(List<String> ranked, Map<String, Integer> labels, int k) {
        Set<String> relevant = relevantIds(labels)
        if (relevant.isEmpty()) return null
        return ranked.take(k).any { String id -> relevant.contains(id) } ? 1.0d : 0.0d
    }

    /**
     * Scores one query.
     *
     * @param ranked Document ids in result order
     * @param labels Grade by document id
     * @return Map with the metric values of this query (null where undefined) and zero_result
     */
    static Map scoreQuery(List<String> ranked, Map<String, Integer> labels) {
        return [
            zero_result: ranked.isEmpty() ? 1.0d : 0.0d,
            recall_10: recallAtK(ranked, labels, 10),
            recall_50: recallAtK(ranked, labels, 50),
            recall_100: recallAtK(ranked, labels, 100),
            ndcg_10: ndcgAtK(ranked, labels, 10),
            mrr_10: reciprocalRank(ranked, labels, 10),
            success_1: successAtK(ranked, labels, 1),
            success_3: successAtK(ranked, labels, 3)
        ]
    }

    /**
     * Averages the metric maps of several queries. A null value (undefined for that query) is skipped.
     *
     * @param scores Maps returned by scoreQuery
     * @return Map of metric name to mean, plus queries
     */
    static Map aggregate(List<Map> scores) {
        Map result = [queries: scores.size()]
        ['zero_result', 'recall_10', 'recall_50', 'recall_100', 'ndcg_10', 'mrr_10', 'success_1', 'success_3'].each { String name ->
            List<Double> values = scores.collect { it[name] as Double }.findAll { it != null }
            result[name] = values ? values.sum() / values.size() : null
        }
        return result
    }

    /**
     * Paired bootstrap over queries.
     *
     * @param a Per-query values of system A (same query order as b)
     * @param b Per-query values of system B
     * @param resamples Number of resamples
     * @param seed Seed, so that two runs give the same interval
     * @return Map with mean_diff (b minus a), ci_low, ci_high (95%) and pairs
     */
    static Map bootstrapDifference(List<Double> a, List<Double> b, int resamples = 10000, long seed = 42L) {
        List<Double> diffs = []
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (a[i] != null && b[i] != null) diffs << ((b[i] as double) - (a[i] as double))
        }
        if (diffs.isEmpty()) return [mean_diff: null, ci_low: null, ci_high: null, pairs: 0]
        Random random = new Random(seed)
        double[] means = new double[resamples]
        for (int r = 0; r < resamples; r++) {
            double sum = 0.0d
            for (int i = 0; i < diffs.size(); i++) sum += diffs[random.nextInt(diffs.size())]
            means[r] = sum / diffs.size()
        }
        Arrays.sort(means)
        double low = means[(int) Math.floor(0.025d * (resamples - 1))]
        double high = means[(int) Math.ceil(0.975d * (resamples - 1))]
        return [mean_diff: diffs.sum() / diffs.size(), ci_low: low, ci_high: high, pairs: diffs.size()]
    }

    private static Set<String> relevantIds(Map<String, Integer> labels) {
        return labels.findAll { String id, Integer grade -> grade >= RELEVANT_GRADE }.keySet()
    }

    private static double dcg(List<Integer> grades) {
        double total = 0.0d
        grades.eachWithIndex { Integer grade, int index ->
            total += (Math.pow(2.0d, grade) - 1.0d) / (Math.log(index + 2) / Math.log(2.0d))
        }
        return total
    }
}
