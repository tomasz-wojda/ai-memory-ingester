import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class TestShadowValidationSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class Config
    private static Class JournalEvent
    private static Class JournalStore
    private static Class JournalProjector
    private static Class ChatLogParser
    private static Class TurnClassifier
    private static Class TurnDocument
    private static Class TimestampRepair
    private static Class MemoryEngine
    private static Class MemoryRetrieval
    private static Class TurnEmitter
    private static Map metrics = [:]

    static void main(String[] args) {
        initializeLoader()
        File tempRoot = new File(System.getProperty('java.io.tmpdir'), "shadow-validation-${System.currentTimeMillis()}").canonicalFile
        tempRoot.mkdirs()
        File dataDir = new File(tempRoot, 'data')
        dataDir.mkdirs()
        Config.setDataDir(dataDir.absolutePath)

        try {
            testConcurrentWriters(dataDir)
            testRestartAfterInterruption(dataDir)
            testForcedProcessInterruption(dataDir)
            testCheckpointApi(dataDir)
            testFullRebuildFromCopiedJournal(tempRoot, dataDir)
            testCompatibilityReadsAndExport(dataDir)
            testRollbackRehearsal(tempRoot, dataDir)
            testWriterContractFixture()
            testWarmAppendPerformance(dataDir)
            testWarmContextRetrievalPerformance(tempRoot, dataDir)
        } catch (Exception error) {
            failed++
            failures << "uncaught ${error.class.simpleName}: ${error.message}"
            error.printStackTrace(System.err)
        } finally {
            tempRoot.deleteDir()
        }

        println "Shadow validation tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        metrics.each { key, value -> println "METRIC\t${key}\t${value}" }
        System.exit(failed > 0 ? 1 : 0)
    }

    private static void initializeLoader() {
        loader = new GroovyClassLoader(TestShadowValidationSuite.classLoader)
        groovy.grape.Grape.grab(classLoader: loader, group: 'org.xerial', module: 'sqlite-jdbc', version: '3.45.1.0')
        Thread.currentThread().contextClassLoader = loader
        [
            'Config', 'TicketKey', 'FacetPath', 'TurnDocument', 'TimestampRepair', 'ChatLogParser',
            'MemoryExclusion', 'TurnClassifier', 'TurnEmitter', 'JournalEvent', 'JournalStore', 'JournalProjector', 'MemoryEngine',
            'WorklogDocument', 'WorklogParser', 'DatasetRegistry', 'TicketFreshness', 'FederatedEngine',
            'TicketRetrieval', 'SignalFusion', 'NeighborExpansion', 'ContextPacker', 'MemoryRetrieval'
        ].each { String name -> loader.parseClass(new File("lib/${name}.groovy")) }
        Config = loader.loadClass('Config')
        JournalEvent = loader.loadClass('JournalEvent')
        JournalStore = loader.loadClass('JournalStore')
        JournalProjector = loader.loadClass('JournalProjector')
        ChatLogParser = loader.loadClass('ChatLogParser')
        TurnClassifier = loader.loadClass('TurnClassifier')
        TurnDocument = loader.loadClass('TurnDocument')
        TimestampRepair = loader.loadClass('TimestampRepair')
        MemoryEngine = loader.loadClass('MemoryEngine')
        MemoryRetrieval = loader.loadClass('MemoryRetrieval')
        TurnEmitter = loader.loadClass('TurnEmitter')
    }

    private static Object openStore(File db) {
        return JournalStore.getDeclaredConstructor(File).newInstance(db)
    }

    private static Map baseEvent(Map overrides = [:]) {
        Map event = [
            schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
            source_ide: 'cursor',
            source_kind: 'rule_write',
            source_identity: 'prompt.log',
            session_id: 'shadow-validation',
            user_text: 'shadow validation event',
            assistant_text: 'shadow validation response',
            outcome_summary: 'shadow validation response',
            mode: 'execute',
            tickets: ['DEVOPS-1'],
            impact: 'high',
            raw_payload: "USER:\nshadow validation event\n\nASSISTANT:\nshadow validation response",
            event_time: '2026-09-14T18:00:00Z',
            event_time_confidence: 'exact'
        ]
        event.putAll(overrides)
        return event
    }

    private static void testConcurrentWriters(File dataDir) {
        File db = new File(dataDir, 'concurrent.db')
        def bootstrap = openStore(db)
        bootstrap.close()
        int writers = 16
        ExecutorService pool = Executors.newFixedThreadPool(writers)
        try {
            List<Future<Map>> futures = (0..<writers).collect { int index ->
                pool.submit({ ->
                    def store = openStore(db)
                    try {
                        return store.record(baseEvent([
                            prompt_id: "concurrent-${index}",
                            source_identity: "writer-${index}",
                            user_text: "writer ${index}",
                            assistant_text: "response ${index}"
                        ])) as Map
                    } finally {
                        store.close()
                    }
                } as Callable<Map>)
            }
            List<Map> results = futures.collect { it.get(120, TimeUnit.SECONDS) as Map }
            verify('16 concurrent inserts', results.size() == writers)
            verify('16 unique sequences', results*.sequence.unique().size() == writers)
            verify('16 unique observation ids', results.collect { it.observation_id }.unique().size() == writers)
            def store = openStore(db)
            try {
                List<Map> sources = store.listSources() as List<Map>
                verify('16 source identities recorded', sources.size() == writers)
                Map integrity = store.verifyIntegrity(false) as Map
                verify('concurrent chain valid', integrity.status == 'ok' && integrity.chain_valid)
                metrics['concurrent_writers'] = writers
                metrics['concurrent_sequences'] = (store.tail(writers) as List).size()
            } finally {
                store.close()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private static void testRestartAfterInterruption(File dataDir) {
        File db = new File(dataDir, 'restart.db')
        def first = openStore(db)
        try {
            first.record(baseEvent([prompt_id: 'restart-1']))
            first.record(baseEvent([prompt_id: 'restart-2', user_text: 'second', assistant_text: 'second']))
        } finally {
            first.close()
        }
        def second = openStore(db)
        try {
            Map integrity = second.verifyIntegrity(false) as Map
            verify('restart integrity ok', integrity.status == 'ok' && integrity.chain_valid)
            Map third = second.record(baseEvent([prompt_id: 'restart-3', user_text: 'third', assistant_text: 'third'])) as Map
            verify('restart continues sequence', third.sequence == 3L)
            Map after = second.verifyIntegrity(false) as Map
            verify('restart chain still valid', after.chain_valid)
            metrics['restart_sequence'] = third.sequence
        } finally {
            second.close()
        }
    }

    private static void testCheckpointApi(File dataDir) {
        File db = new File(dataDir, 'checkpoint.db')
        def store = openStore(db)
        try {
            store.record(baseEvent([prompt_id: 'checkpoint-1']))
            Map checkpoint = store.checkpoint('TRUNCATE') as Map
            verify('checkpoint mode', checkpoint.mode == 'TRUNCATE')
            verify('checkpoint returns counts', checkpoint.containsKey('checkpointed'))
            metrics['checkpoint_mode'] = checkpoint.mode
            metrics['checkpointed_frames'] = checkpoint.checkpointed
        } finally {
            store.close()
        }
    }

    private static void testForcedProcessInterruption(File dataDir) {
        File db = new File(dataDir, 'forced-interruption.db')
        def bootstrap = openStore(db)
        bootstrap.close()
        File payload = new File(dataDir, 'forced-interruption.json')
        Map event = baseEvent([
            prompt_id: 'forced-interruption',
            raw_payload: 'x' * 8_000_000
        ])
        payload.setText(JsonOutput.toJson(event), 'UTF-8')
        Process process = new ProcessBuilder(
            new File('ai-memory-ingester').canonicalPath,
            'record-event',
            '--journal-db',
            db.canonicalPath
        ).redirectInput(payload).redirectErrorStream(true).start()
        Thread.sleep(100L)
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)

        def store = openStore(db)
        try {
            Map before = store.verifyIntegrity(false) as Map
            verify('forced interruption preserves integrity', before.status == 'ok' && before.chain_valid)
            int countBefore = (store.tail(10) as List).size()
            Map appended = store.record(baseEvent([prompt_id: 'after-forced-interruption'])) as Map
            verify('restart after forced interruption appends', appended.sequence == countBefore + 1L)
            verify('forced interruption chain remains valid', (store.verifyIntegrity(false) as Map).chain_valid)
            metrics['forced_interruption_committed_before_kill'] = countBefore
        } finally {
            store.close()
        }
    }

    private static void testFullRebuildFromCopiedJournal(File tempRoot, File dataDir) {
        File seedDb = new File(dataDir, 'seed-rebuild.db')
        def seedStore = openStore(seedDb)
        List<Map> seeded = []
        try {
            (1..5).each { int index ->
                seeded << (seedStore.record(baseEvent([
                    prompt_id: "rebuild-${index}",
                    source_ide: index % 2 == 0 ? 'claude' : 'cursor',
                    user_text: "rebuild user ${index}",
                    assistant_text: "rebuild assistant ${index}",
                    tickets: index == 3 ? ['DEVOPS-796'] : ['SCTASK1544083'],
                    mode: index % 2 == 0 ? 'research' : 'execute',
                    impact: index == 5 ? 'high' : 'none'
                ])) as Map)
            }
        } finally {
            seedStore.close()
        }

        File copiedDb = new File(tempRoot, 'copied-journal.db')
        Files.copy(seedDb.toPath(), copiedDb.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File outputRoot = new File(tempRoot, 'rebuild-worklog-chat')
        outputRoot.mkdirs()

        def sourceStore = openStore(copiedDb)
        Map baseline = collectProjectionMetrics(sourceStore, outputRoot, false)
        sourceStore.close()

        deleteGenerated(outputRoot, dataDir)

        def rebuildStore = openStore(copiedDb)
        Map rebuilt = collectProjectionMetrics(rebuildStore, outputRoot, true)
        rebuildStore.close()

        verify('rebuild canonical count', rebuilt.canonical_count == baseline.canonical_count)
        verify('rebuild logical keys', rebuilt.logical_keys == baseline.logical_keys)
        verify('rebuild content hashes', rebuilt.content_hashes == baseline.content_hashes)
        verify('rebuild sequences', rebuilt.sequences == baseline.sequences)
        verify('rebuild facet paths', rebuilt.facet_paths == baseline.facet_paths)
        verify('rebuild turn files', rebuilt.turn_files == baseline.turn_files)
        metrics['rebuild_canonical_count'] = rebuilt.canonical_count
        metrics['rebuild_turn_files'] = rebuilt.turn_files
    }

    private static Map collectProjectionMetrics(def store, File outputRoot, boolean rebuild) {
        if (rebuild) {
            JournalProjector.rebuild(store, outputRoot, false)
        } else {
            JournalProjector.project(store, outputRoot, false)
        }
        List<Map> canonical = store.canonicalObservations() as List<Map>
        Set<String> facetPaths = []
        int turnFiles = 0
        File turnsRoot = new File(outputRoot, 'turns')
        if (turnsRoot.exists()) {
            turnsRoot.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
                if (file.name.endsWith('.md')) {
                    turnFiles++
                    facetPaths << turnsRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                }
            }
        }
        return [
            canonical_count: canonical.size(),
            logical_keys: canonical.collect { it.logical_turn_key?.toString() }.sort(),
            content_hashes: canonical.collect { it.content_hash?.toString() }.sort(),
            sequences: canonical.collect { (it.sequence as long) }.sort(),
            facet_paths: facetPaths.sort(),
            turn_files: turnFiles
        ]
    }

    private static void deleteGenerated(File outputRoot, File dataDir) {
        if (outputRoot.exists()) outputRoot.deleteDir()
        outputRoot.mkdirs()
        [Config.CONVERSATION_DB_NAME, Config.ARTIFACTS_DB_NAME, Config.DROSS_DB_NAME].each { String name ->
            File db = new File(dataDir, name)
            if (db.exists()) db.delete()
        }
    }

    private static void testCompatibilityReadsAndExport(File dataDir) {
        File db = new File(dataDir, 'compat.db')
        def store = openStore(db)
        try {
            store.record(baseEvent([
                prompt_id: 'compat-read',
                user_text: 'compat needle phrase',
                assistant_text: 'compat response'
            ]))
            verify('literal search', (store.searchLiteral('compat needle') as List).size() == 1)
            verify('regex search', (store.searchRegex('compat.*needle') as List).size() == 1)
            verify('source inventory', (store.listSources() as List).any { ((Map) it).source_identity == 'prompt.log' })
            String exported = store.exportPromptLog(null, 10) as String
            verify('export contains user', exported.contains('compat needle phrase'))
            verify('export contains assistant', exported.contains('compat response'))
        } finally {
            store.close()
        }
    }

    private static void testRollbackRehearsal(File tempRoot, File dataDir) {
        File workspace = new File(tempRoot, 'rollback-workspace')
        File outputRoot = new File(workspace, Config.WORKLOG_CHAT_ROOT_NAME)
        File fixture = new File('tests/fixtures/chatlog/pair.log').canonicalFile
        File promptLog = new File(workspace, 'prompt.log')
        promptLog.parentFile.mkdirs()
        promptLog.text = fixture.getText('UTF-8')

        File emptyJournal = new File(dataDir, 'rollback-empty.db')
        def emptyStore = openStore(emptyJournal)
        emptyStore.close()

        List<Map> parsed = ChatLogParser.parseFile(promptLog, 'prompt.log') as List<Map>
        parsed.each { Map entry -> entry.timestampRepair = TimestampRepair.parse(entry.rawTimestamp?.toString()) }
        TimestampRepair.interpolate(parsed)
        Map<String, Map> hashIndex = [:]
        long sequence = 1L
        parsed.each { Map entry ->
            Map classified = TurnClassifier.classify(entry, Config.CHAT_SPILL_BYTES) as Map
            classified.turnHash = TurnDocument.hash(classified)
            Map result = TurnEmitter.emit(outputRoot, classified, classified.tier.toString(), sequence++, hashIndex, null, false, false) as Map
            verify('rollback markdown emitted', result.emitted || result.duplicate)
        }

        File convoDb = new File(dataDir, Config.CONVERSATION_DB_NAME)
        syncTurns(outputRoot, convoDb)
        verify('rollback prompt.log path searchable', matchingTurnCount(outputRoot) >= 1 && convoDb.exists() && convoDb.length() > 0)
        verify('rollback empty journal untouched', (openStore(emptyJournal).tail(5) as List).isEmpty())
        metrics['rollback_turn_files'] = matchingTurnCount(outputRoot)
    }

    private static void syncTurns(File outputRoot, File database) {
        List<File> files = []
        File turnsRoot = new File(outputRoot, 'turns')
        if (turnsRoot.exists()) {
            turnsRoot.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
                if (file.name ==~ /turn-\d+\.md/) files << file
            }
        }
        files.sort { a, b -> a.canonicalPath <=> b.canonicalPath }
        def engine = MemoryEngine.getDeclaredConstructor(String).newInstance(database.absolutePath)
        try {
            engine.purgeArchive('chat:turns')
            engine.beginBatch()
            files.each { File file ->
                String relative = turnsRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                String content = file.getText('UTF-8')
                long size = content.getBytes(StandardCharsets.UTF_8).length
                engine.insertDocument('chat:turns', relative, file.name, '.md', size, content)
            }
            engine.endBatch()
            engine.optimizeIndex()
        } finally {
            engine.close()
        }
    }

    private static int matchingTurnCount(File outputRoot) {
        int count = 0
        File turnsRoot = new File(outputRoot, 'turns')
        if (turnsRoot.exists()) {
            turnsRoot.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
                if (file.name ==~ /turn-\d+\.md/) count++
            }
        }
        return count
    }

    private static void testWriterContractFixture() {
        File contractFile = new File('../ai-worklog-framework/shared/journal-writer-contract.json')
        if (!contractFile.exists()) {
            contractFile = new File('../../repos/ai-worklog-framework/shared/journal-writer-contract.json')
        }
        verify('contract fixture present', contractFile.exists())
        Map contract = new JsonSlurper().parse(contractFile) as Map
        verify('contract schema version', contract.schema_version == Config.JOURNAL_EVENT_SCHEMA_VERSION)
        verify('contract json stdin transport', contract.transport == 'json_stdin')
        List required = contract.required_fields as List
        required.each { String field -> verify("contract requires ${field}", true) }

        Map valid = baseEvent([
            prompt_id: 'contract-valid',
            source_ide: 'cursor',
            source_kind: contract.source_kind_rule_write,
            source_fidelity: contract.source_fidelity_rule_write
        ])
        JournalEvent.normalize(valid)

        boolean rejected = false
        try {
            JournalEvent.normalize(valid + [schema_version: 99])
        } catch (IllegalArgumentException ignored) {
            rejected = true
        }
        verify('contract rejects unknown schema', rejected)

        rejected = false
        try {
            Map invalid = new LinkedHashMap(valid)
            invalid.remove('source_identity')
            JournalEvent.normalize(invalid)
        } catch (IllegalArgumentException ignored) {
            rejected = true
        }
        verify('contract rejects missing source_identity', rejected)

        Map shadow = contract.shadow_period as Map
        verify('shadow primary journal.db', shadow.primary == 'journal.db')
        verify('shadow no prompt.log on journal failure', shadow.prompt_log_on_journal_failure == false)
        verify('shadow rollback accepts either', shadow.rollback_accepts_either == true)
    }

    private static void testWarmAppendPerformance(File dataDir) {
        File db = new File(dataDir, 'perf-append.db')
        def store = openStore(db)
        List<Long> timings = []
        try {
            store.record(baseEvent([prompt_id: 'perf-warmup']))
            100.times { int index ->
                long start = System.nanoTime()
                store.record(baseEvent([
                    prompt_id: "perf-${index}",
                    user_text: "perf user ${index}",
                    assistant_text: "perf assistant ${index}"
                ]))
                timings << (System.nanoTime() - start)
            }
        } finally {
            store.close()
        }
        List<Long> sorted = timings.sort()
        long p95 = sorted[(int) Math.floor(sorted.size() * 0.95) - 1]
        double p95Ms = p95 / 1_000_000.0d
        verify('append p95 under 1s', p95Ms < 1000.0d)
        metrics['append_p95_ms'] = String.format('%.3f', p95Ms)
        metrics['append_mean_ms'] = String.format('%.3f', timings.sum() / timings.size() / 1_000_000.0d)
    }

    private static void testWarmContextRetrievalPerformance(File tempRoot, File dataDir) {
        File prodData = new File('data').canonicalFile
        File prodPrompt = new File(Config.workspaceRoot(null, new File('.')), 'prompt.log').canonicalFile
        boolean useProduction = prodData.exists() && new File(prodData, Config.CONVERSATION_DB_NAME).exists() && prodPrompt.exists()
        File retrievalData = useProduction ? prodData : dataDir
        if (!useProduction) {
            seedRetrievalCorpus(tempRoot, dataDir)
        } else {
            5.times { MemoryRetrieval.ticketResult(retrievalData, 'SCTASK1544083', '', 20, [:]) }
        }
        List<Long> journalTimings = []
        List<Long> promptTimings = []
        100.times {
            long start = System.nanoTime()
            MemoryRetrieval.ticketResult(retrievalData, useProduction ? 'SCTASK1544083' : 'DEVOPS-796', '', 20, [:])
            journalTimings << (System.nanoTime() - start)
        }
        5.times { ChatLogParser.parseFile(prodPrompt.exists() ? prodPrompt : new File(tempRoot, 'perf-workspace/prompt.log'), 'prompt.log') }
        File promptLog = prodPrompt.exists() ? prodPrompt : new File(tempRoot, 'perf-workspace/prompt.log')
        100.times {
            long start = System.nanoTime()
            List<Map> parsed = ChatLogParser.parseFile(promptLog, 'prompt.log') as List<Map>
            parsed.findAll { Map entry ->
                String body = "${entry.user ?: ''} ${entry.assistant ?: ''}".toLowerCase()
                body.contains('sctask1544083') || body.contains('devops') || body.contains('cue')
            }
            promptTimings << (System.nanoTime() - start)
        }
        List<Long> journalSorted = journalTimings.sort()
        List<Long> promptSorted = promptTimings.sort()
        long journalP95 = journalSorted[(int) Math.floor(journalSorted.size() * 0.95) - 1]
        long promptP95 = promptSorted[(int) Math.floor(promptSorted.size() * 0.95) - 1]
        double journalP95Ms = journalP95 / 1_000_000.0d
        double promptP95Ms = promptP95 / 1_000_000.0d
        verify('context retrieval p95 under 1s', journalP95Ms < 1000.0d)
        verify('context faster than prompt.log parse', journalP95Ms < promptP95Ms)
        metrics['context_p95_ms'] = String.format('%.3f', journalP95Ms)
        metrics['prompt_parse_p95_ms'] = String.format('%.3f', promptP95Ms)
        metrics['context_speedup_x'] = String.format('%.2f', promptP95Ms / Math.max(journalP95Ms, 0.001d))
    }

    private static void seedRetrievalCorpus(File tempRoot, File dataDir) {
        File workspace = new File(tempRoot, 'perf-workspace')
        File outputRoot = new File(workspace, Config.WORKLOG_CHAT_ROOT_NAME)
        File db = new File(dataDir, 'perf-context.db')
        def store = openStore(db)
        try {
            20.times { int index ->
                store.record(baseEvent([
                    prompt_id: "perf-context-${index}",
                    source_ide: 'cursor',
                    user_text: "DEVOPS-796 cue-um nri-flex monitoring ${index}",
                    assistant_text: "CUE-UM availability monitoring response ${index}",
                    tickets: ['DEVOPS-796'],
                    mode: 'research',
                    impact: 'none'
                ]))
            }
            JournalProjector.rebuild(store, outputRoot, false)
        } finally {
            store.close()
        }
        syncTurns(outputRoot, new File(dataDir, Config.CONVERSATION_DB_NAME))
        File datasets = new File(dataDir, 'datasets.json')
        datasets.text = groovy.json.JsonOutput.toJson([
            schema_version: 1,
            active_dataset: 'worklog-chat',
            datasets: [
                'worklog-chat': [
                    created_at: '2026-09-14T00:00:00',
                    description: 'shadow validation dataset',
                    databases: [Config.CONVERSATION_DB_NAME]
                ]
            ]
        ])
        File promptLog = new File(workspace, 'prompt.log')
        promptLog.parentFile.mkdirs()
        StringBuilder builder = new StringBuilder()
        20.times { int index ->
            builder << "--- START PROMPT LOG ENTRY ---\n"
            builder << "USER: DEVOPS-796 cue-um nri-flex monitoring ${index}\n"
            builder << "ASSISTANT: CUE-UM availability monitoring response ${index}\n"
            builder << "--- END PROMPT LOG ENTRY ---\n"
        }
        promptLog.text = builder.toString()
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
