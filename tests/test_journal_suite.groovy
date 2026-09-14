import java.nio.charset.StandardCharsets

class TestJournalSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class Config
    private static Class JournalEvent
    private static Class JournalStore
    private static Class ChatLogParser
    private static Class TurnClassifier
    private static Class TurnDocument
    private static Class TimestampRepair

    static void main(String[] args) {
        initializeLoader()
        File tempRoot = new File(System.getProperty('java.io.tmpdir'), "journal-suite-${System.currentTimeMillis()}").canonicalFile
        tempRoot.mkdirs()
        File dataDir = new File(tempRoot, 'data')
        dataDir.mkdirs()
        Config.setDataDir(dataDir.absolutePath)

        try {
            testRecordAndRead()
            testPromptIdempotency(dataDir)
            testRepeatedIdenticalContent(dataDir)
            testImmutabilityTriggers(dataDir)
            testIntegrityChain(dataDir)
            testCanonicalSourceFidelity(dataDir)
            testCanonicalUnkeyedObservation(dataDir)
            testBackfillFromFixture(tempRoot, dataDir)
            testCompatibilityReads(dataDir)
            testRawPayloadRetained(dataDir)
        } finally {
            tempRoot.deleteDir()
        }

        println "Journal tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        if (failed > 0) System.exit(1)
    }

    private static void initializeLoader() {
        loader = new GroovyClassLoader(TestJournalSuite.classLoader)
        groovy.grape.Grape.grab(classLoader: loader, group: 'org.xerial', module: 'sqlite-jdbc', version: '3.45.1.0')
        Thread.currentThread().contextClassLoader = loader
        [
            'Config', 'TicketKey', 'FacetPath', 'TurnDocument', 'TimestampRepair', 'ChatLogParser',
            'TurnClassifier', 'JournalEvent', 'JournalStore'
        ].each { String name -> loader.parseClass(new File("lib/${name}.groovy")) }
        Config = loader.loadClass('Config')
        JournalEvent = loader.loadClass('JournalEvent')
        JournalStore = loader.loadClass('JournalStore')
        ChatLogParser = loader.loadClass('ChatLogParser')
        TurnClassifier = loader.loadClass('TurnClassifier')
        TurnDocument = loader.loadClass('TurnDocument')
        TimestampRepair = loader.loadClass('TimestampRepair')
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
            session_id: 'promptlog:2026-09-14',
            user_text: 'hello journal',
            assistant_text: 'journal ready',
            outcome_summary: 'journal ready',
            mode: 'execute',
            tickets: ['DEVOPS-1'],
            impact: 'high',
            raw_payload: "USER:\nhello journal\n\nASSISTANT:\njournal ready",
            event_time: '2026-09-14T12:00:00Z',
            event_time_confidence: 'exact'
        ]
        event.putAll(overrides)
        return event
    }

    private static void testRecordAndRead() {
        File db = new File(Config.DATA_DIR, 'record-read.db')
        def store = openStore(db)
        try {
            Map result = store.record(baseEvent([prompt_id: 'turn-001'])) as Map
            verify('record inserted', result.idempotency == 'inserted' && result.sequence == 1L)
            Map fetched = store.getObservation(result.observation_id as String) as Map
            verify('get by id', fetched?.sequence == 1L && fetched.user_text == 'hello journal')
            verify('tail length', (store.tail(5) as List).size() == 1)
            verify('sequence range', (store.sequenceRange(1, 1) as List).size() == 1)
        } finally {
            store.close()
        }
    }

    private static void testPromptIdempotency(File dataDir) {
        File db = new File(dataDir, 'idempotency.db')
        def store = openStore(db)
        try {
            Map first = store.record(baseEvent([prompt_id: 'same-prompt'])) as Map
            Map second = store.record(baseEvent([
                prompt_id: 'same-prompt',
                user_text: 'changed text should not create duplicate',
                assistant_text: 'changed'
            ])) as Map
            verify('prompt idempotency', first.observation_id == second.observation_id && second.idempotency == 'existing')
            verify('single sequence allocated', (store.tail(10) as List).size() == 1)
        } finally {
            store.close()
        }
    }

    private static void testRepeatedIdenticalContent(File dataDir) {
        File db = new File(dataDir, 'duplicate-content.db')
        def store = openStore(db)
        try {
            Map first = store.record(baseEvent([
                prompt_id: 'prompt-a',
                source_start_line: 1,
                source_end_line: 2
            ])) as Map
            Map second = store.record(baseEvent([
                prompt_id: 'prompt-b',
                source_start_line: 3,
                source_end_line: 4
            ])) as Map
            verify('identical content different prompts', first.observation_id != second.observation_id)
            verify('identical turn hash allowed', first.sequence != second.sequence)
            Map one = store.getObservation(first.observation_id as String) as Map
            Map two = store.getObservation(second.observation_id as String) as Map
            verify('same turn hash', one.turn_hash == two.turn_hash)
        } finally {
            store.close()
        }
    }

    private static void testImmutabilityTriggers(File dataDir) {
        File db = new File(dataDir, 'immutable.db')
        def store = openStore(db)
        try {
            store.record(baseEvent([prompt_id: 'immutable-1']))
            Map probe = store.appendOnlyProbe(1L) as Map
            verify('update rejected', probe.update_blocked)
            verify('delete rejected', probe.delete_blocked)
        } finally {
            store.close()
        }
    }

    private static void testIntegrityChain(File dataDir) {
        File db = new File(dataDir, 'integrity.db')
        def store = openStore(db)
        try {
            store.record(baseEvent([prompt_id: 'chain-1']))
            store.record(baseEvent([prompt_id: 'chain-2', user_text: 'second', assistant_text: 'second']))
            Map integrity = store.verifyIntegrity() as Map
            verify('integrity ok', integrity.status == 'ok' && integrity.chain_valid)
        } finally {
            store.close()
        }
    }

    private static void testCanonicalSourceFidelity(File dataDir) {
        File db = new File(dataDir, 'canonical.db')
        def store = openStore(db)
        try {
            String logicalKey = 'shared-turn'
            store.record(baseEvent([
                prompt_id: logicalKey,
                source_fidelity: 'parser_backfill',
                source_kind: 'parser_backfill',
                logical_turn_key: logicalKey,
                user_text: 'canonical user',
                assistant_text: 'backfill text'
            ]))
            store.record(baseEvent([
                prompt_id: 'rule-write-copy',
                source_fidelity: 'rule_write',
                source_kind: 'rule_write',
                logical_turn_key: logicalKey,
                user_text: 'canonical user',
                assistant_text: 'rule write text'
            ]))
            List<Map> canonical = store.canonicalObservations() as List<Map>
            verify('single canonical turn', canonical.size() == 1)
            verify('rule write wins', canonical[0].assistant_text == 'rule write text')
        } finally {
            store.close()
        }
    }

    private static void testCanonicalUnkeyedObservation(File dataDir) {
        File db = new File(dataDir, 'canonical-unkeyed.db')
        def store = openStore(db)
        try {
            store.record(baseEvent([
                session_id: null,
                prompt_id: null,
                logical_turn_key: null
            ]))
            List<Map> canonical = store.canonicalObservations() as List<Map>
            verify('unkeyed canonical observation retained', canonical.size() == 1)
        } finally {
            store.close()
        }
    }

    private static void testBackfillFromFixture(File tempRoot, File dataDir) {
        File fixture = new File('tests/fixtures/chatlog/pair.log').canonicalFile
        File workspace = new File(tempRoot, 'workspace')
        File source = new File(workspace, 'prompt.log')
        source.parentFile.mkdirs()
        source.text = fixture.getText('UTF-8')

        File db = new File(dataDir, 'backfill.db')
        def store = openStore(db)
        try {
            List<Map> entries = ChatLogParser.parseFile(source, 'prompt.log') as List<Map>
            entries.each { Map entry ->
                entry.timestampRepair = TimestampRepair.parse(entry.rawTimestamp?.toString())
            }
            TimestampRepair.interpolate(entries)
            Map snapshot = store.recordSourceSnapshot('prompt.log', 'prompt.log', source) as Map
            store.recordUnparsedRanges(snapshot.snapshot_id as String, JournalStore.computeUncoveredRanges(source.bytes, entries) as List)
            long legacySequence = 1L
            entries.each { Map parsed ->
                Map classified = TurnClassifier.classify(parsed, Config.CHAT_SPILL_BYTES) as Map
                classified.turnHash = TurnDocument.hash(classified)
                Map event = JournalEvent.fromParsedEntry(classified, 'prompt.log', legacySequence++) as Map
                store.backfillObservation(event)
            }
            verify('backfill observations', (store.tail(10) as List).size() >= 1)
            Map integrity = store.verifyIntegrity() as Map
            verify('backfill integrity', integrity.status == 'ok')
            verify('snapshot stored', snapshot.full_hash?.toString()?.length() == 64)
        } finally {
            store.close()
        }
    }

    private static void testCompatibilityReads(File dataDir) {
        File db = new File(dataDir, 'reads.db')
        def store = openStore(db)
        try {
            Map recorded = store.record(baseEvent([
                prompt_id: 'read-me',
                user_text: 'needle phrase here',
                assistant_text: 'response body'
            ])) as Map
            verify('literal search', (store.searchLiteral('needle phrase') as List).size() == 1)
            verify('regex search', (store.searchRegex('needle.*phrase') as List).size() == 1)
            store.record(baseEvent([prompt_id: 'read-me-two', user_text: 'needle phrase again']))
            verify('regex limit', (store.searchRegex('needle.*phrase', 1) as List).size() == 1)
            verify('source inventory', (store.listSources() as List).any { ((Map) it).source_identity == 'prompt.log' })
            String exported = store.exportPromptLog(null, 10) as String
            verify('export contains user', exported.contains('needle phrase here'))
            verify('export get', (store.getBySequence(recorded.sequence as long) as Map)?.observation_id == recorded.observation_id)
        } finally {
            store.close()
        }
    }

    private static void testRawPayloadRetained(File dataDir) {
        File db = new File(dataDir, 'raw.db')
        def store = openStore(db)
        try {
            String raw = '--- START PROMPT LOG ENTRY ---\nUSER: preserved raw bytes\nASSISTANT: intact'
            Map recorded = store.record(baseEvent([
                prompt_id: 'raw-payload',
                raw_payload: raw,
                user_text: 'preserved raw bytes',
                assistant_text: 'intact'
            ])) as Map
            Map fetched = store.getObservation(recorded.observation_id as String) as Map
            verify('raw payload retained', fetched.raw_payload == raw)
        } finally {
            store.close()
        }
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
