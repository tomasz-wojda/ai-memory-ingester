@Grab('org.xerial:sqlite-jdbc:3.45.1.0')
@Grab('org.slf4j:slf4j-nop:2.0.12')

class RetrievalSuite {
    private static int passed = 0
    private static int failed = 0
    private static GroovyClassLoader loader

    static void main(String[] args) {
        initialize()
        File root = new File(System.getProperty('java.io.tmpdir'), 'ai-memory-ingester-retrieval-suite')
        root.deleteDir()
        root.mkdirs()
        File data = new File(root, 'data')
        data.mkdirs()
        Class configClass = loader.loadClass('Config')
        configClass.setDataDir(data.absolutePath)

        Map fixtures = buildFixtures(root, data)
        Class memoryRetrieval = loader.loadClass('MemoryRetrieval')
        Class signalFusion = loader.loadClass('SignalFusion')
        Class neighborExpansion = loader.loadClass('NeighborExpansion')
        Class contextPacker = loader.loadClass('ContextPacker')
        Class ticketFreshness = loader.loadClass('TicketFreshness')
        Class memoryEngine = loader.loadClass('MemoryEngine')

        def searchTopic = { String query, int limit = 20 ->
            memoryRetrieval.searchTopic(data, 'worklog-chat', query, limit, [:]) as Map
        }

        Map pathRanking = searchTopic('mongodb connection pool exhaustion', 5)
        List<Map> ranked = (pathRanking.results as List<Map>) ?: []
        int relevantIndex = ranked.findIndexOf { it.file_name == 'turn-000002.md' }
        int pathOnlyIndex = ranked.findIndexOf { it.file_name == 'turn-000001.md' }
        verify('body relevance outranks path-only short doc', relevantIndex >= 0 && (pathOnlyIndex < 0 || relevantIndex < pathOnlyIndex))

        Map ticketRanking = searchTopic('devops796 mongodb', 5)
        verify('exact ticket precedence surfaces ticket-scoped results first', (ticketRanking.results as List<Map>).take(3).every { it.file_path?.contains('devops796') || it.file_name == 'devops796.md' })

        Map staleTicket = memoryRetrieval.ticketResult(data, 'TEST-1234', '', 20, [:]) as Map
        Map stateCandidate = (staleTicket.results as List<Map>).find { it.pool == 'state' }
        verify('stale worklog labeled', stateCandidate?.freshness == 'stale')
        verify('newer evidence precedes authoritative when stale', (staleTicket.results as List<Map>).findIndexOf { it.pool == 'newer-evidence' } <
            (staleTicket.results as List<Map>).findIndexOf { it.pool == 'authoritative' })

        Map freshness = ticketFreshness.classify('TEST-1234', fixtures.chunks, new File(root, 'worklog-chat')) as Map
        verify('low impact newer turn can stale capsule', freshness.freshness == 'stale')
        verify('low impact newer turn appears in newer evidence', (freshness.newer_evidence as List).any { it.file_path?.contains('turn-000011') })

        List<Map> fused = signalFusion.fuse([
            bm25: [[origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'devops796/d20260101/research/impact-high/turn-000010.md', file_name: 'turn-000010.md', sequence: 10L]],
            recency: [[origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'devops796/d20260101/research/impact-high/turn-000011.md', file_name: 'turn-000011.md', sequence: 11L]]
        ], 2) as List<Map>
        List<Map> fusedRepeat = signalFusion.fuse([
            bm25: [[origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'devops796/d20260101/research/impact-high/turn-000010.md', file_name: 'turn-000010.md', sequence: 10L]],
            recency: [[origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'devops796/d20260101/research/impact-high/turn-000011.md', file_name: 'turn-000011.md', sequence: 11L]]
        ], 2) as List<Map>
        verify('deterministic fusion', fused*.stable_id == fusedRepeat*.stable_id)

        def engine = memoryEngine.getDeclaredConstructor(String).newInstance(new File(data, 'convo.db').absolutePath)
        List<Map> neighborRows = []
        try {
            neighborRows = engine.neighborTurnDocuments(
                'devops796/d20260101/research/impact-high/turn-000010.md',
                'turn-000010.md',
                1
            ) as List<Map>
        } finally {
            engine.close()
        }
        verify('neighbor expansion query returns adjacent turn', neighborRows.any { it.file_name == 'turn-000009.md' } && neighborRows.any { it.file_name == 'turn-000011.md' })
        List<Map> neighbors = neighborExpansion.expand([
            [origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'devops796/d20260101/research/impact-high/turn-000010.md', file_name: 'turn-000010.md', sequence: 10L, content: fixtures.turns['turn-000010.md']]
        ], data, false, 1) as List<Map>
        verify('neighbor expansion adds adjacent turn', neighbors.any { it.file_name == 'turn-000009.md' } && neighbors.any { it.file_name == 'turn-000011.md' })

        Map packed = contextPacker.pack([
            [pool: 'state', origin_db: 'worklogs.db', source_archive: 'worklog:state', file_path: 'test1234.md', file_name: 'test1234.md', content: fixtures.capsule]
        ], 22000, true) as Map
        verify('complete row packing keeps full content', (packed.items as List<Map>)[0]?.content?.contains('Deploy production'))
        verify('packing never snippet-only', !(packed.items as List<Map>)[0]?.content?.startsWith('…'))

        def legacyEngine = memoryEngine.getDeclaredConstructor(String).newInstance(new File(data, 'convo.db').absolutePath)
        try {
            List<Map> legacy = legacyEngine.search('mongodb', 3) as List<Map>
            List<Map> body = legacyEngine.searchBody('mongodb', 3) as List<Map>
            verify('direct compatibility bypass keeps legacy search', legacy && body)
        } finally {
            legacyEngine.close()
        }

        verify('journal.db excluded from searchable databases', !configClass.isSearchableDatabase('journal.db'))
        File journal = new File(data, 'journal.db')
        journal.write('placeholder')
        verify('journal.db excluded from database listing', !configClass.listDatabases().any { it.name == 'journal.db' })

        Map multiTicket = searchTopic('SCTASK1685388 OR SCTASK1685377', 5)
        verify('multi-ticket OR query preserves full boolean expression', multiTicket.parsed?.terms == 'SCTASK1685388 OR SCTASK1685377')
        verify('multi-ticket OR query does not peel a single ticket facet', !multiTicket.parsed?.ticket)

        println "Retrieval tests: ${passed} passed, ${failed} failed"
        if (failed > 0) System.exit(1)
    }

    private static Map buildFixtures(File root, File data) {
        File chatRoot = new File(root, 'worklog-chat')
        File turnsRoot = new File(chatRoot, 'turns/devops796/d20260101/research/impact-high')
        turnsRoot.mkdirs()
        File testTurns = new File(chatRoot, 'turns/test1234/d20260913/plan/impact-none')
        testTurns.mkdirs()
        File ticketState = new File(chatRoot, 'ticket-state')
        ticketState.mkdirs()

        Map turns = [:]
        turns['turn-000001.md'] = turnMarkdown('DEVOPS-796', 'none', '2026-01-01T10:00:00Z', 'path token devops796', 'short path-only body')
        turns['turn-000002.md'] = turnMarkdown('DEVOPS-796', 'high', '2026-01-02T10:00:00Z', 'mongodb connection pool exhaustion in production', 'Detailed mongodb connection pool exhaustion remediation with runtime metrics.')
        turns['turn-000009.md'] = turnMarkdown('DEVOPS-796', 'high', '2026-01-09T10:00:00Z', 'neighbor before', 'context before target turn')
        turns['turn-000010.md'] = turnMarkdown('DEVOPS-796', 'high', '2026-01-10T10:00:00Z', 'neighbor center', 'center turn for neighbor expansion')
        turns['turn-000011.md'] = turnMarkdown('DEVOPS-796', 'none', '2026-01-11T10:00:00Z', 'follow-up devops796 note', 'Adjacent neighbor turn for sequence expansion.')
        turns.each { String name, String content ->
            new File(turnsRoot, name).setText(content, 'UTF-8')
        }
        new File(testTurns, 'turn-000011.md').setText(
            turnMarkdown('TEST-1234', 'none', '2026-09-13T10:00:00Z', 'verify TEST-1234 deployment rollout', 'Verified deployment rollout completed with runtime healthy signal.'),
            'UTF-8'
        )

        String capsule = '''---
ticket: "TEST-1234"
freshness: "stale"
authoritative_through: "2026-09-10T12:00:00Z"
newer_evidence_through: "2026-09-13T10:00:00Z"
---
# TEST-1234
Deploy production after approval.
'''
        new File(ticketState, 'test1234.md').setText(capsule, 'UTF-8')

        List<Map> chunks = [[
            category: 'status',
            latest_source_time: '2026-09-10T12:00:00Z',
            source_mtime: 0L
        ]]

        Class memoryEngine = loader.loadClass('MemoryEngine')
        def convo = memoryEngine.getDeclaredConstructor(String).newInstance(new File(data, 'convo.db').absolutePath)
        def worklogs = memoryEngine.getDeclaredConstructor(String).newInstance(new File(data, 'worklogs.db').absolutePath)
        try {
            convo.beginBatch()
            turns.each { String name, String content ->
                convo.insertDocument('chat:turns', "devops796/d20260101/research/impact-high/${name}", name, '.md', content.length(), content)
            }
            convo.endBatch()
            String devopsCapsule = '''---
ticket: "DEVOPS-796"
freshness: "fresh"
authority: "state"
state: "current"
---
# DEVOPS-796
Current mongodb remediation state.
'''
            worklogs.insertDocument('worklog:state', 'devops796.md', 'devops796.md', '.md', devopsCapsule.length(), devopsCapsule)
            worklogs.insertDocument('worklog:state', 'test1234.md', 'test1234.md', '.md', capsule.length(), capsule)
            worklogs.insertDocument('worklog:chunks', 'test1234/status/0001-status.md', '0001-status.md', '.md', 64, 'status: "current"\nauthority: "authoritative"\nstate: "current"\nfreshness: "stale"\n# Status\nPending approval')
        } finally {
            convo.close()
            worklogs.close()
        }

        File datasets = new File(data, 'datasets.json')
        datasets.setText(groovy.json.JsonOutput.toJson([
            schema_version: 1,
            active_dataset: 'worklog-chat',
            datasets: [
                'worklog-chat': [
                    created_at: '2026-09-14T00:00:00',
                    description: 'retrieval test dataset',
                    databases: ['convo.db', 'worklogs.db']
                ]
            ]
        ]), 'UTF-8')

        return [turns: turns, capsule: capsule, chunks: chunks]
    }

    private static String turnMarkdown(String ticket, String impact, String timestamp, String user, String assistant) {
        return """---
timestamp: "${timestamp}"
timestamp_confidence: "exact"
mode: "research"
tickets: ["${ticket}"]
impact: "${impact}"
---
## User

${user}

## Assistant

${assistant}
"""
    }

    private static void initialize() {
        loader = new GroovyClassLoader(RetrievalSuite.class.classLoader)
        ['Config', 'TicketKey', 'MemoryEngine', 'DatasetRegistry', 'TicketFreshness', 'TicketRetrieval',
         'SignalFusion', 'NeighborExpansion', 'ContextPacker', 'MemoryRetrieval', 'FederatedEngine'].each { String name ->
            loader.parseClass(new File("lib/${name}.groovy"))
        }
        Thread.currentThread().contextClassLoader = loader
    }

    private static void verify(String name, boolean condition) {
        if (condition) {
            passed++
            println "  [PASS] ${name}"
        } else {
            failed++
            println "  [FAIL] ${name}"
        }
    }
}
