import groovy.sql.Sql
import java.nio.charset.StandardCharsets

class TicketRetrieval {
    static List<Map> retrieve(File dataDir, String ticket, String terms = '', int limit = 20) {
        String facet = normalizeTicket(ticket)
        List<Map> state = stateCandidates(new File(dataDir, Config.WORKLOGS_DB_NAME), facet, 1)
        List<Map> authoritative = authoritativeCandidates(new File(dataDir, Config.WORKLOGS_DB_NAME), facet, 4)
        List<Map> recent = recentTurnCandidates(new File(dataDir, Config.CONVERSATION_DB_NAME), facet, 5)
        List<Map> impact = impactCandidates(new File(dataDir, Config.CONVERSATION_DB_NAME), facet, 5)
        List<Map> semantic = terms?.trim()
            ? semanticCandidates(dataDir, facet, terms.trim(), 5)
            : authoritativeCandidates(new File(dataDir, Config.WORKLOGS_DB_NAME), facet, 9).drop(authoritative.size()) +
                recentTurnCandidates(new File(dataDir, Config.CONVERSATION_DB_NAME), facet, 10).drop(recent.size())
        return mergeCandidates([state, authoritative, recent, impact, semantic], limit)
    }

    static List<Map> stateCandidates(File database, String facet, int limit) {
        return rows(database, '''
            SELECT id, source_archive, file_path, file_name, extension, content
            FROM documents
            WHERE source_archive = ? AND file_name = ?
            ORDER BY id DESC LIMIT ?
        ''', [Config.WORKLOG_STATE_ARCHIVE, "${facet}.md", limit], 'state')
    }

    static List<Map> authoritativeCandidates(File database, String facet, int limit) {
        return rows(database, '''
            SELECT id, source_archive, file_path, file_name, extension, content
            FROM documents
            WHERE source_archive = ? AND file_path LIKE ?
            ORDER BY
              CASE
                WHEN file_path LIKE '%/status/%' THEN 1
                WHEN file_path LIKE '%/next-action/%' THEN 2
                WHEN file_path LIKE '%/delivery/%' THEN 3
                WHEN file_path LIKE '%/decisions/%' THEN 4
                WHEN file_path LIKE '%/summary/%' THEN 5
                WHEN file_path LIKE '%/findings/%' THEN 6
                ELSE 7
              END,
              CASE WHEN content LIKE '%state: "current"%' THEN 0 ELSE 1 END,
              id DESC
            LIMIT ?
        ''', [Config.WORKLOG_CHUNKS_ARCHIVE, "${facet}/%", limit], 'authoritative')
    }

    static List<Map> recentTurnCandidates(File database, String facet, int limit) {
        return rows(database, '''
            SELECT id, source_archive, file_path, file_name, extension, content
            FROM documents
            WHERE source_archive = 'chat:turns' AND file_path LIKE ?
            ORDER BY CAST(substr(file_name, 6, length(file_name) - 8) AS INTEGER) DESC
            LIMIT ?
        ''', ["${facet}/%", limit], 'recent')
    }

    static List<Map> impactCandidates(File database, String facet, int limit) {
        return rows(database, '''
            SELECT id, source_archive, file_path, file_name, extension, content
            FROM documents
            WHERE source_archive = 'chat:turns' AND file_path LIKE ? AND file_path LIKE '%/impact-high/%'
            ORDER BY CAST(substr(file_name, 6, length(file_name) - 8) AS INTEGER) DESC
            LIMIT ?
        ''', ["${facet}/%", limit], 'impact')
    }

    static List<Map> semanticCandidates(File dataDir, String facet, String terms, int limit) {
        List<Map> results = []
        [Config.WORKLOGS_DB_NAME, Config.CONVERSATION_DB_NAME, Config.ARTIFACTS_DB_NAME].each { String name ->
            File database = new File(dataDir, name)
            if (!database.exists()) return
            MemoryEngine engine = new MemoryEngine(database.absolutePath)
            try {
                engine.search("file_path:${facet} AND (${terms})", limit).each { result ->
                    Map document = engine.getDocument(result.id as int)
                    if (document) {
                        results << document + [
                            origin_db: name,
                            rank: result.rank,
                            pool: 'semantic',
                            authority: authority(document.content?.toString()),
                            state: state(document.content?.toString())
                        ]
                    }
                }
            } finally {
                engine.close()
            }
        }
        return results.sort { a, b -> (a.rank as double) <=> (b.rank as double) }.take(limit)
    }

    static List<Map> mergeCandidates(List<List<Map>> pools, int limit) {
        List<Map> merged = []
        Set<String> identities = [] as Set<String>
        pools.each { List<Map> pool ->
            pool.each { Map item ->
                String identity = "${item.origin_db}:${item.source_archive}:${item.file_path}"
                if (!identities.contains(identity) && merged.size() < limit) {
                    Map candidate = new LinkedHashMap(item)
                    candidate.snippet = bodyPreview(candidate.content?.toString() ?: '', '')
                    merged << candidate
                    identities << identity
                }
            }
        }
        return merged
    }

    static Map assembleContext(List<Map> candidates, int budgetBytes = Config.TICKET_CONTEXT_BUDGET_BYTES) {
        int used = 0
        List<Map> selected = []
        candidates.each { Map candidate ->
            String content = candidate.content?.toString() ?: ''
            int bytes = content.getBytes(StandardCharsets.UTF_8).length
            if (bytes > 0 && used + bytes <= budgetBytes) {
                selected << candidate
                used += bytes
            }
        }
        return [items: selected, content_bytes: used, budget_bytes: budgetBytes]
    }

    static String bodyPreview(String content, String terms = '', int size = 320) {
        String body = (content ?: '').replaceFirst(/(?s)^---\s*\n.*?\n---\s*\n/, '').trim()
        if (!body) return ''
        int start = 0
        List<String> tokens = (terms ?: '').toLowerCase().tokenize(' ').findAll { it.size() > 2 }
        if (tokens) {
            List<Integer> positions = tokens.collect { body.toLowerCase().indexOf(it) }.findAll { it >= 0 }
            if (positions) start = Math.max(0, positions.min() - (size.intdiv(3)))
        }
        String preview = body.substring(start, Math.min(body.length(), start + size)).replaceAll(/\s+/, ' ').trim()
        return (start > 0 ? '… ' : '') + preview + (start + size < body.length() ? '…' : '')
    }

    static String normalizeTicket(String ticket) {
        return (ticket ?: '').toLowerCase().replaceAll(/[^a-z0-9]/, '')
    }

    private static List<Map> rows(File database, String query, List parameters, String pool) {
        if (!database.exists()) return []
        def driver = Thread.currentThread().contextClassLoader
            .loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
        def connection = driver.connect("jdbc:sqlite:${database.absolutePath}", new Properties())
        Sql sql = new Sql(connection)
        try {
            List<Map> found = []
            sql.eachRow(query, parameters) { row ->
                String content = row.content?.toString() ?: ''
                found << [
                    id: row.id as long,
                    source_archive: row.source_archive.toString(),
                    file_path: row.file_path.toString(),
                    file_name: row.file_name.toString(),
                    extension: row.extension?.toString() ?: '',
                    content: content,
                    origin_db: database.name,
                    rank: null,
                    pool: pool,
                    authority: authority(content),
                    state: state(content)
                ]
            }
            return found
        } finally {
            sql.close()
        }
    }

    private static String authority(String content) {
        def matcher = content =~ /(?m)^authority:\s*"([^"]+)"/
        return matcher.find() ? matcher.group(1) : 'historical'
    }

    private static String state(String content) {
        def matcher = content =~ /(?m)^state:\s*"([^"]+)"/
        return matcher.find() ? matcher.group(1) : 'historical'
    }
}
