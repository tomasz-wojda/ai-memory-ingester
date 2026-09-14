class NeighborExpansion {

    static List<Map> expand(List<Map> candidates, File dataDir, boolean allowRetrievalMeta = false, int radius = 1) {
        if (!candidates || radius <= 0) return candidates ?: []
        List<Map> expanded = []
        Set<String> seen = [] as Set<String>
        Map<String, MemoryEngine> engines = [:]
        try {
            candidates.each { Map candidate ->
                String stableId = SignalFusion.stableIdOf(candidate)
                if (!seen.contains(stableId)) {
                    expanded << candidate
                    seen << stableId
                }
                if (candidate.source_archive?.toString() != 'chat:turns') return
                if (!allowRetrievalMeta && candidate.retrieval_meta) return
                File database = resolveDatabase(dataDir, candidate.origin_db?.toString())
                if (!database?.exists()) return
                MemoryEngine engine = engines.computeIfAbsent(database.absolutePath) {
                    new MemoryEngine(it)
                }
                engine.neighborTurnDocuments(
                    candidate.file_path?.toString(),
                    candidate.file_name?.toString(),
                    radius
                ).each { Map neighbor ->
                    enrichTurn(neighbor, database.name, allowRetrievalMeta)
                    if (!allowRetrievalMeta && neighbor.excluded_reason) return
                    String neighborId = SignalFusion.stableIdOf(neighbor)
                    if (!seen.contains(neighborId)) {
                        neighbor.pool = 'neighbor'
                        expanded << neighbor
                        seen << neighborId
                    }
                }
            }
        } finally {
            engines.values().each { MemoryEngine engine ->
                try { engine.close() } catch (Exception ignored) {}
            }
        }
        return expanded
    }

    private static File resolveDatabase(File dataDir, String originDb) {
        if (!originDb) return null
        File direct = new File(dataDir, originDb)
        return direct.exists() ? direct : Config.resolveDatabase(originDb)
    }

    private static void enrichTurn(Map candidate, String originDb, boolean allowRetrievalMeta) {
        candidate.origin_db = originDb
        candidate.sequence = candidate.sequence ?: sequenceOf(candidate.file_name?.toString())
        String content = candidate.content?.toString() ?: ''
        Map metadata = TicketFreshness.frontMatter(content)
        candidate.event_time = TicketFreshness.parseInstant(metadata.timestamp?.toString())?.toString()
        candidate.event_instant = TicketFreshness.parseInstant(metadata.timestamp?.toString())
        candidate.timestamp_confidence = metadata.timestamp_confidence ?: 'unknown'
        candidate.impact = metadata.impact ?: 'none'
        candidate.retrieval_meta = TicketFreshness.isRetrievalMeta(content)
        if (!allowRetrievalMeta && candidate.retrieval_meta) {
            candidate.excluded_reason = 'retrieval_meta'
        }
        candidate.logical_turn_key = metadata.logical_turn_key ?: metadata.turn_hash ?: null
    }

    private static long sequenceOf(String fileName) {
        def matcher = (fileName ?: '') =~ /turn-(\d+)\.md$/
        return matcher.find() ? matcher.group(1).toLong() : 0L
    }
}
