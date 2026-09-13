import groovy.json.JsonSlurper
@Grab('org.xerial:sqlite-jdbc:3.45.1.0')
@Grab('org.slf4j:slf4j-nop:2.0.12')

class WorklogSuite {
    private static int passed = 0
    private static int failed = 0
    private static GroovyClassLoader loader
    private static Class Config
    private static Class WorklogParser
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
        verify('structured worklog', parsed.structured)
        verify('summary category', parsed.chunks.any { it.category == 'summary' })
        verify('decision category', parsed.chunks.any { it.category == 'decisions' })
        verify('status category', parsed.chunks.any { it.category == 'status' })
        verify('next action category', parsed.chunks.any { it.category == 'next-action' })
        (parsed.chunks as List<Map>).each { it.source_mtime = fixtures.primary.lastModified() }
        String capsule = TicketStateCapsule.render(TicketStateCapsule.assemble(parsed.ticket, parsed.chunks))
        verify('capsule is extractive', capsule.contains('OPTION A') && capsule.contains('image 24-9'))
        verify('capsule includes blocker', capsule.contains('promotion to master'))
        verify('capsule includes next action', capsule.contains('Deploy production'))
        verify('capsule includes provenance', capsule.contains('worklog/2026-09-13_TEST-1234.log'))

        String dry = executeScript(['--all', '--dry-run', '--workspace-root', root.absolutePath])
        verify('dry run accounts sources', dry.contains('structured=2') && dry.contains('evidence=1'))
        verify('dry run writes nothing', !new File(root, 'worklog-chat').exists())

        String first = executeScript(['--all', '--sync-db', '--workspace-root', root.absolutePath])
        verify('first run changes sources', first.contains('changed=3'))
        verify('chunk files emitted', new File(root, 'worklog-chat/worklogs/test1234').exists())
        verify('evidence emitted', new File(root, 'worklog-chat/artifacts/test1234').exists())
        verify('capsule emitted', new File(root, 'worklog-chat/ticket-state/test1234.md').exists())
        verify('worklogs database created', new File(data, 'worklogs.db').exists())

        List<Map> candidates = TicketRetrieval.retrieve(data, 'TEST-1234', '', 20)
        verify('state candidate is first', candidates && candidates[0].pool == 'state')
        verify('preview excludes front matter', candidates[0].snippet.startsWith('# TEST-1234'))
        verify('normalization variants', TicketRetrieval.normalizeTicket('TEST-1234') == TicketRetrieval.normalizeTicket('test1234'))
        verify('semantic pool retrieval', TicketRetrieval.semanticCandidates(data, 'test1234', 'verified', 5).any { it.pool == 'semantic' })
        Map context = TicketRetrieval.assembleContext(candidates, 22000)
        verify('context respects budget', (context.content_bytes as int) <= 22000)
        verify('context includes state', (context.items as List).any { it.pool == 'state' })

        String second = executeScript(['--changed', '--sync-db', '--workspace-root', root.absolutePath])
        verify('unchanged rerun does no work', second.contains('changed=0') && second.contains('unchanged=3'))

        fixtures.stale.setText(fixtures.stale.getText('UTF-8').replace('old state', 'replacement state'), 'UTF-8')
        String replacement = executeScript(['--changed', '--sync-db', '--workspace-root', root.absolutePath])
        verify('changed source replaced', replacement.contains('changed=1'))
        verify('replacement reaches capsule', new File(root, 'worklog-chat/ticket-state/test5678.md').getText('UTF-8').contains('replacement state'))

        println "Worklog tests: ${passed} passed, ${failed} failed"
        if (failed > 0) System.exit(1)
    }

    private static void initialize() {
        loader = new GroovyClassLoader(WorklogSuite.class.classLoader)
        ['Config', 'ArchiveHandler', 'ContentExtractor', 'MemoryEngine', 'TimestampRepair', 'ChatLogParser',
         'FacetPath', 'TurnClassifier', 'TurnDocument', 'TurnEmitter', 'SweepState', 'ResumeDigest',
         'WorklogParser', 'WorklogDocument', 'TicketStateCapsule', 'WorklogEmitter', 'TicketRetrieval',
         'DatasetRegistry', 'SetRegistry', 'FederatedEngine'].each { String name ->
            loader.parseClass(new File("lib/${name}.groovy"))
        }
        Thread.currentThread().contextClassLoader = loader
        Config = loader.loadClass('Config')
        WorklogParser = loader.loadClass('WorklogParser')
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
