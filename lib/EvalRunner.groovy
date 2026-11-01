/**
 * Runs a labelled query set against one or more retrieval systems and reports metrics.
 * Systems S0 and S1 are the Phase 0 systems: S0 is the baseline (relaxed query off),
 * S1 adds the relaxed first-stage query.
 */
class EvalRunner {

    static final Map<String, Map> SYSTEMS = [
        S0: [name: 'baseline: strict query only', options: [relax_mode: 'off', rerank_mode: 'off', late_mode: 'off']],
        S1: [name: 'strict, then relaxed query when the strict query finds nothing', options: [relax_mode: 'auto', rerank_mode: 'off', late_mode: 'off']],
        S2: [name: 'S1 plus re-rank through the memory service', options: [relax_mode: 'auto', rerank_mode: 'on', late_mode: 'off']],
        S5: [name: 'S2 plus the late-interaction pool', options: [relax_mode: 'auto', rerank_mode: 'on', late_mode: 'on']],
        S6: [name: 'S5 with a late-interaction re-score instead of the cross-encoder', options: [relax_mode: 'auto', rerank_mode: 'late', late_mode: 'on']]
    ].asImmutable()

    static final int RESULT_LIMIT = 100

    /**
     * Runs one system over all queries.
     *
     * @param dataDir Data directory
     * @param dataset Dataset name
     * @param queries Queries with labels
     * @param systemId Key of SYSTEMS
     * @param asOfSequence Results of turns with a higher sequence are ignored (0 turns the filter off)
     * @return Map with system, per_query, aggregate, by_class, by_language, elapsed_ms
     */
    static Map runSystem(File dataDir, String dataset, List<Map> queries, String systemId, long asOfSequence = 0L, Map extraOptions = [:]) {
        Map system = SYSTEMS[systemId]
        if (system == null) throw new IllegalArgumentException("Unknown system ${systemId}")
        List<Map> perQuery = []
        long started = System.nanoTime()
        queries.each { Map query ->
            long queryStarted = System.nanoTime()
            Map result = MemoryRetrieval.searchTopic(dataDir, dataset, query.query.toString(), RESULT_LIMIT, new LinkedHashMap(system.options as Map) + extraOptions)
            List<String> ranked = rankedIds(result.results as List<Map>, asOfSequence)
            Map scores = EvalMetrics.scoreQuery(ranked, GoldSet.labelMap(query)) as Map
            perQuery << [
                qid: query.qid, 'class': query['class'], lang: query.lang, split: query.split, known_gap: query.known_gap ?: false,
                results: ranked.size(), form_used: (result.query_plan as Map)?.form_used,
                degraded: (system.options.rerank_mode in ['on', 'late']) && result.degraded == true, degraded_reason: result.degraded_reason,
                elapsed_ms: (System.nanoTime() - queryStarted) / 1_000_000.0d
            ] + scores
        }
        return [
            system: systemId,
            description: system.name,
            per_query: perQuery,
            aggregate: EvalMetrics.aggregate(perQuery),
            degraded_queries: perQuery.count { it.degraded } as int,
            by_class: groupAggregate(perQuery, 'class'),
            by_language: groupAggregate(perQuery, 'lang'),
            elapsed_ms: (System.nanoTime() - started) / 1_000_000.0d
        ]
    }

    /**
     * Compares systems against the first one with a paired bootstrap on nDCG@10 and on the zero-result rate.
     *
     * @param runs Results of runSystem, first one is the reference
     * @return One map per later system
     */
    static List<Map> compare(List<Map> runs) {
        if (runs.size() < 2) return []
        Map reference = runs[0]
        return runs.drop(1).collect { Map run ->
            [
                system: run.system,
                against: reference.system,
                ndcg_10: EvalMetrics.bootstrapDifference(column(reference, 'ndcg_10'), column(run, 'ndcg_10')),
                zero_result: EvalMetrics.bootstrapDifference(column(reference, 'zero_result'), column(run, 'zero_result')),
                recall_50: EvalMetrics.bootstrapDifference(column(reference, 'recall_50'), column(run, 'recall_50'))
            ]
        }
    }

    /**
     * Writes the unlabelled top results of every system for each query, so that a person can label the pool.
     *
     * @return Rows with qid, system, rank, doc id and a short snippet
     */
    static List<Map> pool(File dataDir, String dataset, List<Map> queries, List<String> systemIds, int depth, long asOfSequence = 0L) {
        List<Map> rows = []
        queries.each { Map query ->
            Map seen = [:]
            systemIds.each { String systemId ->
                Map result = MemoryRetrieval.searchTopic(dataDir, dataset, query.query.toString(), RESULT_LIMIT,
                    new LinkedHashMap(SYSTEMS[systemId].options as Map))
                filterBySequence(result.results as List<Map>, asOfSequence).take(depth).eachWithIndex { Map row, int index ->
                    String id = SignalFusion.stableIdOf(row)
                    if (seen.containsKey(id)) {
                        (seen[id] as Map).systems << "${systemId}#${index + 1}".toString()
                    } else {
                        seen[id] = [qid: query.qid, query: query.query, doc: id, systems: ["${systemId}#${index + 1}".toString()],
                                    snippet: (row.snippet ?: '').toString().replaceAll(/\s+/, ' ').take(240), rel: null]
                    }
                }
            }
            rows.addAll(seen.values() as List<Map>)
        }
        return rows
    }

    private static List<String> rankedIds(List<Map> rows, long asOfSequence) {
        return filterBySequence(rows ?: [], asOfSequence).collect { Map row -> SignalFusion.stableIdOf(row) }
    }

    private static List<Map> filterBySequence(List<Map> rows, long asOfSequence) {
        if (asOfSequence <= 0L) return rows
        return rows.findAll { Map row -> ((row.sequence ?: 0L) as long) <= asOfSequence }
    }

    private static Map groupAggregate(List<Map> perQuery, String field) {
        Map groups = [:]
        perQuery.groupBy { it[field] }.sort().each { Object name, List<Map> items -> groups[name.toString()] = EvalMetrics.aggregate(items) }
        return groups
    }

    private static List<Double> column(Map run, String metric) {
        return (run.per_query as List<Map>).collect { Map row -> row[metric] as Double }
    }
}
