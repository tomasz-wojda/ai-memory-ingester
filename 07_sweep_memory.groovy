List<String> arguments = args as List<String>
String workspaceValue = valueAfter(arguments, '--workspace-root')
boolean forceStateRefresh = arguments.contains('--refresh-state')
boolean forceDigestRefresh = arguments.contains('--refresh-digest')
boolean dryRun = arguments.contains('--dry-run')
File repositoryRoot = new File('.').canonicalFile
File workspaceRoot = Config.workspaceRoot(workspaceValue, repositoryRoot)
File outputRoot = new File(workspaceRoot, Config.WORKLOG_CHAT_ROOT_NAME)
File homeDir = new File(System.getProperty('user.home') ?: '.').canonicalFile
List<String> runner = new File(repositoryRoot, 'ai-memory-ingester').exists()
    ? [new File(repositoryRoot, 'ai-memory-ingester').absolutePath]
    : ['groovy', new File(repositoryRoot, 'run.groovy').absolutePath]

long started = System.currentTimeMillis()
Map pruneReport = TurnEmitter.pruneExcluded(outputRoot, dryRun)
boolean exclusionsPruned = ((pruneReport.turns ?: 0) as int) > 0 ||
    ((pruneReport.artifacts ?: 0) as int) > 0 ||
    ((pruneReport.hashes ?: 0) as int) > 0
JournalStore journalStore = new JournalStore(Config.journalDbPath())
Map reconcileReport = null
Map projectReport = null
try {
    reconcileReport = TranscriptReconciler.reconcile(journalStore, workspaceRoot, homeDir, dryRun)
    projectReport = JournalProjector.project(journalStore, outputRoot, dryRun)
} finally {
    journalStore.close()
}

boolean journalChanged = ((reconcileReport?.totals?.observations_appended ?: 0) as int) > 0 ||
    ((projectReport?.totals?.emitted ?: 0) as int) > 0 ||
    ((projectReport?.totals?.refreshed ?: 0) as int) > 0
boolean chatTurnsChanged = journalChanged || exclusionsPruned

List<Map> syncResults = []
boolean chatSyncNeeded = journalChanged ||
    archiveCount(new File(Config.DATA_DIR, Config.CONVERSATION_DB_NAME), 'chat:turns') != matchingCount(new File(outputRoot, 'turns')) { it.name ==~ /turn-\d+\.md/ } ||
    archiveCount(new File(Config.DATA_DIR, Config.ARTIFACTS_DB_NAME), 'chat:artifacts') != matchingCount(new File(outputRoot, 'artifacts')) { it.name ==~ /artifact-\d+\.md/ } ||
    archiveCount(new File(Config.DATA_DIR, Config.DROSS_DB_NAME), 'chat:dross') != matchingCount(new File(outputRoot, 'dross')) { it.name.endsWith('.md') }
if (chatSyncNeeded && !dryRun) {
    syncDirectory(new File(outputRoot, 'turns'), new File(Config.DATA_DIR, Config.CONVERSATION_DB_NAME), 'chat:turns') { File file -> file.name ==~ /turn-\d+\.md/ }
    syncDirectory(new File(outputRoot, 'artifacts'), new File(Config.DATA_DIR, Config.ARTIFACTS_DB_NAME), 'chat:artifacts') { File file -> file.name ==~ /artifact-\d+\.md/ }
    syncDirectory(new File(outputRoot, 'dross'), new File(Config.DATA_DIR, Config.DROSS_DB_NAME), 'chat:dross') { File file -> file.name.endsWith('.md') }
}

List<String> worklogCommand = runner + [
    'worklogs', '--changed', '--sync-db', '--workspace-root', workspaceRoot.absolutePath
]
if (chatTurnsChanged || forceStateRefresh) worklogCommand << '--refresh-state'
if (dryRun) worklogCommand << '--dry-run'
Map worklogs = execute(worklogCommand, repositoryRoot)
boolean worklogsChanged = worklogs.output =~ /(?:^|\t)(?:changed|capsules)=[1-9]/
if (chatSyncNeeded || worklogsChanged) reconcileDatasets()

JournalStore digestStore = new JournalStore(Config.journalDbPath())
boolean digestUpdated = false
try {
    digestUpdated = forceDigestRefresh || chatTurnsChanged || worklogsChanged || !new File(outputRoot, Config.CHAT_RESUME_DIGEST_NAME).exists()
    if (digestUpdated && !dryRun) {
        ResumeDigest.write(outputRoot, Config.RESUME_BUDGET_BYTES, digestStore)
    }
} finally {
    digestStore.close()
}

Map integrityReport = null
Map lagSummary = [:]
JournalStore integrityStore = new JournalStore(Config.journalDbPath())
try {
    integrityReport = integrityStore.verifyIntegrity(!dryRun)
    lagSummary = [
        conflicts: (reconcileReport?.totals?.conflicts ?: 0),
        lag_reports: (reconcileReport?.totals?.lag_reports ?: 0),
        unknown_format_failures: (reconcileReport?.totals?.unknown_format_failures ?: 0)
    ]
} finally {
    integrityStore.close()
}

int failures = (worklogs.code as int) != 0 ? 1 : 0
if (reconcileReport?.status != 'ok') failures++
if (integrityReport?.status != 'ok') failures++

printReconcileLine(reconcileReport)
printProjectLine(projectReport)
println "PRUNE_EXCLUDED\tturns=${pruneReport.turns}\tartifacts=${pruneReport.artifacts}\thashes=${pruneReport.hashes}\tdry_run=${dryRun}"
println worklogs.output.trim()
println "SWEEP_MEMORY\tjournal_changed=${journalChanged}\tchat_turns_changed=${chatTurnsChanged}\tworklogs_changed=${worklogsChanged}\tdatabase_syncs=${chatSyncNeeded && !dryRun ? 3 : 0}\tdigest_updated=${digestUpdated}\tintegrity=${integrityReport?.status}\tconflicts=${lagSummary.conflicts}\tlag=${lagSummary.lag_reports}\tunknown_format=${lagSummary.unknown_format_failures}\tfailures=${failures}\tdigest_bytes=${new File(outputRoot, Config.CHAT_RESUME_DIGEST_NAME).exists() ? new File(outputRoot, Config.CHAT_RESUME_DIGEST_NAME).length() : 0}\telapsed_ms=${System.currentTimeMillis() - started}"
if (failures > 0) exitApp(1)

static void printReconcileLine(Map report) {
    if (!report) return
    Map totals = report.totals as Map
    println "RECONCILE_TRANSCRIPTS\tstatus=${report.status}\tsources=${totals.sources_scanned}\tappended=${totals.observations_appended}\tlinks=${totals.links_created}\tconflicts=${totals.conflicts}\tlag=${totals.lag_reports}"
}

static void printProjectLine(Map report) {
    if (!report) return
    Map totals = report.totals as Map
    println "PROJECT_JOURNAL\tstatus=${report.status}\tscanned=${totals.canonical_scanned}\temitted=${totals.emitted}\tduplicate=${totals.duplicate}\tcursor=${totals.cursor_before}->${totals.cursor_after}"
}

static Map execute(List<String> command, File directory) {
    Process process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start()
    String output = process.inputStream.getText('UTF-8')
    int code = process.waitFor()
    return [code: code, output: output]
}

static void syncDirectory(File root, File database, String archive, Closure<Boolean> include) {
    List<File> files = []
    if (root.exists()) root.eachFileRecurse(groovy.io.FileType.FILES) { File file -> if (include(file)) files << file }
    files.sort { a, b -> a.canonicalPath <=> b.canonicalPath }
    MemoryEngine engine = new MemoryEngine(database.absolutePath)
    try {
        engine.purgeArchive(archive)
        engine.beginBatch()
        long bytes = 0L
        files.each { File file ->
            String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            String content = file.getText('UTF-8')
            long size = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            bytes += size
            engine.insertDocument(archive, relative, file.name, '.md', size, content)
        }
        engine.recordManifest(archive, bytes, files.size(), bytes, root.path, WorklogDocument.hash(files*.path.join('\n')), WorklogDocument.hash(files.collect { it.length() }.join(',')))
        engine.endBatch()
        engine.optimizeIndex()
    } finally {
        engine.close()
    }
}

static int matchingCount(File root, Closure<Boolean> include) {
    int count = 0
    if (root.exists()) root.eachFileRecurse(groovy.io.FileType.FILES) { File file -> if (include(file)) count++ }
    return count
}

static int archiveCount(File database, String archive) {
    if (!database.exists()) return -1
    def driver = Thread.currentThread().contextClassLoader.loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
    def connection = driver.connect("jdbc:sqlite:${database.absolutePath}", new Properties())
    groovy.sql.Sql sql = new groovy.sql.Sql(connection)
    try {
        return (sql.firstRow('SELECT count(*) AS count FROM documents WHERE source_archive = ?', [archive]).count ?: 0) as int
    } finally {
        sql.close()
    }
}

static void reconcileDatasets() {
    Map<String, List<String>> desired = [
        (Config.WORKLOG_CHAT_DATASET): [Config.CONVERSATION_DB_NAME, Config.WORKLOGS_DB_NAME, Config.ARTIFACTS_DB_NAME],
        (Config.WORKLOG_CHAT_TURNS_DATASET): [Config.CONVERSATION_DB_NAME],
        (Config.WORKLOG_CHAT_AUTHORITATIVE_DATASET): [Config.WORKLOGS_DB_NAME],
        (Config.WORKLOG_CHAT_ALL_DATASET): [Config.CONVERSATION_DB_NAME, Config.WORKLOGS_DB_NAME, Config.ARTIFACTS_DB_NAME, Config.DROSS_DB_NAME]
    ]
    desired.each { String name, List<String> databases ->
        try { DatasetRegistry.createDataset(name, databases) } catch (Exception ignored) {}
        databases.each { String database ->
            try { DatasetRegistry.addDatabaseToDataset(name, database) } catch (Exception ignored) {}
        }
    }
    DatasetRegistry.setActiveDataset(Config.WORKLOG_CHAT_DATASET)
}

static String valueAfter(List<String> arguments, String option) {
    int index = arguments.indexOf(option)
    return index >= 0 && index + 1 < arguments.size() ? arguments[index + 1] : null
}

void exitApp(int code) {
    if (binding?.hasVariable('inProcess') && binding.getVariable('inProcess')) return
    System.exit(code)
}
