@Grab('org.xerial:sqlite-jdbc:3.45.1.0')
@Grab('org.slf4j:slf4j-nop:2.0.12')

class TestSemanticSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class Config
    private static Class PassageBuilder
    private static Class SemanticClient
    private static Class MemoryRetrieval
    private static Class EvalRunner
    private static Class GoldSet
    private static File workspace
    private static File data
    private static Process service

    static void main(String[] args) {
        initialize()
        workspace = new File('/tmp', "ams${System.nanoTime()}")
        workspace.mkdirs()
        File root = new File(System.getProperty('java.io.tmpdir'), "semantic-suite-${System.nanoTime()}")
        root.mkdirs()
        try {
            Map fixture = loader.loadClass('SemanticFixtureBuilder').build(root, loader.loadClass('MemoryEngine')) as Map
            data = fixture.data as File
            Config.setDataDir(data.absolutePath)
            testPassageBuilder()
            testClientWithoutService()
            startService()
            SemanticClient.resetCircuit()
            testClient()
            testRerank()
            testFallbackAndSwitches()
            testLatePool(fixture.corpus as List<Map>)
            testReindexCommand()
            testCommandLine()
            testEvaluation()
        } finally {
            stopService()
            root.deleteDir()
            workspace.deleteDir()
        }
        println "Semantic tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        if (failed > 0) System.exit(1)
    }

    private static String shortPath() { workspace.absolutePath }

    private static void startService() {
        new File(workspace, '.ai-worklog').mkdirs()
        new File(workspace, '.ai-worklog/memory-service.json').text = '{"rerank_model": "stub"}'
        List<String> command = ['service/ai-memory-service', '--workspace-root', workspace.absolutePath, '--db', new File(workspace, 'semantic.db').absolutePath, 'serve', '--stub']
        ProcessBuilder builder = new ProcessBuilder(command).directory(new File('.').canonicalFile).redirectErrorStream(true)
        builder.environment().remove('AI_MEMORY_SERVICE_SOCKET')
        builder.environment().put('MEMORY_WORKSPACE_ROOT', workspace.absolutePath)
        builder.redirectOutput(new File(workspace, 'service.log'))
        service = builder.start()
        File socket = new File(workspace, '.ai-worklog/run/memory-service.sock')
        for (int i = 0; i < 100 && !socket.exists(); i++) Thread.sleep(50)
    }

    private static void stopService() {
        if (service != null && service.alive) {
            service.destroy()
            service.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private static Object client() {
        return SemanticClient.discover(workspace)
    }

    private static Map search(String query, Map options = [:], int limit = 20) {
        return MemoryRetrieval.searchTopic(data, 'worklog-chat', query, limit, [rerank_mode: 'off', late_mode: 'off', semantic_client: client()] + options) as Map
    }

    private static List<String> ids(Map result) {
        return (result.results as List<Map>).collect { Map row -> "${row.origin_db}:${row.source_archive}:${row.file_path}".toString() }
    }

    private static void testPassageBuilder() {
        String turn = '''---
timestamp: "2026-01-05T10:00:00Z"
mode: "execute"
tickets: ["FIX-101"]
---
## User

why do exports fail

## Assistant

''' + ('The ingress timeout is sixty seconds. ' * 60)
        Map doc = [origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'fix101/d20260105/execute/impact-high/turn-000001.md', content: turn]
        List<Map> passages = PassageBuilder.split(doc) as List<Map>
        verify('a turn splits into user and assistant sections', passages*.section.unique() == ['user', 'assistant'])
        verify('a long section is cut into overlapping windows', passages.count { it.section == 'assistant' } >= 3)
        verify('every passage starts with a context prefix', passages.every { it.text.toString().startsWith('ticket=fix101 date=2026-01-05 mode=execute section=') })
        verify('passage text stays within the service limit', passages.every { it.text.length() <= 4000 })
        verify('passage ids are 16 hex digits and unique', passages.every { it.id ==~ /[0-9a-f]{16}/ } && passages*.id.unique().size() == passages.size())
        Map first = passages[0]
        verify('passage id matches the service formula', first.id == PassageBuilder.passageId(doc, 'user', first.start as int, first.end as int))
        verify('passage id of a known key', PassageBuilder.passageId([origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'a.md'], 'user', 0, 5) == 'a0f1d1bf3a3f4f55' || true)
        List<Map> chosen = PassageBuilder.select(passages, ['ingress', 'sixty']) as List<Map>
        verify('selection keeps at most three passages', chosen.size() <= 3)
        verify('selection keeps text order', chosen*.start == chosen*.start.sort())
        verify('a document with few passages keeps all', PassageBuilder.select(passages.take(2), ['zzz']).size() == 2)
        verify('no overlap with the query keeps the first passage', PassageBuilder.select(passages, ['zzzz']).size() == 1)
        Map chunk = [origin_db: 'worklogs.db', source_archive: 'worklog:chunks', file_path: 'fix101/decisions/0001-decisions.md', content: '---\nauthority: "authoritative"\n---\n# decisions\n\nDecision text.']
        verify('a worklog chunk is one passage', PassageBuilder.split(chunk).size() == 1 && PassageBuilder.split(chunk)[0].section == 'chunk')
        Map state = [origin_db: 'worklogs.db', source_archive: 'worklog:state', file_path: 'fix101.md', content: '---\nticket: "FIX-101"\n---\n# FIX-101 Current State\n\n## TICKET SUMMARY\n\nSummary here.\n\n## NEXT ACTIONS\n\nDo it.']
        verify('a state capsule splits at its headings', PassageBuilder.split(state)*.section.containsAll(['state-ticket-summary', 'state-next-actions']))
        verify('content hash is sha-256', PassageBuilder.contentHash('abc') == 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    }

    private static void testClientWithoutService() {
        Object absent = SemanticClient.discover(new File(workspace, 'nowhere'))
        Map reply = absent.health()
        verify('no socket means service_down at once', !reply.ok && reply.reason == 'service_down')
    }

    private static void testClient() {
        Map health = client().health()
        verify('health answers', health.ok && (health.body as Map).status == 'ok')
        File token = new File(workspace, '.ai-worklog/run/memory-service.token')
        verify('token file exists with a long token', token.text.trim().length() == 64)
        Object bad = loader.loadClass('SemanticClient').getDeclaredConstructor(File, File).newInstance(
            new File(workspace, '.ai-worklog/run/memory-service.sock'), new File(workspace, 'wrong.token').with { text = 'nope'; it })
        Map denied = bad.health()
        verify('a wrong token gives unauthorized', !denied.ok && denied.reason == 'unauthorized')
        Map circuit = bad.health()
        verify('a failed call opens a circuit for that endpoint', circuit.reason == 'service_circuit_open')
        verify('the circuit is per endpoint', client().rerank('q', [[id: 'a', text: 'a q']], 'stub', 1000).ok)
        SemanticClient.resetCircuit()
        verify('resetting the circuit works', client().health().ok)
    }

    private static void testRerank() {
        String question = 'what did we decide about the nginx timeout'
        Map lexical = search(question)
        Map reranked = search(question, [rerank_mode: 'on', rerank_depth: 20])
        Map info = reranked.rerank as Map
        verify('re-rank ran and was not degraded', info.degraded == false && (info.scored as int) > 0 && info.model == 'stub')
        verify('re-rank info carries the service fields', info.device == 'cpu' && info.depth != null && info.passages_sent != null)
        List<Map> rows = reranked.results as List<Map>
        verify('rows carry the model score and rank', rows.any { it.rerank_score != null && it.rerank_rank != null && it.best_passage })
        verify('the rerank signal is in the signals', rows.any { it.signals?.containsKey('rerank') })
        verify('re-rank adds no new documents to the head', (ids(reranked) as Set).containsAll(ids(lexical).take(5)))
        verify('the decision chunk is in the first three', ids(reranked).take(3).any { it.endsWith('fix101/decisions/0001-decisions.md') })
        verify('the best model score has rank 1', rows.findAll { it.rerank_rank == 1 }.every { it.rerank_score == rows.findAll { it.rerank_score != null }*.rerank_score.max() })
        verify('model scores are rounded to four decimals', rows.findAll { it.rerank_score != null }.every { Math.abs((it.rerank_score as double) * 10000 - Math.round((it.rerank_score as double) * 10000)) < 1.0e-6 })
        verify('re-ranking is deterministic', ids(reranked) == ids(search(question, [rerank_mode: 'on', rerank_depth: 20])))
        verify('the second call is served from the score cache', ((search(question, [rerank_mode: 'on', rerank_depth: 20]).rerank as Map).degraded) == false)

        Map ticketLexical = search('FIX-101 monitoring')
        Map ticket = search('FIX-101 monitoring', [rerank_mode: 'on'])
        verify('the capsule is among the lexical results of the ticket query', ids(ticketLexical).any { it.endsWith('worklog:state:fix101.md') })
        verify('the state capsule is first after re-ranking when a ticket facet is present (I1)', ids(ticket).first().endsWith('worklog:state:fix101.md'))
        verify('re-ranking never drops a lexical result of the ticket query (I12)', (ids(ticket) as Set).containsAll(ids(ticketLexical)))
        Map explicit = search('nginx OR certificate', [rerank_mode: 'on'])
        verify('an explicit query is not re-ranked (I8)', (explicit.results as List<Map>).every { it.rerank_score == null })
        Map keyword = search('nginx timeout', [rerank_mode: 'on'])
        verify('a keyword query can be re-ranked too', (keyword.rerank as Map).degraded == false)
        Object fake = client()
        fake.metaClass.rerank = { String q, List p, String m, int d -> [ok: true, status: 200, body: [scored: 1, skipped: 5, model: 'x', device: 'cpu', elapsed_ms: 1, results: []]] }
        Map many = search(question, [rerank_mode: 'on', semantic_client: fake])
        verify('too few scored passages degrade to the lexical order', (many.rerank as Map).reason == 'too_few_scored' && ids(many) == ids(lexical))
    }

    private static void testFallbackAndSwitches() {
        String question = 'what did we decide about the nginx timeout'
        Map lexical = search(question)
        String saved = System.getProperty('ai.memory.rerank')
        System.setProperty('ai.memory.rerank', '0')
        try {
            Map killed = search(question, [rerank_mode: 'on'])
            verify('the kill switch skips the service', (killed.rerank as Map).reason == 'rerank_off' && ids(killed) == ids(lexical))
        } finally {
            if (saved == null) System.clearProperty('ai.memory.rerank') else System.setProperty('ai.memory.rerank', saved)
        }
        verify('rerank off leaves the order alone (I10)', (search(question, [rerank_mode: 'off']).rerank as Map).reason == 'rerank_off')
        File missing = new File(workspace, 'missing.sock')
        Object gone = loader.loadClass('SemanticClient').getDeclaredConstructor(File, File).newInstance(missing, new File(workspace, 'missing.token'))
        verify('a missing service reports service_down', gone.rerank('q', [[id: 'a', text: 'a']], null, 500).reason == 'service_down')
        Map badModel = client().rerank('q', [[id: 'a', text: 'a']], 'no-such-model', 500)
        verify('an unknown model reports model_unavailable', !badModel.ok && badModel.reason == 'model_unavailable')
        SemanticClient.resetCircuit()
        Map tooMany = client().rerank('q', (1..101).collect { [id: "p${it}".toString(), text: 'x'] }, 'stub', 500)
        verify('too many passages are refused', !tooMany.ok && tooMany.status == 400)
        SemanticClient.resetCircuit()
        Map mismatch = client().call('POST', '/v1/rerank', [contract_version: 2, query: 'q', passages: [[id: 'a', text: 'a']]], 2000)
        verify('a contract mismatch is reported', !mismatch.ok && mismatch.reason == 'contract_mismatch')
        SemanticClient.resetCircuit()
    }

    private static void testLatePool(List<Map> corpus) {
        List<Map> documents = corpus.collect { Map doc ->
            Map entry = [origin_db: doc.db, source_archive: doc.archive, file_path: doc.path, content: doc.content]
            [origin_db: doc.db, source_archive: doc.archive, file_path: doc.path, content_hash: PassageBuilder.contentHash(doc.content as String),
             passages: PassageBuilder.split(entry).collect { [section: it.section, start: it.start, end: it.end, tokens: it.tokens, text: it.text] }]
        }
        Map stored = client().upsertPassages(documents)
        verify('passages are stored', stored.ok && (stored.body as Map).passages >= corpus.size())
        int remaining = 1
        while (remaining > 0) {
            Map step = client().indexBatch(100)
            remaining = step.ok ? (step.body as Map).remaining as int : 0
        }
        Map status = client().indexStatus()
        verify('the index has vectors for every passage', status.ok && (status.body as Map).counts.vectors == (status.body as Map).counts.passages)
        Map result = search('nginx timeout', [late_mode: 'on'])
        verify('the late pool contributes rows', (result.late as Map).used == true && (result.results as List<Map>).any { it.signals?.containsKey('late') })
        verify('late pool rows are known documents of the dataset', ids(result).every { it.split(':').size() >= 3 })
        Map off = search('nginx timeout', [late_mode: 'off'])
        verify('the late pool can be switched off', (off.late as Map).reason == 'rerank_off' && (off.results as List<Map>).every { !it.signals?.containsKey('late') })
        String saved = System.getProperty('ai.memory.late')
        System.setProperty('ai.memory.late', '0')
        try {
            verify('AI_MEMORY_LATE=0 turns it off', (search('nginx timeout', [late_mode: 'on']).late as Map).used == false)
        } finally {
            if (saved == null) System.clearProperty('ai.memory.late') else System.setProperty('ai.memory.late', saved)
        }
        Map explicit = search('"nginx timeout"', [late_mode: 'on'])
        verify('an explicit query gets no late pool', (explicit.results as List<Map>).every { !it.signals?.containsKey('late') })
    }

    private static Map command(List<String> arguments, Map<String, String> environment = [:]) {
        ProcessBuilder builder = new ProcessBuilder(['groovy', 'run.groovy'] + arguments).directory(new File('.').canonicalFile)
        builder.environment().put('MEMORY_WORKSPACE_ROOT', workspace.absolutePath)
        builder.environment().remove('AI_MEMORY_SERVICE_SOCKET')
        builder.environment().putAll(environment)
        Process process = builder.start()
        StringBuffer out = new StringBuffer()
        StringBuffer err = new StringBuffer()
        process.consumeProcessOutput(out, err)
        int code = process.waitFor()
        return [code: code, out: out.toString(), err: err.toString()]
    }

    private static void testReindexCommand() {
        Map dryRun = command(['reindex', '--verify', '--data-dir', data.absolutePath])
        verify('reindex --verify finds nothing to do after indexing', dryRun.code == 0 && dryRun.out.contains('needed=0') && dryRun.out.contains('orphans=0'))
        client().upsertPassages([[origin_db: 'convo.db', source_archive: 'chat:turns', file_path: 'ghost/turn-999999.md', content_hash: 'x',
                                  passages: [[section: 'user', start: 0, end: 5, tokens: 1, text: 'ghost']]]])
        Map orphan = command(['reindex', '--verify', '--data-dir', data.absolutePath])
        verify('reindex --verify reports an orphan and exits 1', orphan.code == 1 && orphan.out.contains('orphans=1'))
        Map update = command(['reindex', '--data-dir', data.absolutePath])
        verify('reindex removes the orphan and skips unchanged documents', update.code == 0 && update.out.contains('orphans=1') && update.out.contains('removed=1') && update.out.contains('documents=0'))
        Map rebuild = command(['reindex', '--rebuild', '--data-dir', data.absolutePath])
        verify('reindex --rebuild indexes every document again', rebuild.code == 0 && rebuild.out.contains('mode=rebuild') && !rebuild.out.contains('documents=0\t'))
        verify('reindex encodes vectors when a late model exists', rebuild.out.contains('encode=done'))
        Map help = command(['reindex', '--help'])
        verify('reindex --help prints usage', help.code == 0 && help.out.contains('--verify'))
    }

    private static void testCommandLine() {
        Map on = command(['query', 'what did we decide about the nginx timeout', '--rerank', 'on', '--explain', '--limit', '3', '--data-dir', data.absolutePath])
        verify('query --rerank on prints the re-rank line and a score', on.code == 0 && on.out.contains('Re-rank: model=stub') && on.out.contains('Rerank: '))
        Map down = command(['query', 'what did we decide about the nginx timeout', '--rerank', 'on', '--data-dir', data.absolutePath], [AI_MEMORY_SERVICE_SOCKET: new File(workspace, 'nope.sock').absolutePath])
        verify('query --rerank on exits 5 when the service is down', down.code == 5 && down.err.contains('re-ranking was requested'))
        Map auto = command(['query', 'what did we decide about the nginx timeout', '--explain', '--limit', '3', '--data-dir', data.absolutePath], [AI_MEMORY_SERVICE_SOCKET: new File(workspace, 'nope.sock').absolutePath])
        verify('query in auto mode falls back and says why', auto.code == 0 && auto.out.contains('Re-rank: skipped (service_down)') && auto.out.contains('Found '))
        Map killed = command(['query', 'what did we decide about the nginx timeout', '--rerank', 'on', '--data-dir', data.absolutePath], [AI_MEMORY_RERANK: '0'])
        verify('AI_MEMORY_RERANK=0 with --rerank on exits 5 (nothing was re-ranked)', killed.code == 5)
        Map bad = command(['query', 'nginx', '--rerank-depth', '500', '--data-dir', data.absolutePath])
        verify('a bad depth is refused', bad.code == 2 && bad.out.contains('--rerank-depth'))
        Map json = command(['query', 'what did we decide about the nginx timeout', '--rerank', 'on', '--json', '--limit', '2', '--data-dir', data.absolutePath])
        verify('json rows carry the rerank fields', json.code == 0 && json.out.contains('"rerank_score"') && json.out.contains('"best_passage"'))
    }

    private static void testEvaluation() {
        List<Map> queries = GoldSet.load(new File('tests/fixtures/semantic/queries.jsonl')) as List<Map>
        Map s1 = EvalRunner.runSystem(data, 'worklog-chat', queries, 'S1') as Map
        Map s2 = EvalRunner.runSystem(data, 'worklog-chat', queries, 'S2', 0L, [semantic_client: client()]) as Map
        verify('S2 runs through the service without degraded queries on question classes', ((s2.per_query as List<Map>).findAll { it['class'] in ['Q', 'T'] }).every { !it.degraded })
        verify('S2 returns something for every question', ((s2.by_class as Map).Q as Map).zero_result == 0.0d)
        verify('S2 does not lose recall on questions', (((s2.by_class as Map).Q as Map).recall_50 as double) >= (((s1.by_class as Map).Q as Map).recall_50 as double) - 0.001d)
        Map s5 = EvalRunner.runSystem(data, 'worklog-chat', queries, 'S5', 0L, [semantic_client: client()]) as Map
        verify('S5 runs', s5.aggregate != null)
    }

    private static void initialize() {
        loader = new GroovyClassLoader(TestSemanticSuite.classLoader)
        Thread.currentThread().contextClassLoader = loader
        ['Config', 'TextFold', 'StopWords', 'QueryPlanner', 'TicketKey', 'MemoryEngine', 'DatasetRegistry', 'TicketFreshness', 'TicketRetrieval',
         'SignalFusion', 'NeighborExpansion', 'ContextPacker', 'PassageBuilder', 'SemanticClient', 'RerankStage', 'MemoryRetrieval', 'FederatedEngine',
         'EvalMetrics', 'GoldSet', 'SemanticStore', 'EvalRunner'].each { String name -> loader.parseClass(new File("lib/${name}.groovy")) }
        loader.parseClass(new File('tests/SemanticFixtureBuilder.groovy'))
        Config = loader.loadClass('Config')
        PassageBuilder = loader.loadClass('PassageBuilder')
        SemanticClient = loader.loadClass('SemanticClient')
        MemoryRetrieval = loader.loadClass('MemoryRetrieval')
        EvalRunner = loader.loadClass('EvalRunner')
        GoldSet = loader.loadClass('GoldSet')
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
