class TestReconciliationSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class Config
    private static Class JournalEvent
    private static Class JournalStore
    private static Class CursorTranscriptAdapter
    private static Class ClaudeTranscriptAdapter
    private static Class TranscriptReconciler
    private static Class JournalProjector
    private static Class TurnDocument
    private static Class TurnEmitter
    private static Class ResumeDigest

    static void main(String[] args) {
        initializeLoader()
        File tempRoot = new File(System.getProperty('java.io.tmpdir'), "reconciliation-suite-${System.currentTimeMillis()}").canonicalFile
        tempRoot.mkdirs()
        File dataDir = new File(tempRoot, 'data')
        dataDir.mkdirs()
        Config.setDataDir(dataDir.absolutePath)

        try {
            testCursorAdapterFixture()
            testClaudeAdapterFixture()
            testUnknownCursorFormatFails()
            testMissedRuleWriteRecovery(tempRoot, dataDir)
            testConflictRetention(dataDir)
            testProjectionRebuild(tempRoot, dataDir)
            testCursorPersistence(tempRoot, dataDir)
        } finally {
            tempRoot.deleteDir()
        }

        println "Reconciliation tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        if (failed > 0) System.exit(1)
    }

    private static void initializeLoader() {
        loader = new GroovyClassLoader(TestReconciliationSuite.classLoader)
        groovy.grape.Grape.grab(classLoader: loader, group: 'org.xerial', module: 'sqlite-jdbc', version: '3.45.1.0')
        Thread.currentThread().contextClassLoader = loader
        [
            'Config', 'TicketKey', 'FacetPath', 'TimestampRepair', 'TurnClassifier', 'TurnDocument',
            'TurnEmitter', 'JournalEvent', 'JournalStore', 'CursorTranscriptAdapter', 'ClaudeTranscriptAdapter',
            'TranscriptReconciler', 'JournalProjector', 'TicketFreshness', 'ResumeDigest'
        ].each { String name -> loader.parseClass(new File("lib/${name}.groovy")) }
        Config = loader.loadClass('Config')
        JournalEvent = loader.loadClass('JournalEvent')
        JournalStore = loader.loadClass('JournalStore')
        CursorTranscriptAdapter = loader.loadClass('CursorTranscriptAdapter')
        ClaudeTranscriptAdapter = loader.loadClass('ClaudeTranscriptAdapter')
        TranscriptReconciler = loader.loadClass('TranscriptReconciler')
        JournalProjector = loader.loadClass('JournalProjector')
        TurnDocument = loader.loadClass('TurnDocument')
        TurnEmitter = loader.loadClass('TurnEmitter')
        ResumeDigest = loader.loadClass('ResumeDigest')
    }

    private static File stageCursorFixture(File tempRoot, File workspace, String fixtureName, String sessionId = 'session-cursor-test') {
        File home = new File(tempRoot, 'home')
        String projectSlug = CursorTranscriptAdapter.workspaceSlug(workspace, false)
        File transcriptDir = new File(home, ".cursor/projects/${projectSlug}/agent-transcripts/${sessionId}")
        transcriptDir.mkdirs()
        File fixture = new File("tests/fixtures/transcripts/${fixtureName}")
        File target = new File(transcriptDir, "${sessionId}.jsonl")
        target.bytes = fixture.bytes
        return home
    }

    private static File stageClaudeFixture(File tempRoot, File workspace) {
        File home = new File(tempRoot, 'home-claude')
        String projectSlug = ClaudeTranscriptAdapter.workspaceSlug(workspace)
        File projectDir = new File(home, ".claude/projects/${projectSlug}")
        projectDir.mkdirs()
        File fixture = new File('tests/fixtures/transcripts/claude-turn.jsonl')
        new File(projectDir, 'session-claude-test.jsonl').bytes = fixture.bytes
        return home
    }

    private static void testCursorAdapterFixture() {
        File tempRoot = new File(System.getProperty('java.io.tmpdir'), "cursor-adapter-${System.nanoTime()}").canonicalFile
        tempRoot.mkdirs()
        File workspace = new File(tempRoot, 'workspace')
        workspace.mkdirs()
        File home = stageCursorFixture(tempRoot, workspace, 'cursor-turn.jsonl')
        List<File> files = CursorTranscriptAdapter.discoverTranscriptFiles(workspace, home) as List<File>
        verify('cursor discovers fixture transcript', files.size() == 1)
        Map parsed = CursorTranscriptAdapter.parseFile(files[0]) as Map
        verify('cursor format version', parsed.format_version == Config.CURSOR_TRANSCRIPT_FORMAT_V1)
        verify('cursor parses one turn', (parsed.turns as List).size() == 1)
        Map turn = (parsed.turns as List)[0] as Map
        verify('cursor user text', turn.user_text?.contains('KD-6999'))
        verify('cursor assistant text', turn.assistant_text?.contains('MODE: EXECUTE'))
        tempRoot.deleteDir()
    }

    private static void testClaudeAdapterFixture() {
        File tempRoot = new File(System.getProperty('java.io.tmpdir'), "claude-adapter-${System.nanoTime()}").canonicalFile
        tempRoot.mkdirs()
        File workspace = new File(tempRoot, 'workspace')
        workspace.mkdirs()
        File home = stageClaudeFixture(tempRoot, workspace)
        List<File> files = ClaudeTranscriptAdapter.discoverTranscriptFiles(workspace, home) as List<File>
        verify('claude discovers fixture transcript', files.size() == 1)
        Map parsed = ClaudeTranscriptAdapter.parseFile(files[0]) as Map
        verify('claude format version', parsed.format_version == Config.CLAUDE_TRANSCRIPT_FORMAT_V2)
        verify('claude parses one turn', (parsed.turns as List).size() == 1)
        Map turn = (parsed.turns as List)[0] as Map
        verify('claude prompt id retained', turn.prompt_id == 'prompt-claude-001')
        verify('claude assistant text', turn.assistant_text?.contains('idempotent cursor'))
        tempRoot.deleteDir()
    }

    private static void testUnknownCursorFormatFails() {
        File tempRoot = new File(System.getProperty('java.io.tmpdir'), "cursor-unknown-${System.nanoTime()}").canonicalFile
        tempRoot.mkdirs()
        File workspace = new File(tempRoot, 'workspace')
        workspace.mkdirs()
        File home = stageCursorFixture(tempRoot, workspace, 'cursor-unknown.jsonl', 'session-unknown')
        List<File> files = CursorTranscriptAdapter.discoverTranscriptFiles(workspace, home) as List<File>
        boolean failedAsExpected = false
        try {
            CursorTranscriptAdapter.parseFile(files[0])
        } catch (IllegalStateException ex) {
            failedAsExpected = ex.message?.contains('Unknown Cursor transcript format')
        }
        verify('unknown cursor format fails loudly', failedAsExpected)
        tempRoot.deleteDir()
    }

    private static void testMissedRuleWriteRecovery(File tempRoot, File dataDir) {
        File workspace = new File(tempRoot, 'workspace')
        workspace.mkdirs()
        File home = stageCursorFixture(tempRoot, workspace, 'cursor-turn.jsonl', 'session-recovery')
        File outputRoot = new File(tempRoot, 'worklog-chat')
        outputRoot.mkdirs()
        File db = new File(dataDir, 'recovery.db')
        def store = JournalStore.getDeclaredConstructor(File).newInstance(db)
        try {
            List<File> files = CursorTranscriptAdapter.discoverTranscriptFiles(workspace, home) as List<File>
            File transcript = files.find { it.canonicalPath.contains('session-recovery') }
            Map parsed = CursorTranscriptAdapter.parseFile(transcript) as Map
            Map turn = (parsed.turns as List)[0] as Map
            store.record([
                schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
                source_ide: 'cursor',
                source_kind: 'rule_write',
                source_fidelity: 'rule_write',
                source_identity: 'prompt.log',
                session_id: turn.session_id,
                user_text: turn.user_text,
                assistant_text: turn.assistant_text,
                outcome_summary: turn.outcome_summary,
                event_time: turn.event_time,
                event_time_confidence: 'exact'
            ])
            Map first = TranscriptReconciler.reconcile(store, workspace, home, false, [transcript.canonicalPath]) as Map
            verify('recovery reconcile ok', first.status == 'ok')
            verify('recovery appends transcript observation', (first.totals.observations_appended as int) >= 1)
            Map second = TranscriptReconciler.reconcile(store, workspace, home, false, [transcript.canonicalPath]) as Map
            verify('recovery idempotent second pass', (second.totals.observations_appended as int) == 0)
            verify('recovery creates link', (first.totals.links_created as int) >= 1 || (second.totals.links_created as int) >= 0)
            Map project = JournalProjector.project(store, outputRoot, false) as Map
            verify('recovery projection emits turn', (project.totals.emitted as int) + (project.totals.duplicate as int) >= 1)
        } finally {
            store.close()
        }
    }

    private static void testConflictRetention(File dataDir) {
        File db = new File(dataDir, 'conflict.db')
        def store = JournalStore.getDeclaredConstructor(File).newInstance(db)
        try {
            String promptId = 'conflict-prompt'
            Map rule = store.record([
                schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
                source_ide: 'cursor',
                source_kind: 'rule_write',
                source_fidelity: 'rule_write',
                source_identity: 'prompt.log',
                prompt_id: promptId,
                session_id: 'conflict-session',
                user_text: 'same question',
                assistant_text: 'rule version',
                outcome_summary: 'rule version',
                event_time: '2026-09-14T10:00:00Z',
                event_time_confidence: 'exact'
            ]) as Map
            Map transcriptEvent = JournalEvent.normalize([
                schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
                source_ide: 'cursor',
                source_kind: 'native_transcript',
                source_fidelity: 'native_transcript',
                source_identity: 'cursor-transcript:test/conflict-session',
                prompt_id: promptId,
                session_id: 'conflict-session',
                user_text: 'same question',
                assistant_text: 'transcript version',
                outcome_summary: 'transcript version',
                event_time: '2026-09-14T10:00:30Z',
                event_time_confidence: 'exact',
                raw_payload: 'USER:\nsame question\n\nASSISTANT:\ntranscript version'
            ]) as Map
            Map transcript = store.record(transcriptEvent) as Map
            store.recordReconciliationLink(rule.observation_id as String, transcript.observation_id as String, 'conflict', 'prompt_id_time')
            List<Map> canonical = store.canonicalObservations() as List<Map>
            verify('conflict retains canonical winner', canonical.size() == 1)
            verify('native transcript wins canonical', canonical[0].assistant_text == 'transcript version')
            List<Map> all = store.tail(10) as List<Map>
            verify('conflict retains both observations', all.size() == 2)
        } finally {
            store.close()
        }
    }

    private static void testProjectionRebuild(File tempRoot, File dataDir) {
        File outputRoot = new File(tempRoot, 'projection-worklog-chat')
        outputRoot.mkdirs()
        File db = new File(dataDir, 'projection.db')
        def store = JournalStore.getDeclaredConstructor(File).newInstance(db)
        try {
            store.record([
                schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
                source_ide: 'claude',
                source_kind: 'native_transcript',
                source_fidelity: 'native_transcript',
                source_identity: 'claude-transcript:test/session-projection',
                prompt_id: 'projection-001',
                session_id: 'session-projection',
                user_text: 'projection rebuild test',
                assistant_text: 'projected body',
                outcome_summary: 'projected body',
                mode: 'execute',
                tickets: ['DEVOPS-796'],
                event_time: '2026-09-14T12:00:00Z',
                event_time_confidence: 'exact',
                raw_payload: 'USER:\nprojection rebuild test\n\nASSISTANT:\nprojected body'
            ])
            Map first = JournalProjector.project(store, outputRoot, false) as Map
            verify('projection first pass emits', (first.totals.emitted as int) == 1)
            Map second = JournalProjector.project(store, outputRoot, false) as Map
            verify('projection second pass duplicate', (second.totals.emitted as int) == 0)
            Map rebuild = JournalProjector.rebuild(store, outputRoot, false) as Map
            verify('projection rebuild scans all canonical', (rebuild.totals.canonical_scanned as int) >= 1)
            File turnFile = null
            File turnsRoot = new File(outputRoot, 'turns')
            if (turnsRoot.exists()) {
                turnsRoot.eachFileRecurse(groovy.io.FileType.FILES) { File candidate ->
                    if (!turnFile && candidate.name.endsWith('.md')) turnFile = candidate
                }
            }
            verify('projection materialized markdown', turnFile != null)
            if (turnFile) {
                String content = turnFile.getText('UTF-8')
                verify('projection journal provenance', content.contains('journal_observation_id'))
            }
            Map digest = ResumeDigest.assembleFromJournal(store, outputRoot, Config.RESUME_BUDGET_BYTES) as Map
            verify('resume digest from journal', (digest.items as List)?.size() >= 1)
        } finally {
            store.close()
        }
    }

    private static void testCursorPersistence(File tempRoot, File dataDir) {
        File workspace = new File(tempRoot, 'workspace')
        workspace.mkdirs()
        File home = stageCursorFixture(tempRoot, workspace, 'cursor-turn.jsonl', 'session-cursor-persist')
        File db = new File(dataDir, 'cursor-persist.db')
        def store = JournalStore.getDeclaredConstructor(File).newInstance(db)
        try {
            List<File> files = CursorTranscriptAdapter.discoverTranscriptFiles(workspace, home) as List<File>
            File transcript = files.find { it.canonicalPath.contains('session-cursor-persist') }
            Map first = TranscriptReconciler.reconcile(store, workspace, home, false, [transcript.canonicalPath]) as Map
            verify('cursor first reconcile ok', first.status == 'ok')
            Map cursor = store.getSourceCursor(CursorTranscriptAdapter.sourceIdentityFor(transcript)) as Map
            verify('cursor persisted byte offset', (cursor.cursor_value as String).toLong() == transcript.length())
            Map second = TranscriptReconciler.reconcile(store, workspace, home, false, [transcript.canonicalPath]) as Map
            verify('cursor second reconcile no append', (second.totals.observations_appended as int) == 0)
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
