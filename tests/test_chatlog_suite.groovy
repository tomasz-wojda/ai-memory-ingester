import groovy.json.JsonSlurper
import groovy.sql.Sql
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Verifies prompt-log parsing, materialization, retrieval, and recovery behavior.
 */
class TestChatLogSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class Config
    private static Class ArchiveHandler
    private static Class TimestampRepair
    private static Class ChatLogParser
    private static Class FacetPath
    private static Class TurnClassifier
    private static Class TurnDocument
    private static Class TurnEmitter
    private static Class SweepState
    private static Class ResumeDigest
    private static Class DatasetRegistry

    /**
     * Runs the complete hermetic chat-memory test battery.
     *
     * @param args Unused command arguments
     */
    static void main(String[] args) {
        initializeLoader()
        File fixtureRoot = new File('tests/fixtures/chatlog').canonicalFile
        Class fixtureGenerator = loader.parseClass(new File('tests/TestFixtureGenerator.groovy'))
        Map<String, File> fixtures = fixtureGenerator.generateChatLogFixtures(fixtureRoot, true)
        File temporaryRoot = new File(System.getProperty('java.io.tmpdir'), 'ai-memory-ingester-chatlog-suite').canonicalFile
        temporaryRoot.deleteDir()
        temporaryRoot.mkdirs()
        Map<String, File> sequenceFixtures = fixtureGenerator.generateSequenceOrderFixtures(temporaryRoot, true)

        testParserDialects(fixtures)
        testBoundaryAndRoleMatrix(fixtures)
        testFenceRecovery(fixtures)
        testTimestampRepair()
        testFacetsAndHashing()
        testClassification()
        testEmissionAndSpill(fixtures, temporaryRoot)
        testDiscoveryAndReport(fixtures, temporaryRoot)
        testSweep(fixtures, temporaryRoot)
        testDigest(temporaryRoot)
        testDeterministicDirectoryIngestion(sequenceFixtures, temporaryRoot)
        testRetrievalAndJson(temporaryRoot)
        testLiveCorpusInvariant()

        println "Chatlog tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        temporaryRoot.deleteDir()
        if (failed > 0) System.exit(1)
    }

    private static void initializeLoader() {
        loader = new GroovyClassLoader(TestChatLogSuite.classLoader)
        groovy.grape.Grape.grab(classLoader: loader, group: 'org.xerial', module: 'sqlite-jdbc', version: '3.45.1.0')
        Thread.currentThread().contextClassLoader = loader
        [
            'Config', 'ArchiveHandler', 'ContentExtractor', 'MemoryEngine',
            'TimestampRepair', 'ChatLogParser', 'FacetPath', 'TurnClassifier',
            'TurnDocument', 'TurnEmitter', 'SweepState', 'TicketFreshness', 'ResumeDigest',
            'DatasetRegistry', 'SetRegistry', 'FederatedEngine'
        ].each { String name -> loader.parseClass(new File("lib/${name}.groovy")) }
        Config = loader.loadClass('Config')
        ArchiveHandler = loader.loadClass('ArchiveHandler')
        TimestampRepair = loader.loadClass('TimestampRepair')
        ChatLogParser = loader.loadClass('ChatLogParser')
        FacetPath = loader.loadClass('FacetPath')
        TurnClassifier = loader.loadClass('TurnClassifier')
        TurnDocument = loader.loadClass('TurnDocument')
        TurnEmitter = loader.loadClass('TurnEmitter')
        SweepState = loader.loadClass('SweepState')
        ResumeDigest = loader.loadClass('ResumeDigest')
        DatasetRegistry = loader.loadClass('DatasetRegistry')
    }

    private static void testParserDialects(Map<String, File> fixtures) {
        Map<String, String> expected = [
            'fenced.log': 'Fenced',
            'labelled.log': 'Labelled',
            'block.log': 'Block',
            'ticket.log': 'Ticket',
            'pair.log': 'Pair',
            'artifact.log': 'Artifact'
        ]
        expected.each { String name, String dialect ->
            List<Map> entries = ChatLogParser.parseFile(fixtures[name], name)
            verify("dialect ${dialect}", entries.size() == 1 && entries[0].dialect == dialect)
        }
    }

    private static void testFenceRecovery(Map<String, File> fixtures) {
        List<Map> opener = ChatLogParser.parseFile(fixtures['unmatched-opener.log'], 'unmatched-opener.log')
        List<Map> closer = ChatLogParser.parseFile(fixtures['unmatched-closer.log'], 'unmatched-closer.log')
        verify('unmatched opener keeps following entry', opener.size() == 2)
        verify('unmatched closer preserves entry', closer.size() == 1 && closer[0].user.contains('unmatched closer'))
    }

    private static void testBoundaryAndRoleMatrix(Map<String, File> fixtures) {
        List<Map> matrix = ChatLogParser.parseFile(fixtures['boundary-matrix.log'], 'boundary-matrix.log')
        verify('complete boundary matrix count', matrix.size() == 16)
        verify('symmetric date banners split', matrix.take(3)*.user == [
            'Three-equals banner', 'Five-equals banner', 'Dash banner'
        ])
        verify('fence variants classify', matrix[3..5].every { it.dialect == 'Fenced' })
        verify('bracket variants preserve headers', matrix[6..10].every { it.rawTimestamp })
        verify('bare date headers split', matrix[11..13]*.dialect == ['Block', 'Block', 'Block'])
        verify('prompt input output pair', matrix[14].user == 'Date prompt input' && matrix[14].assistant == 'Date prompt output')
        verify('input output aliases pair', matrix[15].user == 'Input alias' && matrix[15].assistant == 'Output alias response')

        List<Map> aliases = ChatLogParser.parseFile(fixtures['role-aliases.log'], 'role-aliases.log')
        verify('qualified role aliases split', aliases.size() == 4)
        verify('user query assistant response aliases', aliases[0].user == 'Query alias' && aliases[0].assistant == 'Assistant response alias')
        verify('error response summary aliases', aliases[1].user == 'Error alias' && aliases[1].assistant == 'Response summary alias')
        verify('mode-qualified aliases', aliases[2].user == 'Qualified prompt alias' && aliases[2].assistant == 'Qualified assistant alias')

        List<Map> consecutive = ChatLogParser.parseFile(fixtures['mixed-consecutive.log'], 'mixed-consecutive.log')
        verify('consecutive mixed turns do not collapse', consecutive.size() == 3 && consecutive.every { it.user && it.assistant })
        List<Map> dateContent = ChatLogParser.parseFile(fixtures['date-non-boundary.log'], 'date-non-boundary.log')
        verify('date-leading content is not boundary', dateContent.size() == 1 && dateContent[0].assistant.contains('2026-08-22T09:00:00Z service restarted'))
    }

    private static void testTimestampRepair() {
        Map exact = TimestampRepair.parse('2026-08-11T10:00:00Z')
        Map local = TimestampRepair.parse('2026-08-11 10:00:00')
        Map dateOnly = TimestampRepair.parse('2026-08-11')
        Map lost = TimestampRepair.parse('2026-08-11T$(date +%H:%M:%S)Z')
        verify('timestamp exact confidence', exact.confidence == 'exact')
        verify('timestamp local confidence', local.confidence == 'local')
        verify('timestamp date-only confidence', dateOnly.confidence == 'date_only')
        verify('timestamp literal substitution lost', lost.confidence == 'lost')

        List<Map> entries = [
            [rawTimestamp: '2026-08-11T08:00:00Z', timestampRepair: exact],
            [rawTimestamp: '$(date)', timestampRepair: lost],
            [rawTimestamp: '2026-08-11T12:00:00Z', timestampRepair: TimestampRepair.parse('2026-08-11T12:00:00Z')]
        ]
        TimestampRepair.interpolate(entries)
        verify('timestamp neighbour interpolation', entries[1].timestampRepair.timestamp == '2026-08-11T11:00:00Z')
    }

    private static void testFacetsAndHashing() {
        verify('ticket facet atomic', FacetPath.ticketFacet('KD-11') == 'kd11')
        verify('long ticket facet distinct', FacetPath.ticketFacet('KD-6607') == 'kd6607')
        verify('day facet cannot equal ticket', FacetPath.dateFacet(Instant.parse('2026-08-11T00:00:00Z')) != 'kd11')
        Map first = [user: 'USER: Check  CWP-11649', assistant: 'ASSISTANT: Done at 2026-08-11 10:00']
        Map second = [user: 'check cwp-11649', assistant: 'done at 2026-08-11 10:00']
        verify('hash normalization equivalence', TurnDocument.hash(first) == TurnDocument.hash(second))
    }

    private static void testClassification() {
        Config.DROSS_ALLOWLIST.each { String value ->
            Map entry = [user: value, assistant: 'ok', sections: [:], dialect: 'Block', rawBody: value]
            verify("dross allowlist ${value ?: '(empty)'}", TurnClassifier.isDross(entry))
        }
        Map protectedEntry = [
            user: 'ok',
            assistant: 'done',
            sections: ['FILES CHANGED': 'config.yml'],
            dialect: 'Block',
            rawBody: 'ok'
        ]
        verify('changed-files entry is not dross', !TurnClassifier.isDross(protectedEntry))
        verify('high impact', TurnClassifier.impactOf(protectedEntry) == 'high')
        verify('low impact', TurnClassifier.impactOf([sections: ['FINDINGS': 'x']]) == 'low')
        verify('no impact', TurnClassifier.impactOf([sections: [:]]) == 'none')
    }

    private static void testEmissionAndSpill(Map<String, File> fixtures, File temporaryRoot) {
        File output = new File(temporaryRoot, 'worklog-chat')
        File index = new File(output, '.index/turn-hashes.tsv')
        Map<String, Map> hashIndex = TurnEmitter.loadHashIndex(index)
        Map entry = prepare(ChatLogParser.parseFile(fixtures['fenced.log'], 'fenced.log')[0])
        Map first = TurnEmitter.emit(output, entry, 'turns', 1L, hashIndex)
        Map second = TurnEmitter.emit(output, entry, 'turns', 2L, hashIndex)
        TurnEmitter.saveHashIndex(index, hashIndex)
        verify('first emission writes file', first.emitted && new File(output, first.path).exists())
        verify('second emission deduplicates', second.duplicate && countMarkdown(output) == 1)

        Map oversized = prepare(ChatLogParser.parseFile(fixtures['oversized.log'], 'oversized.log')[0])
        oversized = TurnClassifier.classify(oversized, Config.CHAT_SPILL_BYTES)
        verify('oversized turn requests spill', oversized.spillRequired)
        Map artifact = new LinkedHashMap(oversized)
        artifact.turnHash = TurnDocument.hash("artifact ${oversized.rawBody}")
        artifact.assistant = oversized.rawBody
        Map artifactResult = TurnEmitter.emit(output, artifact, 'artifacts', 3L, hashIndex)
        Map turnResult = TurnEmitter.emit(output, oversized, 'turns', 3L, hashIndex, artifactResult.path)
        String turnContent = new File(output, turnResult.path).getText('UTF-8')
        verify('spill pointer resolves', new File(output, artifactResult.path).exists() && turnContent.contains("spill: \"${artifactResult.path}\""))
        verify('spill abstract is non-empty', turnContent.contains('## Assistant') && !turnContent.contains('## Assistant\n\n\n'))
    }

    private static void testSweep(Map<String, File> fixtures, File temporaryRoot) {
        File source = new File(temporaryRoot, 'incremental.log')
        source.bytes = fixtures['incremental.log'].bytes
        File stateFile = new File(temporaryRoot, 'ingest-state.json')
        Map state = SweepState.load(stateFile)
        List<Map> initial = ChatLogParser.parseFile(source, 'incremental.log')
        SweepState.record(state, 'incremental.log', source)
        SweepState.save(stateFile, state)
        source.append('''[2026-08-20T09:00:00Z] USER: Appended incremental turn
[2026-08-20T09:00:00Z] ASSISTANT: Appended incremental response.
''', 'UTF-8')
        Map reloaded = SweepState.load(stateFile)
        long offset = SweepState.offsetFor(reloaded, 'incremental.log')
        List<Map> appended = ChatLogParser.parseFile(source, 'incremental.log', offset)
        verify('incremental sweep reads only appended turn', initial.size() == 1 && appended.size() == 1 && appended[0].user.contains('Appended'))

        File truncated = new File(temporaryRoot, 'truncated.log')
        truncated.bytes = fixtures['truncated.log'].bytes
        Map truncationState = [schema_version: 1, sources: [:]]
        SweepState.record(truncationState, 'truncated.log', truncated)
        truncated.setText('[2026-08-21T09:00:00Z] USER: Replaced\n[2026-08-21T09:00:00Z] ASSISTANT: Replaced.\n', 'UTF-8')
        verify('truncation requires full rescan', SweepState.needsFullRescan(truncated, truncationState.sources['truncated.log'] as Map))
    }

    private static void testDiscoveryAndReport(Map<String, File> fixtures, File temporaryRoot) {
        File workspace = new File(temporaryRoot, 'report-workspace')
        workspace.mkdirs()
        Map<String, String> sourcePatterns = [
            'prompt.log': 'source-prompt.fixture',
            'prompt.log.old': 'source-prompt-old.fixture',
            'prompt.log.new': 'source-prompt-new.fixture',
            'PR.log': 'source-pr.fixture',
            'exporter-prompt.log': 'source-exporter.fixture'
        ]
        sourcePatterns.each { String name, String fixtureName ->
            new File(workspace, name).bytes = fixtures[fixtureName].bytes
        }
        new File(workspace, 'copy-prompt.log').bytes = fixtures['source-prompt.fixture'].bytes
        new File(workspace, 'semantic-copy-prompt.log').setText(
            fixtures['source-prompt.fixture'].getText('UTF-8').replace('2026-08-25 08:00', '2026-08-26 09:00'),
            'UTF-8'
        )
        new File(workspace, 'spill-prompt.log').bytes = fixtures['oversized.log'].bytes

        String output = executeChatlogScript(['--all', '--dry-run', '--workspace-root', workspace.absolutePath])
        String total = output.readLines().find { it.startsWith('TOTAL\t') }
        verify('all source filename patterns discovered', [
            'prompt.log', 'prompt.log.old', 'prompt.log.new', 'PR.log', 'exporter-prompt.log'
        ].every { String name -> output.readLines().any { it.startsWith("${name}\t") } })
        verify('whole-file duplicate hash skipped', total?.contains('skipped_duplicate_hash=1'))
        verify('report raw unique duplicate accounting', total?.contains('raw_turns=7') &&
            total.contains('unique_turns=6') && total.contains('duplicate_turns=1') && total.contains('reconciled=true'))
        verify('spill report reconciles independently', total?.contains('spill_raw=1') &&
            total.contains('spill_unique=1') && total.contains('spill_duplicate=0'))
    }

    private static void testDigest(File temporaryRoot) {
        File output = new File(temporaryRoot, 'worklog-chat')
        Map assembly = ResumeDigest.assemble(output, 22000)
        File digest = ResumeDigest.write(output, assembly)
        verify('digest exists', digest.exists())
        verify('digest stays within budget', digest.length() <= 22000)
        verify('digest reports generation metadata', digest.text.contains('Generated:') && digest.text.contains('Budget bytes:'))
    }

    private static void testRetrievalAndJson(File temporaryRoot) {
        File dataDir = new File(temporaryRoot, 'data')
        dataDir.mkdirs()
        Config.setDataDir(dataDir.absolutePath)
        File convo = new File(dataDir, 'convo.db')
        File artifacts = new File(dataDir, 'artifacts.db')
        File dross = new File(dataDir, 'dross.db')
        createChatDatabase(convo, 'chat:turns', [
            ['kd6607/d20260911/research/impact-none/turn-000013.md', 'gamma newest memory'],
            ['kd11/d20260811/execute/impact-high/turn-000011.md', 'alpha recent memory'],
            ['kd6607/d20260811/plan/impact-low/turn-000012.md', 'beta newer memory']
        ])
        createChatDatabase(artifacts, 'chat:artifacts', [
            ['kd6607/d20260912/artifact-000014.md', 'beta artifact high impact memory']
        ])
        createChatDatabase(dross, 'chat:dross', [
            ['source/turn-000015.md', 'uniquedrossphrase']
        ])
        DatasetRegistry.createDataset('worklog-chat', ['convo.db', 'artifacts.db'])
        DatasetRegistry.createDataset('worklog-chat-all', ['convo.db', 'artifacts.db', 'dross.db'])
        DatasetRegistry.setActiveDataset('worklog-chat')

        def engine = loader.loadClass('MemoryEngine').getDeclaredConstructor(String).newInstance(convo.absolutePath)
        verify('facet exact short ticket', engine.search('file_path:kd11', 10).size() == 1)
        verify('facet long ticket distinct', engine.search('file_path:kd6607', 10).size() == 2)
        verify('facet date prefix', engine.search('file_path:d202608*', 10).size() == 2)
        verify('facet compound constraint', engine.search('file_path:kd11 AND file_path:execute', 10).size() == 1)
        engine.close()

        String recentJson = executeQueryScript(['--dataset', 'worklog-chat', '--json'], 'recent')
        List recent = new JsonSlurper().parseText(recentJson) as List
        verify('recent sequence ordering', recent*.file_name == ['turn-000013.md', 'turn-000012.md', 'turn-000011.md'])
        verify('recent excludes artifacts with newer ids', recent.every { it.source_archive == 'chat:turns' })
        String filteredRecentJson = executeQueryScript(['--dataset', 'worklog-chat', '--mode', 'plan', '--json'], 'recent')
        List filteredRecent = new JsonSlurper().parseText(filteredRecentJson) as List
        verify('filtered recent preserves sequence order', filteredRecent*.file_name == ['turn-000012.md'])
        String resumeJson = executeQueryScript(['--dataset', 'worklog-chat', '--budget', '22000', '--json'], 'resume')
        List resume = new JsonSlurper().parseText(resumeJson) as List
        verify('resume begins with recent turns', resume.take(3)*.file_name == ['turn-000013.md', 'turn-000012.md', 'turn-000011.md'])
        verify('resume later tiers include artifacts', resume.drop(3).any { it.source_archive == 'chat:artifacts' })
        verify('json contract fields', recent && recent[0].keySet() == ['id', 'source_archive', 'file_path', 'file_name', 'extension', 'rank', 'snippet'] as Set)

        String defaultSearch = executeQueryScript(['uniquedrossphrase', '--dataset', 'worklog-chat', '--json'], 'query')
        String forensicSearch = executeQueryScript(['uniquedrossphrase', '--dataset', 'worklog-chat-all', '--json'], 'query')
        verify('dross excluded from default dataset', (new JsonSlurper().parseText(defaultSearch) as List).isEmpty())
        verify('dross reachable from forensic dataset', (new JsonSlurper().parseText(forensicSearch) as List).size() == 1)
    }

    private static void testDeterministicDirectoryIngestion(Map<String, File> fixtures, File temporaryRoot) {
        List turnPaths = ArchiveHandler.listDirectoryEntries(fixtures.turns)*.path
        List generalPaths = ArchiveHandler.listDirectoryEntries(fixtures.general)*.path
        verify('directory enumeration is path deterministic', turnPaths == turnPaths.sort() && generalPaths == ['a.txt', 'b.txt'])

        File turnDb = new File(temporaryRoot, 'sequence-turns.db')
        executeIngestScript(fixtures.turns, turnDb, 'chat:turns')
        List<Map> turnRows = databaseRows(turnDb, 'chat:turns')
        verify('memory directory ids align with sequence', turnRows*.file_name == [
            'turn-000011.md', 'turn-000012.md', 'turn-000013.md'
        ])

        File generalDb = new File(temporaryRoot, 'sequence-general.db')
        executeIngestScript(fixtures.general, generalDb, 'general')
        List<Map> generalRows = databaseRows(generalDb, 'general')
        verify('general directory ids align with path', generalRows*.file_name == ['a.txt', 'b.txt'])
    }

    private static void testLiveCorpusInvariant() {
        File workspace = Config.workspaceRoot(null, new File('.').canonicalFile)
        List<File> sources = []
        workspace.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
            String relative = workspace.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
            if (!relative.tokenize('/').contains('.git') &&
                (file.name == 'PR.log' || file.name ==~ /(?i).*prompt\.log(?:\.(?:old|new))?$/)) {
                sources << file
            }
        }
        int violations = 0
        sources.each { File source ->
            ChatLogParser.parseFile(source, source.path).each { Map entry ->
                if (hasUserStartAfterAssistant(entry.rawBody?.toString())) violations++
            }
        }
        verify('live corpus has no collapsed independent turns', sources && violations == 0)
    }

    private static Map prepare(Map entry) {
        entry.timestampRepair = TimestampRepair.parse(entry.rawTimestamp?.toString())
        entry = TurnClassifier.classify(entry)
        entry.turnHash = TurnDocument.hash(entry)
        return entry
    }

    private static void createChatDatabase(File file, String archive, List<List<String>> documents) {
        def engine = loader.loadClass('MemoryEngine').getDeclaredConstructor(String).newInstance(file.absolutePath)
        engine.beginBatch()
        documents.each { List<String> document ->
            String path = document[0]
            String content = document[1]
            engine.insertDocument(archive, path, new File(path).name, '.md', content.getBytes(StandardCharsets.UTF_8).length, content)
        }
        engine.endBatch()
        engine.recordManifest(archive, file.length(), documents.size(), documents.sum { it[1].getBytes(StandardCharsets.UTF_8).length } as long)
        engine.optimizeIndex()
        engine.close()
    }

    private static String executeQueryScript(List<String> arguments, String commandName) {
        Class scriptClass = loader.parseClass(new File('03_query_memory.groovy'))
        Script script = scriptClass.getDeclaredConstructor().newInstance() as Script
        Binding binding = new Binding(args: arguments as String[], commandName: commandName, inProcess: true)
        script.binding = binding
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        PrintStream original = System.out
        try {
            System.setOut(new PrintStream(output, true, 'UTF-8'))
            script.run()
        } finally {
            System.setOut(original)
        }
        return output.toString('UTF-8').trim()
    }

    private static String executeChatlogScript(List<String> arguments) {
        Class scriptClass = loader.parseClass(new File('05_ingest_chatlog.groovy'))
        Script script = scriptClass.getDeclaredConstructor().newInstance() as Script
        script.binding = new Binding(args: arguments as String[], commandName: 'chatlog', inProcess: true)
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        PrintStream original = System.out
        try {
            System.setOut(new PrintStream(output, true, 'UTF-8'))
            script.run()
        } finally {
            System.setOut(original)
        }
        return output.toString('UTF-8').trim()
    }

    private static void executeIngestScript(File directory, File database, String archive) {
        if (database.exists()) database.delete()
        File originalDatabase = Config.DB_PATH
        try {
            Config.DB_PATH = database
            Class scriptClass = loader.parseClass(new File('02_ingest_archive.groovy'))
            Script script = scriptClass.getDeclaredConstructor().newInstance() as Script
            script.binding = new Binding(
                args: ['--dir', directory.absolutePath, '--as', archive, '--uncompressed'] as String[],
                commandName: 'ingest',
                inProcess: true
            )
            ByteArrayOutputStream output = new ByteArrayOutputStream()
            PrintStream originalOutput = System.out
            try {
                System.setOut(new PrintStream(output, true, 'UTF-8'))
                script.run()
            } finally {
                System.setOut(originalOutput)
            }
        } finally {
            Config.DB_PATH = originalDatabase
        }
    }

    private static List<Map> databaseRows(File database, String archive) {
        def driver = loader.loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
        def connection = driver.connect("jdbc:sqlite:${database.absolutePath}", new Properties())
        Sql sql = new Sql(connection)
        try {
            List<Map> rows = []
            sql.eachRow(
                'SELECT id, file_name FROM documents WHERE source_archive = ? ORDER BY id ASC',
                [archive]
            ) { row ->
                rows << [id: row.id as long, file_name: row.file_name.toString()]
            }
            return rows
        } finally {
            sql.close()
        }
    }

    private static boolean hasUserStartAfterAssistant(String text) {
        boolean assistantSeen = false
        for (String rawLine : (text ?: '').readLines()) {
            String line = rawLine.trim().replaceFirst(/^\[[^\]]*\d{4}[^\]]*\]\s*/, '')
            if (line ==~ /(?i)^\d{4}-\d{2}-\d{2}(?:T|\s).*\bPROMPT OUTPUT\b.*$/ ||
                line ==~ /(?i)^(ASSISTANT(?:\s+(?:OUTPUT|RESPONSE))?|RESPONSE(?:\s+SUMMARY)?|OUTPUT)(?:\s*\([^)]*\))?(?:\s*-\s*[^:]+)?\s*:.*$/) {
                assistantSeen = true
            } else if (line ==~ /(?i)^\d{4}-\d{2}-\d{2}(?:T|\s).*\bPROMPT INPUT\b.*$/ ||
                line ==~ /(?i)^(USER(?:\s+PROMPTS?)?(?:\s+\d+)?|PROMPT|INPUT|USER QUERY|USER CAUGHT ERROR)(?:\s*\([^)]*\))?(?:\s*-\s*[^:]+)?\s*:.*$/) {
                if (assistantSeen) return true
            }
        }
        return false
    }

    private static int countMarkdown(File root) {
        int count = 0
        root.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
            if (file.name.endsWith('.md')) count++
        }
        return count
    }

    private static void verify(String name, boolean condition) {
        if (condition) {
            passed++
            println "[PASS] ${name}"
        } else {
            failed++
            failures << name
            println "[FAIL] ${name}"
        }
    }
}
