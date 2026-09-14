import groovy.json.JsonSlurper
@Grab('org.xerial:sqlite-jdbc:3.45.1.0')
@Grab('org.slf4j:slf4j-nop:2.0.12')

class WorklogSuite {
    private static int passed = 0
    private static int failed = 0
    private static GroovyClassLoader loader
    private static Class Config
    private static Class MemoryEngine
    private static Class WorklogParser
    private static Class TicketFreshness
    private static Class ResumeDigest
    private static Class TicketStateCapsule
    private static Class TicketRetrieval

    static void main(String[] arguments) {
        initialize()
        File root = new File(System.getProperty('java.io.tmpdir'), 'ai-memory-ingester-worklog-suite')
        root.deleteDir()
        root.mkdirs()
        Class generator = loader.parseClass(new File('tests/TestFixtureGenerator.groovy'))
        Map fixtures = generator.generateWorklogFixtures(root, true)
        File data = new File(root, 'data')
        Config.setDataDir(data.absolutePath)

        Map parsed = WorklogParser.parseFile(fixtures.primary, 'worklog/2026-09-13_TEST-1234.log')
        verify('ticket from filename', parsed.ticket == 'TEST-1234')
        File compactSource = new File(root.parentFile, '2026-07-01_SCTASK1544083.log')
        compactSource.setText('SCTASK1544083 worklog\n', 'UTF-8')
        verify('compact ticket from worklog filename', WorklogParser.extractTicket(compactSource) == 'SCTASK1544083')
        compactSource.delete()
        verify('structured worklog', parsed.structured)
        verify('summary category', parsed.chunks.any { it.category == 'summary' })
        verify('decision category', parsed.chunks.any { it.category == 'decisions' })
        verify('related ticket category', parsed.chunks.any { it.category == 'related-ticket' })
        verify('blockers category', parsed.chunks.any { it.category == 'blockers' })
        verify('status category', parsed.chunks.any { it.category == 'status' })
        verify('next action category', parsed.chunks.any { it.category == 'next-action' })
        (parsed.chunks as List<Map>).each {
            it.source_mtime = fixtures.primary.lastModified()
            it.latest_source_time = parsed.latest_source_time
        }
        Map freshness = TicketFreshness.classify(parsed.ticket, parsed.chunks, new File(root, 'worklog-chat'))
        verify('newer high impact makes worklog stale', freshness.freshness == 'stale')
        verify('low impact does not define evidence through', freshness.newer_evidence_through == '2026-09-12T12:00:00Z')
        String capsule = TicketStateCapsule.render(TicketStateCapsule.assemble(parsed.ticket, parsed.chunks, freshness))
        verify('capsule is extractive', capsule.contains('OPTION A') && capsule.contains('image 24-9'))
        verify('capsule includes explicit blocker', capsule.contains('production approval is pending'))
        verify('capsule includes next action', capsule.contains('Deploy production'))
        verify('capsule includes provenance', capsule.contains('worklog/2026-09-13_TEST-1234.log'))
        verify('capsule starts with ticket summary', capsule.indexOf('## TICKET SUMMARY') < capsule.indexOf('## RELATED TICKET'))
        verify('capsule renders stale metadata', capsule.contains('freshness: "stale"') && capsule.contains('AUTHORITATIVE WORKLOG STALE'))

        String dry = executeScript(['--all', '--dry-run', '--workspace-root', root.absolutePath])
        verify('dry run accounts sources', dry.contains('structured=2') && dry.contains('evidence=1'))
        verify('dry run writes nothing', !new File(root, 'worklog-chat/worklogs').exists() && !new File(root, 'worklog-chat/ticket-state').exists())

        String first = executeScript(['--all', '--sync-db', '--workspace-root', root.absolutePath])
        verify('first run changes sources', first.contains('changed=3'))
        verify('chunk files emitted', new File(root, 'worklog-chat/worklogs/test1234').exists())
        verify('evidence emitted', new File(root, 'worklog-chat/artifacts/test1234').exists())
        verify('capsule emitted', new File(root, 'worklog-chat/ticket-state/test1234.md').exists())
        verify('worklogs database created', new File(data, 'worklogs.db').exists())
        Map digest = ResumeDigest.assemble(new File(root, 'worklog-chat'), 22000)
        verify('digest puts stale warning before capsule', digest.items[0].file_path == 'ticket-state/test1234-freshness-warning.md' && digest.items[1].file_path == 'ticket-state/test1234.md')
        createTurnDatabase(new File(data, 'convo.db'), new File(root, 'worklog-chat/turns'))

        List<Map> candidates = TicketRetrieval.retrieve(data, 'TEST-1234', '', 20)
        verify('state candidate is first', candidates && candidates[0].pool == 'state')
        verify('state exposes stale freshness', candidates[0].freshness == 'stale')
        verify('newer evidence follows state', candidates.size() > 1 && candidates[1].pool == 'newer-evidence')
        verify('event time outranks global sequence', candidates.findIndexOf { it.file_name == 'turn-000010.md' } < candidates.findIndexOf { it.file_name == 'turn-009999.md' })
        verify('newer evidence remains historical', candidates.findAll { it.pool == 'newer-evidence' }.every { it.authority == 'historical' })
        verify('preview excludes front matter', candidates[0].snippet.startsWith('# TEST-1234'))
        verify('normalization variants', TicketRetrieval.normalizeTicket('TEST-1234') == TicketRetrieval.normalizeTicket('test-1234'))
        verify('compact normalization variants', [
            TicketRetrieval.normalizeTicket('SCTASK1544083'),
            TicketRetrieval.normalizeTicket('sctask1544083')
        ] == ['sctask1544083', 'sctask1544083'])
        verify('compact explicit freshness evidence', TicketFreshness.hasExplicitUserTicket(
            '---\n---\n## User\n\nVerify sctask1544083 deployment\n\n## Assistant\n\nVerified.',
            'SCTASK1544083'
        ))
        verify('semantic pool retrieval', TicketRetrieval.semanticCandidates(data, 'test1234', 'verified', 5).any { it.pool == 'semantic' })
        Map context = TicketRetrieval.assembleContext(candidates, 22000)
        verify('context respects budget', (context.content_bytes as int) <= 22000)
        verify('context includes state', (context.items as List).any { it.pool == 'state' })
        verify('context puts newer evidence before worklog chunks', (context.items as List).findIndexOf { it.pool == 'newer-evidence' } < (context.items as List).findIndexOf { it.pool == 'authoritative' })

        String refresh = executeScript(['--changed', '--sync-db', '--refresh-state', '--workspace-root', root.absolutePath])
        verify('unchanged refresh writes nothing', refresh.contains('changed=0') && refresh.contains('capsules=0') && refresh.contains('state_sync=false'))

        fixtures.primary.setText(fixtures.primary.getText('UTF-8').replace('2026-09-10 12:00', '2026-09-14 12:00'), 'UTF-8')
        String freshRun = executeScript(['--changed', '--sync-db', '--workspace-root', root.absolutePath])
        verify('newer worklog clears stale state', freshRun.contains('fresh_capsules=1') && new File(root, 'worklog-chat/ticket-state/test1234.md').getText('UTF-8').contains('freshness: "fresh"'))

        fixtures.stale.setText(fixtures.stale.getText('UTF-8').replace('old state', 'replacement state'), 'UTF-8')
        String replacement = executeScript(['--changed', '--sync-db', '--workspace-root', root.absolutePath])
        verify('changed source replaced', replacement.contains('changed=1'))
        verify('replacement reaches capsule', new File(root, 'worklog-chat/ticket-state/test5678.md').getText('UTF-8').contains('replacement state'))

        println "Worklog tests: ${passed} passed, ${failed} failed"
        if (failed > 0) System.exit(1)
    }

    private static void initialize() {
        loader = new GroovyClassLoader(WorklogSuite.class.classLoader)
        ['Config', 'TicketKey', 'ArchiveHandler', 'ContentExtractor', 'MemoryEngine', 'TimestampRepair', 'ChatLogParser',
         'FacetPath', 'TurnClassifier', 'TurnDocument', 'TurnEmitter', 'SweepState', 'JournalEvent', 'JournalStore',
         'TicketFreshness', 'ResumeDigest', 'WorklogParser', 'WorklogDocument', 'TicketStateCapsule', 'WorklogEmitter',
         'TicketRetrieval', 'DatasetRegistry', 'SetRegistry', 'FederatedEngine',
         'SignalFusion', 'NeighborExpansion', 'ContextPacker', 'MemoryRetrieval'].each { String name ->
            loader.parseClass(new File("lib/${name}.groovy"))
        }
        Thread.currentThread().contextClassLoader = loader
        Config = loader.loadClass('Config')
        MemoryEngine = loader.loadClass('MemoryEngine')
        WorklogParser = loader.loadClass('WorklogParser')
        TicketFreshness = loader.loadClass('TicketFreshness')
        ResumeDigest = loader.loadClass('ResumeDigest')
        TicketStateCapsule = loader.loadClass('TicketStateCapsule')
        TicketRetrieval = loader.loadClass('TicketRetrieval')
    }

    private static String executeScript(List<String> arguments) {
        Class scriptClass = loader.parseClass(new File('06_ingest_worklogs.groovy'))
        Script script = scriptClass.getDeclaredConstructor().newInstance() as Script
        script.binding = new Binding(args: arguments as String[], commandName: 'worklogs', inProcess: true)
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        PrintStream original = System.out
        try {
            System.setOut(new PrintStream(output, true, 'UTF-8'))
            script.run()
        } finally {
            System.setOut(original)
        }
        return output.toString('UTF-8')
    }

    private static void createTurnDatabase(File database, File turnsRoot) {
        def engine = MemoryEngine.getDeclaredConstructor(String).newInstance(database.absolutePath)
        try {
            engine.beginBatch()
            turnsRoot.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
                if (!file.name.endsWith('.md')) return
                String relative = turnsRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                String content = file.getText('UTF-8')
                engine.insertDocument('chat:turns', relative, file.name, '.md', file.length(), content)
            }
            engine.endBatch()
        } finally {
            engine.close()
        }
    }

    private static void verify(String name, boolean condition) {
        if (condition) {
            passed++
            println "[PASS] ${name}"
        } else {
            failed++
            println "[FAIL] ${name}"
        }
    }
}

WorklogSuite.main(args)
