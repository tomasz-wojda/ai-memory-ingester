/**
 * Stages 2 to 4 of the retrieval pipeline: passages, re-rank through the memory service, fusion.
 * The model score is one more ranked signal. It never removes a candidate and never moves a state capsule
 * behind other results of its ticket. Any service problem leaves the lexical order untouched.
 */
class RerankStage {

    /**
     * @param rows Fused candidates with content, best first (the top rerank depth)
     * @param query The question as the user typed it (without a ticket key)
     * @param queryWords Content words of the query, for passage selection
     * @param options rerank_budget_ms, rerank_model, rerank_min_scored
     * @param client Memory service client
     * @param pinTicket Ticket facet (for example kd8130) whose state capsule stays first, or null
     * @return Map with rows (re-ordered copy) and info (model, device, scored, skipped, elapsed_ms, depth, degraded, reason)
     */
    static Map apply(List<Map> rows, String query, List<String> queryWords, Map options, SemanticClient client, String pinTicket) {
        int budget = (options.rerank_budget_ms ?: Config.RERANK_BUDGET_MS) as int
        Map info = [depth: rows.size(), degraded: false, reason: null]
        List<Map> passages = []
        Map<String, String> docOfPassage = [:]
        rows.each { Map row ->
            List<Map> chosen = PassageBuilder.select(PassageBuilder.split(row), queryWords)
            chosen.each { Map passage ->
                if (passages.size() < 100) {
                    passages << passage
                    docOfPassage[passage.id as String] = SignalFusion.stableIdOf(row)
                }
            }
        }
        info.passages_sent = passages.size()
        if (passages.isEmpty()) return degraded(rows, info, 'too_few_scored')
        Map reply = client.rerank(query, passages, options.rerank_model?.toString(), budget)
        if (!reply.ok) return degraded(rows, info, reply.reason.toString())
        Map body = reply.body as Map
        List<Map> results = (body.results ?: []) as List<Map>
        int minimum = Math.min((options.rerank_min_scored ?: Config.RERANK_MIN_SCORED) as int, passages.size())
        int scored = (body.scored ?: 0) as int
        info << [model: body.model, device: body.device, scored: scored, skipped: body.skipped ?: 0, elapsed_ms: body.elapsed_ms]
        if (scored < minimum) return degraded(rows, info, 'too_few_scored')

        Map<String, Map> best = [:]
        Map<String, Map> passageById = passages.collectEntries { [(it.id as String): it] }
        results.each { Map result ->
            if (result.status == 'scored' && result.score != null) {
                String docId = docOfPassage[result.id as String]
                double score = Math.round((result.score as double) * 10000.0d) / 10000.0d
                Map current = best[docId]
                if (current == null || score > (current.score as double)) {
                    best[docId] = [score: score, passage: passageById[result.id as String]]
                }
            }
        }
        List<Map> copies = rows.collect { Map row -> new LinkedHashMap(row) as Map }
        List<Map> scoredRows = copies.findAll { best.containsKey(SignalFusion.stableIdOf(it)) }
        Map<String, Integer> priorRank = [:]
        copies.eachWithIndex { Map row, int index -> priorRank[SignalFusion.stableIdOf(row)] = index }
        List<Map> byModel = scoredRows.sort(false) { Map a, Map b ->
            double sa = best[SignalFusion.stableIdOf(a)].score as double
            double sb = best[SignalFusion.stableIdOf(b)].score as double
            sb <=> sa ?: priorRank[SignalFusion.stableIdOf(a)] <=> priorRank[SignalFusion.stableIdOf(b)] ?: SignalFusion.stableIdOf(a) <=> SignalFusion.stableIdOf(b)
        }
        byModel.eachWithIndex { Map row, int index ->
            Map hit = best[SignalFusion.stableIdOf(row)]
            row.rerank_score = hit.score
            row.rerank_rank = index + 1
            row.rerank_model = body.model
            row.best_passage = (hit.passage?.text ?: '').toString()
        }
        SignalFusion.addSignal(copies, 'rerank', byModel)
        if (pinTicket) {
            Closure isCapsule = { Map row -> row.source_archive == 'worklog:state' && row.file_path?.toString() == "${pinTicket}.md".toString() }
            copies = copies.findAll(isCapsule) + copies.findAll { !isCapsule(it) }
        }
        return [rows: copies, info: info]
    }

    private static Map degraded(List<Map> rows, Map info, String reason) {
        info.degraded = true
        info.reason = reason
        return [rows: rows, info: info]
    }
}
