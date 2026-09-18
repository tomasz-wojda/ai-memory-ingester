import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.regex.Pattern

class MemoryRetrieval {

    static final int POOL_LIMIT = 50
    static final Pattern TICKET_TOKEN = ~/^(?i)(?:[A-Z]{2,10}-\d+|SCTASK\d{6,10})$/

    static Map searchTopic(
        File dataDir,
        String datasetName,
        String queryText,
        int limit = 20,
        Map options = [:]
    ) {
        long started = System.nanoTime()
        ParsedQuery parsed = parseQuery(queryText, options)
        List<File> databases = scopedDatabases(dataDir, options.explicit_db?.toString(), datasetName)
        databases = databases.findAll { Config.isSearchableDatabase(it.name) }
        if (parsed.ticket && !parsed.terms?.trim()) {
            return ticketResult(dataDir, parsed.ticket, '', limit, options, started)
        }
        Map<String, List<Map>> pools = [:]
        pools.exact = exactFacetPool(databases, parsed, POOL_LIMIT)
        pools.bm25 = bodyPool(databases, parsed, POOL_LIMIT)
        if (parsed.hasFacet()) {
            pools.recency = recencyPool(databases, parsed, POOL_LIMIT)
            pools.authority = authorityPool(databases, parsed, POOL_LIMIT)
            pools.impact = impactPool(databases, parsed, POOL_LIMIT)
        } else {
            pools.recency = pools.bm25.toSorted { Map left, Map right ->
                (right.sequence as long) <=> (left.sequence as long)
            }
            pools.authority = pools.bm25.findAll { Map row ->
                row.origin_db == Config.WORKLOGS_DB_NAME
            }.toSorted { Map left, Map right ->
                authorityRank(left) <=> authorityRank(right)
            }
            pools.impact = pools.bm25.findAll { Map row ->
                row.source_archive == 'chat:turns' && row.impact == 'high'
            }.toSorted(TicketFreshness.&compareTurnEvidence)
        }
        List<Map> fused = SignalFusion.fuse(pools, Math.max(limit * 2, limit))
        boolean allowMeta = parsed.terms ==~ /(?is).*(?:retriev|memory|snippet|worklog\s+comparison|stale).*/
        fused = NeighborExpansion.expand(fused, dataDir, allowMeta, 1)
        fused = fused.findAll { !it.excluded_reason }.take(Math.max(limit * 2, limit))
        fused = hydrateDocuments(fused, dataDir)
        fused = applyFreshnessLabels(fused, dataDir, parsed.ticket)
        fused.each { enrichPreview(it, parsed.terms) }
        if (options.show_signals) {
            fused.each { Map row ->
                row.signal_summary = (row.signals ?: [:]).collect { k, v -> "${k}:${v}" }.join(' ')
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000.0d
        return [
            mode: 'topic',
            query: queryText,
            parsed: parsed.properties,
            results: fused.take(limit),
            pools: options.show_signals ? pools.collectEntries { k, v -> [k, v.size()] } : null,
            total_duration_ms: elapsedMs
        ]
    }

    static Map ticketResult(
        File dataDir,
        String ticket,
        String terms,
        int limit,
        Map options,
        long startedNano = System.nanoTime()
    ) {
        List<Map> discovered = TicketRetrieval.retrieve(
            dataDir,
            ticket,
            terms ?: '',
            Math.max(limit * 5, POOL_LIMIT)
        )
        Map<String, List<Map>> pools = [
            exact: discovered.findAll { it.pool == 'state' },
            bm25: discovered.findAll { it.pool == 'semantic' },
            recency: discovered.findAll { it.pool in ['newer-evidence', 'recent'] },
            authority: discovered.findAll { it.pool in ['state', 'authoritative'] },
            impact: discovered.findAll { it.pool in ['newer-evidence', 'impact'] }
        ]
        List<Map> candidates = SignalFusion.fuse(pools, Math.max(limit * 2, limit))
        candidates = NeighborExpansion.expand(candidates, dataDir, false, 1)
        candidates = applyFreshnessLabels(candidates, dataDir, ticket)
        candidates.each { Map candidate ->
            candidate.snippet = TicketRetrieval.bodyPreview(candidate.content?.toString() ?: '', terms ?: '')
        }
        if (options.complete_context) {
            Map packed = ContextPacker.pack(candidates, (options.budget_bytes ?: Config.TICKET_CONTEXT_BUDGET_BYTES) as int, true)
            long elapsedMs = (System.nanoTime() - startedNano) / 1_000_000.0d
            return [
                mode: 'ticket-context',
                ticket: ticket,
                assembly: packed,
                results: packed.items,
                total_duration_ms: elapsedMs
            ]
        }
        long elapsedMs = (System.nanoTime() - startedNano) / 1_000_000.0d
        return [
            mode: 'ticket',
            ticket: ticket,
            results: candidates.take(limit),
            pools: options.show_signals ? pools.collectEntries { key, value -> [key, value.size()] } : null,
            total_duration_ms: elapsedMs
        ]
    }

    static Map resume(
        File dataDir,
        String datasetName,
        int budgetBytes,
        Map options = [:]
    ) {
        long started = System.nanoTime()
        List<File> databases = scopedDatabases(dataDir, options.explicit_db?.toString(), datasetName)
        databases = databases.findAll { Config.isSearchableDatabase(it.name) }
        ParsedQuery parsed = new ParsedQuery(
            ticket: options.ticket_facet,
            mode: options.mode_facet,
            impact: options.impact_facet,
            since: options.since_date,
            until: options.until_date
        )
        List<Map> recent = []
        databases.each { File database ->
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                engine.recentTurnDocuments(parsed.ticket, Config.RESUME_RECENT_TURNS * 2, parsed.facets()).each { Map row ->
                    recent << enrichDocumentRow(row, database.name)
                }
            } finally {
                engine.close()
            }
        }
        recent.sort { Map left, Map right ->
            int bySequence = (right.sequence as long) <=> (left.sequence as long)
            if (bySequence != 0) return bySequence
            left.origin_db <=> right.origin_db ?: left.file_path <=> right.file_path
        }
        recent = recent.take(Config.RESUME_RECENT_TURNS)
        String dominant = parsed.ticket ?: dominantTicket(recent)
        Map<String, List<Map>> pools = [:]
        if (dominant) {
            parsed.ticket = dominant
            pools.exact = exactFacetPool(databases, parsed, 10)
            pools.authority = authorityPool(databases, parsed, 10)
            pools.impact = impactPool(databases, parsed, 10)
        }
        pools.artifacts = artifactPool(databases, 10)
        List<Map> fused = SignalFusion.fuse(pools, 30)
        fused = NeighborExpansion.expand(fused, dataDir, false, 1)
        fused = hydrateDocuments(fused, dataDir)
        fused = applyFreshnessLabels(fused, dataDir, dominant)
        recent = hydrateDocuments(recent, dataDir)
        Set<String> recentIds = recent.collect { SignalFusion.stableIdOf(it) } as Set<String>
        List<Map> ordered = recent + fused.findAll { !recentIds.contains(SignalFusion.stableIdOf(it)) }
        Map packed = ContextPacker.pack(ordered, budgetBytes, true)
        long elapsedMs = (System.nanoTime() - started) / 1_000_000.0d
        return [
            mode: 'resume',
            assembly: packed,
            results: packed.items,
            total_duration_ms: elapsedMs
        ]
    }

    static Map assembleContext(List<Map> candidates, int budgetBytes = Config.TICKET_CONTEXT_BUDGET_BYTES) {
        return ContextPacker.pack(candidates, budgetBytes, true)
    }

    private static List<Map> exactFacetPool(List<File> databases, ParsedQuery parsed, int limit) {
        if (!parsed.hasFacet()) return []
        List<Map> rows = []
        databases.each { File database ->
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                engine.filterByPathFacets(parsed.facets(), limit).each { Map row ->
                    rows << enrichDocumentRow(row, database.name) + [pool: 'exact']
                }
            } finally {
                engine.close()
            }
        }
        rows.sort { Map left, Map right ->
            int byAuthority = authorityRank(left) <=> authorityRank(right)
            if (byAuthority != 0) return byAuthority
            ((right.sequence ?: 0L) as long) <=> ((left.sequence ?: 0L) as long)
        }
        return rows.take(limit)
    }

    private static List<Map> bodyPool(List<File> databases, ParsedQuery parsed, int limit) {
        if (!parsed.terms?.trim()) return []
        List<Map> rows = []
        databases.each { File database ->
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                String expression = parsed.ticket ? "${parsed.terms}" : parsed.terms
                engine.searchBody(expression, limit).each { Map result ->
                    rows << enrichSearchRow(result, database.name, 'bm25')
                }
            } finally {
                engine.close()
            }
        }
        rows.sort { Map left, Map right -> (left.rank as double) <=> (right.rank as double) }
        return rows.take(limit)
    }

    private static List<Map> recencyPool(List<File> databases, ParsedQuery parsed, int limit) {
        List<Map> rows = []
        databases.each { File database ->
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                engine.recentTurnDocuments(parsed.ticket, limit, parsed.facets()).each { Map row ->
                    rows << enrichDocumentRow(row, database.name) + [pool: 'recency']
                }
            } finally {
                engine.close()
            }
        }
        rows.sort { Map left, Map right -> (right.sequence as long) <=> (left.sequence as long) }
        return rows.take(limit)
    }

    private static List<Map> authorityPool(List<File> databases, ParsedQuery parsed, int limit) {
        List<Map> rows = []
        databases.each { File database ->
            if (database.name != Config.WORKLOGS_DB_NAME) return
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                Map facets = parsed.facets()
                facets.ticket = parsed.ticket ?: facets.ticket
                engine.filterByPathFacets(facets, limit).findAll {
                    it.source_archive?.toString() in [Config.WORKLOG_STATE_ARCHIVE, Config.WORKLOG_CHUNKS_ARCHIVE]
                }.each { Map row ->
                    rows << enrichDocumentRow(row, database.name) + [
                        pool: 'authority',
                        authority: authorityOf(row.content?.toString()),
                        state: stateOf(row.content?.toString()),
                        freshness: freshnessOf(row.content?.toString())
                    ]
                }
            } finally {
                engine.close()
            }
        }
        rows.sort { Map left, Map right -> authorityRank(left) <=> authorityRank(right) }
        return rows.take(limit)
    }

    private static List<Map> impactPool(List<File> databases, ParsedQuery parsed, int limit) {
        List<Map> rows = []
        databases.each { File database ->
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                Map facets = parsed.facets()
                facets.impact = facets.impact ?: 'impact-high'
                engine.filterByPathFacets(facets + [impact: 'impact-high'], limit).findAll {
                    it.source_archive?.toString() == 'chat:turns'
                }.each { Map row ->
                    Map enriched = enrichDocumentRow(row, database.name) + [pool: 'impact']
                    enrichTurnMetadata(enriched)
                    if (!enriched.retrieval_meta) rows << enriched
                }
            } finally {
                engine.close()
            }
        }
        rows.sort(TicketFreshness.&compareTurnEvidence)
        return rows.take(limit)
    }

    private static List<Map> artifactPool(List<File> databases, int limit) {
        List<Map> rows = []
        databases.findAll { File database -> database.name == Config.ARTIFACTS_DB_NAME }.each { File database ->
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                engine.filterByPathFacets([:], limit, 'chat:artifacts').each { Map row ->
                    rows << enrichDocumentRow(row, database.name) + [pool: 'artifact']
                }
            } finally {
                engine.close()
            }
        }
        rows.sort { Map left, Map right -> (right.sequence as long) <=> (left.sequence as long) }
        return rows.take(limit)
    }

    private static List<Map> hydrateDocuments(List<Map> candidates, File dataDir) {
        Map<String, MemoryEngine> engines = [:]
        try {
            return candidates.collect { Map candidate ->
                if (candidate.content) return candidate
                File database = new File(dataDir, candidate.origin_db?.toString() ?: '')
                if (!database.exists()) database = Config.resolveDatabase(candidate.origin_db?.toString() ?: '')
                MemoryEngine engine = engines.computeIfAbsent(database.absolutePath) {
                    new MemoryEngine(it)
                }
                Map document = engine.getDocument(candidate.id as int)
                if (document) {
                    return new LinkedHashMap(candidate) + [content: document.content]
                }
                candidate
            }
        } finally {
            engines.values().each { MemoryEngine engine ->
                try { engine.close() } catch (Exception ignored) {}
            }
        }
    }

    private static List<Map> applyFreshnessLabels(List<Map> candidates, File dataDir, String ticket) {
        if (!ticket) return candidates
        File worklogs = new File(dataDir, Config.WORKLOGS_DB_NAME)
        if (!worklogs.exists()) return candidates
        String facet = TicketKey.supported(ticket) ? TicketKey.facet(ticket) : ticket
        List<Map> state = TicketRetrieval.stateCandidates(worklogs, facet, 1)
        if (!state) return candidates
        boolean stale = state[0].freshness == 'stale'
        candidates.each { Map candidate ->
            if (candidate.pool == 'newer-evidence') candidate.freshness = 'newer-evidence'
            else if (stale && candidate.pool in ['state', 'authority']) candidate.freshness = 'stale'
        }
        return candidates
    }

    private static Map enrichSearchRow(Map result, String originDb, String pool) {
        Map row = new LinkedHashMap(result) + [origin_db: originDb, pool: pool, sequence: sequenceOf(result.file_name?.toString())]
        enrichTurnMetadata(row)
        return row
    }

    private static Map enrichDocumentRow(Map row, String originDb) {
        Map enriched = new LinkedHashMap(row) + [
            origin_db: originDb,
            sequence: row.sequence ?: sequenceOf(row.file_name?.toString())
        ]
        enrichTurnMetadata(enriched)
        return enriched
    }

    private static void enrichTurnMetadata(Map candidate) {
        String content = candidate.content?.toString() ?: ''
        Map metadata = TicketFreshness.frontMatter(content)
        candidate.event_time = TicketFreshness.parseInstant(metadata.timestamp?.toString())?.toString()
        candidate.event_instant = TicketFreshness.parseInstant(metadata.timestamp?.toString())
        candidate.timestamp_confidence = metadata.timestamp_confidence ?: 'unknown'
        candidate.impact = metadata.impact ?: 'none'
        candidate.retrieval_meta = TicketFreshness.isRetrievalMeta(content)
        candidate.authority = candidate.authority ?: authorityOf(content)
        candidate.state = candidate.state ?: stateOf(content)
        candidate.freshness = candidate.freshness ?: freshnessOf(content)
    }

    private static void enrichPreview(Map candidate, String terms) {
        candidate.snippet = TicketRetrieval.bodyPreview(candidate.content?.toString() ?: '', terms ?: '')
    }

    private static ParsedQuery parseQuery(String queryText, Map options) {
        String text = (queryText ?: '').trim()
        String ticket = options.ticket_facet?.toString()
        String terms = text
        if (!ticket && multiTicketBooleanQuery(text)) {
            return buildParsedQuery(null, text, options)
        }
        if (!ticket) {
            List<String> tokens = text.tokenize(' ')
            List<String> kept = []
            tokens.each { String token ->
                if (!ticket && TICKET_TOKEN.matcher(token).matches()) {
                    ticket = token
                } else {
                    kept << token
                }
            }
            terms = kept.join(' ').trim()
            if (ticket && terms ==~ /(?is)^(OR|AND)\b.*/) {
                terms = "${ticket} ${terms}".trim()
                ticket = null
            }
        }
        return buildParsedQuery(ticket, terms, options)
    }

    private static boolean multiTicketBooleanQuery(String text) {
        if (!(text ==~ /(?is).*\b(?:OR|AND)\b.*/)) return false
        int ticketCount = 0
        text.tokenize(' ').each { String token ->
            String cleaned = token.replaceAll(/^[\(]+|[\),]+$/, '')
            if (TICKET_TOKEN.matcher(cleaned).matches()) {
                ticketCount++
            }
        }
        return ticketCount >= 2
    }

    private static ParsedQuery buildParsedQuery(String ticket, String terms, Map options) {
        return new ParsedQuery(
            ticket: ticket ? TicketKey.facet(ticket) : null,
            terms: terms,
            mode: options.mode_facet ? normalizeMode(options.mode_facet.toString()) : null,
            impact: options.impact_facet ? normalizeImpact(options.impact_facet.toString()) : null,
            since: options.since_date?.toString(),
            until: options.until_date?.toString()
        )
    }

    private static List<File> scopedDatabases(File dataDir, String explicitDb, String datasetName) {
        if (explicitDb) {
            if (!Config.isSearchableDatabase(explicitDb)) return []
            File database = Config.resolveDatabase(explicitDb)
            return database.exists() ? [database] : []
        }
        Config.setDataDir(dataDir.absolutePath)
        return DatasetRegistry.getDatabasesForDataset(datasetName ?: DatasetRegistry.getActiveDataset())
    }

    private static String dominantTicket(List<Map> rows) {
        Map<String, Integer> counts = [:].withDefault { 0 }
        rows.each { Map row ->
            String ticket = row.file_path?.toString()?.tokenize('/')?.find { it ==~ /[a-z]+[0-9]+|noticket/ }
            if (ticket && ticket != 'noticket') counts[ticket] = counts[ticket] + 1
        }
        if (!counts) return null
        def winner = counts.max { a, b -> a.value <=> b.value }
        return winner.value > (rows.size() / 2) ? winner.key : null
    }

    private static int authorityRank(Map row) {
        switch (row.authority?.toString()) {
            case 'state': return 0
            case 'authoritative': return 1
            case 'historical': return 2
            default: return 3
        }
    }

    private static String authorityOf(String content) {
        def matcher = content =~ /(?m)^authority:\s*"([^"]+)"/
        return matcher.find() ? matcher.group(1) : 'historical'
    }

    private static String stateOf(String content) {
        def matcher = content =~ /(?m)^state:\s*"([^"]+)"/
        return matcher.find() ? matcher.group(1) : 'historical'
    }

    private static String freshnessOf(String content) {
        def matcher = content =~ /(?m)^freshness:\s*"([^"]+)"/
        return matcher.find() ? matcher.group(1) : 'undetermined'
    }

    private static String normalizeMode(String value) {
        String token = (value ?: '').toLowerCase(Locale.ROOT).replaceAll(/[^a-z0-9]/, '')
        return token in ['research', 'innovate', 'plan', 'execute'] ? token : null
    }

    private static String normalizeImpact(String value) {
        String token = (value ?: '').toLowerCase(Locale.ROOT).replaceAll(/[^a-z0-9]/, '')
        return "impact-${token in ['high', 'low', 'none'] ? token : 'none'}"
    }

    private static long sequenceOf(String fileName) {
        def matcher = (fileName ?: '') =~ /(?:turn|artifact)-(\d+)\.md$/
        return matcher.find() ? matcher.group(1).toLong() : 0L
    }

    private static class ParsedQuery {
        String ticket
        String terms
        String mode
        String impact
        String since
        String until

        Map facets() {
            return [
                ticket: ticket,
                mode: mode,
                impact: impact,
                since: since,
                until: until
            ].findAll { k, v -> v }
        }

        boolean hasFacet() {
            return ticket || mode || impact || since || until
        }

        Map getProperties() {
            return facets() + [terms: terms]
        }
    }
}
