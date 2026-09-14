List<String> arguments = args as List<String>
String workspaceValue = valueAfter(arguments, '--workspace-root')
boolean forceStateRefresh = arguments.contains('--refresh-state')
boolean forceDigestRefresh = arguments.contains('--refresh-digest')
File repositoryRoot = new File('.').canonicalFile
File workspaceRoot = Config.workspaceRoot(workspaceValue, repositoryRoot)
File outputRoot = new File(workspaceRoot, Config.WORKLOG_CHAT_ROOT_NAME)
List<String> runner = new File(repositoryRoot, 'ai-memory-ingester').exists()
    ? [new File(repositoryRoot, 'ai-memory-ingester').absolutePath]
    : ['groovy', new File(repositoryRoot, 'run.groovy').absolutePath]

long started = System.currentTimeMillis()
Map chat = execute(runner + ['chatlog', '--since-last', '--defer-digest', '--workspace-root', workspaceRoot.absolutePath], repositoryRoot)
boolean chatChanged = chat.output =~ /(?:unique_(?:turns|artifacts|dross)|metadata_refreshes|path_moves)=[1-9]/
boolean chatTurnsChanged = chat.output =~ /(?:unique_turns|metadata_refreshes|path_moves)=[1-9]/
List<Map> syncResults = []
boolean chatSyncNeeded = chatChanged ||
    archiveCount(new File(Config.DATA_DIR, Config.CONVERSATION_DB_NAME), 'chat:turns') != matchingCount(new File(outputRoot, 'turns')) { it.name ==~ /turn-\d+\.md/ } ||
    archiveCount(new File(Config.DATA_DIR, Config.ARTIFACTS_DB_NAME), 'chat:artifacts') != matchingCount(new File(outputRoot, 'artifacts')) { it.name ==~ /artifact-\d+\.md/ } ||
    archiveCount(new File(Config.DATA_DIR, Config.DROSS_DB_NAME), 'chat:dross') != matchingCount(new File(outputRoot, 'dross')) { it.name.endsWith('.md') }
if (chatSyncNeeded) {
    syncDirectory(new File(outputRoot, 'turns'), new File(Config.DATA_DIR, Config.CONVERSATION_DB_NAME), 'chat:turns') { File file -> file.name ==~ /turn-\d+\.md/ }
    syncDirectory(new File(outputRoot, 'artifacts'), new File(Config.DATA_DIR, Config.ARTIFACTS_DB_NAME), 'chat:artifacts') { File file -> file.name ==~ /artifact-\d+\.md/ }
    syncDirectory(new File(outputRoot, 'dross'), new File(Config.DATA_DIR, Config.DROSS_DB_NAME), 'chat:dross') { File file -> file.name.endsWith('.md') }
}

List<String> worklogCommand = runner + [
    'worklogs', '--changed', '--sync-db', '--workspace-root', workspaceRoot.absolutePath
]
if (chatTurnsChanged || forceStateRefresh) worklogCommand << '--refresh-state'
Map worklogs = execute(worklogCommand, repositoryRoot)
boolean worklogsChanged = worklogs.output =~ /(?:^|\t)(?:changed|capsules)=[1-9]/
if (chatSyncNeeded || worklogsChanged) reconcileDatasets()
boolean digestUpdated = forceDigestRefresh || chatTurnsChanged || worklogsChanged || !new File(outputRoot, Config.CHAT_RESUME_DIGEST_NAME).exists()
if (digestUpdated) ResumeDigest.write(outputRoot, Config.RESUME_BUDGET_BYTES)

int failures = [chat, worklogs].count { (it.code as int) != 0 }
println chat.output.trim()
println worklogs.output.trim()
println "SWEEP_MEMORY\tchat_changed=${chatChanged}\tchat_turns_changed=${chatTurnsChanged}\tworklogs_changed=${worklogsChanged}\tdatabase_syncs=${chatSyncNeeded ? 3 : 0}\tdigest_updated=${digestUpdated}\tfailures=${failures}\tdigest_bytes=${new File(outputRoot, Config.CHAT_RESUME_DIGEST_NAME).length()}\telapsed_ms=${System.currentTimeMillis() - started}"
if (failures > 0) exitApp(1)

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
