import groovy.json.JsonOutput
import java.util.concurrent.CountDownLatch
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class TestMemoryLockSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class Config
    private static Class JournalStore
    private static Class MemoryLock
    private static Class ResumeDigest

    static void main(String[] args) {
        System.setProperty('ai.memory.lock.timeout.ms', '300')
        initializeLoader()
        File tempRoot = new File(System.getProperty('java.io.tmpdir'), "memory-lock-suite-${System.currentTimeMillis()}").canonicalFile
        File workspace = new File(tempRoot, 'workspace')
        File dataDir = new File(tempRoot, 'data')
        workspace.mkdirs()
        dataDir.mkdirs()
        Config.setDataDir(dataDir.absolutePath)

        try {
            testLock(workspace)
            Map<String, Object> fixture = createFixture(workspace, dataDir)
            testActiveTickets(workspace)
            testTicketResume(workspace, fixture)
            testTicketResumeBudget(workspace, fixture)
            testResumeCurrency(workspace, fixture)
            testIndex(workspace, fixture)
            testWriteAtomic(workspace)
        } finally {
            tempRoot.deleteDir()
        }

        println "Memory lock tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        if (failed > 0) System.exit(1)
    }

    private static void initializeLoader() {
        loader = new GroovyClassLoader(TestMemoryLockSuite.classLoader)
        groovy.grape.Grape.grab(classLoader: loader, group: 'org.xerial', module: 'sqlite-jdbc', version: '3.45.1.0')
        Thread.currentThread().contextClassLoader = loader
        [
            'Config', 'TicketKey', 'FacetPath', 'TurnDocument', 'TimestampRepair', 'ChatLogParser',
            'MemoryExclusion', 'TurnClassifier', 'JournalEvent', 'JournalStore', 'MemoryLock',
            'TicketFreshness', 'ResumeDigest'
        ].each { String name -> loader.parseClass(new File("lib/${name}.groovy")) }
        Config = loader.loadClass('Config')
        JournalStore = loader.loadClass('JournalStore')
        MemoryLock = loader.loadClass('MemoryLock')
        ResumeDigest = loader.loadClass('ResumeDigest')
    }

    private static void testLock(File workspace) {
        verify('lock enabled by default', MemoryLock.enabled())
        verify('lock timeout from property', Config.MEMORY_LOCK_TIMEOUT_MS == 300)
        Object value = MemoryLock.withLock(workspace) { 42 }
        verify('lock returns the result', value == 42)
        verify('lock file created', MemoryLock.lockFile(workspace).isFile())
        verify('lock file path', MemoryLock.lockFile(workspace).path.endsWith('.ai-worklog/locks/_journal.sqlite'))

        CountDownLatch held = new CountDownLatch(1)
        CountDownLatch release = new CountDownLatch(1)
        Thread holder = Thread.start {
            Thread.currentThread().contextClassLoader = loader
            MemoryLock.withLock(workspace) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        held.await(5, TimeUnit.SECONDS)
        String message = null
        try {
            MemoryLock.withLock(workspace) { 'ran' }
        } catch (IllegalStateException error) {
            message = error.message
        }
        verify('busy lock fails with a clear message', message?.startsWith('Memory lock busy'))
        release.countDown()
        holder.join(5000)
        verify('lock is free again', MemoryLock.withLock(workspace) { 'ran' } == 'ran')
    }

    private static Map<String, Object> createFixture(File workspace, File dataDir) {
        File state = new File(workspace, '.ai-worklog/state')
        state.mkdirs()
        writeJson(new File(state, 'KD-1.json'), [
            ticket_key: 'KD-1', lifecycle: 'active', phase: 'plan', updated_at: '2026-10-30T10:00:00Z',
            next_action: 'Run the UAT dry-run on Monday.',
            decisions: [
                [id: 'D1', description: 'Pick option A or B', status: 'open'],
                [id: 'D2', description: 'Old settled question', status: 'resolved']
            ],
            blockers: [
                [description: 'PR is not merged', status: 'active'],
                [description: 'Finished blocker', status: 'resolved']
            ]
        ])
        writeJson(new File(state, 'KD-2.json'), [ticket_key: 'KD-2', lifecycle: 'done', phase: 'closeout', next_action: 'none'])
        writeJson(new File(state, 'KD-3.json'), [ticket_key: 'KD-3', next_action: 'Old style state file'])
        writeJson(new File(state, 'KD-4.json'), [ticket_key: 'KD-4', next_action: 'No worklog for this one'])
        File worklog = new File(workspace, 'worklog')
        worklog.mkdirs()
        new File(worklog, '2026-10-30_KD-3.log').setText('TICKET: KD-3\n', 'UTF-8')
        File capsule = new File(workspace, 'worklog-chat/ticket-state/kd1.md')
        capsule.parentFile.mkdirs()
        capsule.setText('---\nticket: "KD-1"\nfreshness: "fresh"\n---\n\n# KD-1 Current State\n\n## TICKET SUMMARY\n\nSummary text for KD-1\n', 'UTF-8')

        def store = JournalStore.getDeclaredConstructor(File).newInstance(new File(dataDir, 'journal.db'))
        try {
            3.times { int index ->
                store.record(event("kd1-${index}", ['KD-1'], "KD-1 turn ${index} " + ('word ' * 40)))
            }
            store.record(event('kd2-0', ['KD-2'], 'KD-2 turn'))
            2.times { int index -> store.record(event("none-${index}", [], "untagged turn ${index}")) }
        } finally {
            store.close()
        }
        return [dataDir: dataDir]
    }

    private static void testActiveTickets(File workspace) {
        List<Map> active = ResumeDigest.activeTickets(workspace)
        verify('active tickets by lifecycle and open worklog', active*.key == ['KD-1', 'KD-3'])
        verify('active ticket carries next action', active[0].next_action == 'Run the UAT dry-run on Monday.')
    }

    private static List<Map> observations(Map fixture) {
        def store = JournalStore.getDeclaredConstructor(File).newInstance(new File(fixture.dataDir as File, 'journal.db'))
        try {
            return ResumeDigest.canonicalObservations(store) as List<Map>
        } finally {
            store.close()
        }
    }

    private static void testTicketResume(File workspace, Map fixture) {
        List<Map> all = observations(fixture)
        Map<String, List<Map>> grouped = ResumeDigest.observationsByTicket(all)
        verify('observations grouped by ticket', grouped.keySet() == ['KD-1', 'KD-2'] as Set && grouped['KD-1'].size() == 3)
        File output = new File(workspace, 'worklog-chat')
        Map built = ResumeDigest.buildTicketResume(output, workspace, 'KD-1', grouped['KD-1'])
        String content = built.content
        verify('resume front matter', content.startsWith('---\nticket: "KD-1"') && content.contains('latest_sequence: ' + built.latest_sequence))
        verify('resume has capsule state', content.contains('## Current state') && content.contains('Summary text for KD-1'))
        verify('resume has next action', content.contains('## Next action\n\nRun the UAT dry-run on Monday.'))
        verify('resume lists open decision only', content.contains('Decision D1 (open): Pick option A or B') && !content.contains('Old settled question'))
        verify('resume lists active blocker only', content.contains('Blocker: PR is not merged') && !content.contains('Finished blocker'))
        verify('resume has recent turns of the ticket', content.contains('KD-1 turn 2') && !content.contains('KD-2 turn'))
        verify('resume newest turn first', content.indexOf('KD-1 turn 2') < content.indexOf('KD-1 turn 0'))
        verify('resume has sources', content.contains('## Sources') && content.contains('.ai-worklog/state/KD-1.json'))
        verify('resume turns counted', built.turns == 3)
    }

    private static void testTicketResumeBudget(File workspace, Map fixture) {
        Map<String, List<Map>> grouped = ResumeDigest.observationsByTicket(observations(fixture))
        File output = new File(workspace, 'worklog-chat')
        Map built = ResumeDigest.buildTicketResume(output, workspace, 'KD-1', grouped['KD-1'], 1500)
        verify('resume respects a small budget', (built.content as String).getBytes(StandardCharsets.UTF_8).length <= 1500)
        Map none = ResumeDigest.buildTicketResume(output, workspace, 'KD-3', [])
        verify('resume without turns or capsule', none.content.contains('No recorded turns.') && none.content.contains('No capsule yet.'))
    }

    private static void testResumeCurrency(File workspace, Map fixture) {
        Map<String, List<Map>> grouped = ResumeDigest.observationsByTicket(observations(fixture))
        File output = new File(workspace, 'worklog-chat')
        Map built = ResumeDigest.buildTicketResume(output, workspace, 'KD-1', grouped['KD-1'])
        File target = new File(output, 'ticket-resume/KD-1.md')
        ResumeDigest.writeAtomic(target, built.content as String)
        File stateFile = new File(workspace, '.ai-worklog/state/KD-1.json')
        target.setLastModified(System.currentTimeMillis() + 5000)
        verify('resume is current', ResumeDigest.ticketResumeCurrent(target, built.latest_sequence as long, [stateFile]))
        verify('resume stale after a new turn', !ResumeDigest.ticketResumeCurrent(target, (built.latest_sequence as long) + 1, [stateFile]))
        stateFile.setLastModified(System.currentTimeMillis() + 60000)
        verify('resume stale after a state change', !ResumeDigest.ticketResumeCurrent(target, built.latest_sequence as long, [stateFile]))
        verify('missing resume is not current', !ResumeDigest.ticketResumeCurrent(new File(output, 'ticket-resume/none.md'), 0L, []))
    }

    private static void testIndex(File workspace, Map fixture) {
        File output = new File(workspace, 'worklog-chat')
        String index = ResumeDigest.buildIndex(output, workspace, 22000, observations(fixture))
        verify('index header kept', index.startsWith('# Worklog Chat Resume') && index.contains('Budget bytes: 22000'))
        verify('index lists active tickets', index.contains('### KD-1') && index.contains('### KD-3'))
        verify('index skips finished tickets', !index.contains('### KD-2') && !index.contains('### KD-4'))
        verify('index points to the resume file', index.contains('worklog-chat/ticket-resume/KD-1.md'))
        verify('index has phase and freshness', index.contains('- Phase: plan') && index.contains('- Freshness: fresh'))
        verify('index has untagged turns only', index.contains('untagged turn 1') && !index.contains('KD-1 turn 0'))
        String small = ResumeDigest.buildIndex(output, workspace, 900, observations(fixture))
        verify('index respects a small budget', small.getBytes(StandardCharsets.UTF_8).length <= 900)
    }

    private static void testWriteAtomic(File workspace) {
        File target = new File(workspace, 'atomic/out.md')
        ResumeDigest.writeAtomic(target, 'one')
        ResumeDigest.writeAtomic(target, 'two')
        verify('atomic write replaces the content', target.getText('UTF-8') == 'two')
        verify('atomic write leaves no temporary file', target.parentFile.list().toList() == ['out.md'])
    }

    private static Map event(String promptId, List<String> tickets, String text) {
        return [
            schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
            source_ide: 'cursor',
            source_kind: 'rule_write',
            source_identity: 'prompt.log',
            session_id: 'promptlog:2026-10-30',
            prompt_id: promptId,
            user_text: text,
            assistant_text: "answer to ${text}".toString(),
            outcome_summary: 'ok',
            mode: 'execute',
            tickets: tickets,
            impact: 'low',
            raw_payload: text,
            event_time: '2026-10-30T12:00:00Z',
            event_time_confidence: 'exact'
        ]
    }

    private static void writeJson(File file, Map content) {
        file.parentFile.mkdirs()
        file.setText(JsonOutput.toJson(content), 'UTF-8')
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
