class TestEvalSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class EvalMetrics

    static void main(String[] args) {
        loader = new GroovyClassLoader(TestEvalSuite.classLoader)
        loader.parseClass(new File('lib/EvalMetrics.groovy'))
        EvalMetrics = loader.loadClass('EvalMetrics')

        testRecall()
        testNdcg()
        testReciprocalRankAndSuccess()
        testScoreAndAggregate()
        testBootstrap()

        println "Eval tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        if (failed > 0) System.exit(1)
    }

    private static void testRecall() {
        Map labels = [a: 3, b: 2, c: 1, d: 0, e: 2]
        verify('recall at 2 finds one of three relevant', near(EvalMetrics.recallAtK(['a', 'x', 'b'], labels, 2), 1 / 3.0d))
        verify('recall at 3 finds two of three', near(EvalMetrics.recallAtK(['a', 'x', 'b'], labels, 3), 2 / 3.0d))
        verify('recall ignores grade 1 and 0', near(EvalMetrics.recallAtK(['c', 'd'], labels, 10), 0.0d))
        verify('recall counts a repeated id once', near(EvalMetrics.recallAtK(['a', 'a', 'a'], labels, 10), 1 / 3.0d))
        verify('recall is undefined without a relevant document', EvalMetrics.recallAtK(['a'], [a: 1], 10) == null)
    }

    private static void testNdcg() {
        Map labels = [a: 3, b: 2, c: 1]
        double idealDcg = 7.0d + 3.0d / (Math.log(3) / Math.log(2)) + 1.0d / 2.0d
        verify('ndcg of the ideal order is 1', near(EvalMetrics.ndcgAtK(['a', 'b', 'c'], labels, 10), 1.0d))
        double swapped = 3.0d + 7.0d / (Math.log(3) / Math.log(2)) + 1.0d / 2.0d
        verify('ndcg of a swapped order', near(EvalMetrics.ndcgAtK(['b', 'a', 'c'], labels, 10), swapped / idealDcg))
        verify('ndcg of a miss is 0', near(EvalMetrics.ndcgAtK(['x', 'y'], labels, 10), 0.0d))
        double onlyTop = 7.0d
        verify('ndcg at 1', near(EvalMetrics.ndcgAtK(['a', 'b'], labels, 1), onlyTop / 7.0d))
        verify('ndcg is undefined without graded documents', EvalMetrics.ndcgAtK(['a'], [a: 0], 10) == null)
    }

    private static void testReciprocalRankAndSuccess() {
        Map labels = [a: 3, b: 2, c: 1]
        verify('mrr first relevant at rank 2', near(EvalMetrics.reciprocalRank(['x', 'b', 'a'], labels, 10), 0.5d))
        verify('mrr ignores grade 1', near(EvalMetrics.reciprocalRank(['c', 'a'], labels, 10), 0.5d))
        verify('mrr beyond k is 0', near(EvalMetrics.reciprocalRank(['x', 'y', 'a'], labels, 2), 0.0d))
        verify('success at 1', near(EvalMetrics.successAtK(['a', 'x'], labels, 1), 1.0d))
        verify('success at 3 misses', near(EvalMetrics.successAtK(['x', 'y', 'z', 'a'], labels, 3), 0.0d))
    }

    private static void testScoreAndAggregate() {
        Map labels = [a: 3, b: 2]
        Map hit = EvalMetrics.scoreQuery(['a', 'b'], labels) as Map
        Map empty = EvalMetrics.scoreQuery([], labels) as Map
        verify('empty result is a zero result', near(empty.zero_result, 1.0d) && near(empty.ndcg_10, 0.0d))
        verify('hit is not a zero result', near(hit.zero_result, 0.0d) && near(hit.ndcg_10, 1.0d))
        Map unlabeled = EvalMetrics.scoreQuery(['a'], [a: 1]) as Map
        verify('unlabeled query keeps undefined metrics', unlabeled.ndcg_10 != null && unlabeled.recall_10 == null)
        Map aggregate = EvalMetrics.aggregate([hit, empty, unlabeled]) as Map
        verify('aggregate counts all queries', aggregate.queries == 3)
        verify('aggregate zero-result rate', near(aggregate.zero_result, 1 / 3.0d))
        verify('aggregate skips undefined recall', near(aggregate.recall_10, 0.5d))
    }

    private static void testBootstrap() {
        List<Double> a = (1..40).collect { 0.2d }
        List<Double> b = (1..40).collect { 0.6d }
        Map clear = EvalMetrics.bootstrapDifference(a, b, 2000, 7L) as Map
        verify('bootstrap mean difference', near(clear.mean_diff, 0.4d))
        verify('bootstrap interval of a constant gain is tight', near(clear.ci_low, 0.4d) && near(clear.ci_high, 0.4d))
        List<Double> noisyA = (1..40).collect { it % 2 == 0 ? 0.0d : 1.0d }
        List<Double> noisyB = (1..40).collect { it % 2 == 0 ? 1.0d : 0.0d }
        Map noisy = EvalMetrics.bootstrapDifference(noisyA, noisyB, 2000, 7L) as Map
        verify('bootstrap interval of symmetric noise contains zero', (noisy.ci_low as double) < 0.0d && (noisy.ci_high as double) > 0.0d)
        Map again = EvalMetrics.bootstrapDifference(noisyA, noisyB, 2000, 7L) as Map
        verify('bootstrap is deterministic for a seed', noisy == again)
        Map skipped = EvalMetrics.bootstrapDifference([0.1d, null, 0.3d], [0.2d, 0.9d, null], 100, 1L) as Map
        verify('bootstrap skips pairs with an undefined value', skipped.pairs == 1)
        verify('bootstrap of nothing is undefined', EvalMetrics.bootstrapDifference([], []).mean_diff == null)
    }

    private static boolean near(Object actual, double expected) {
        return actual != null && Math.abs((actual as double) - expected) < 1.0e-9
    }

    private static void verify(String name, boolean condition) {
        if (condition) {
            passed++
        } else {
            failed++
            failures << name
        }
    }
}
