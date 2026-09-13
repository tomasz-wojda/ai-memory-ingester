import java.nio.charset.StandardCharsets
import java.security.MessageDigest

List<String> arguments = args as List<String>
boolean dryRun = arguments.contains('--dry-run')
boolean syncDb = arguments.contains('--sync-db')
String explicitWorkspace = valueAfter(arguments, '--workspace-root')
List<String> explicitSources = valuesAfter(arguments, '--source')
File repositoryRoot = new File('.').canonicalFile
File workspaceRoot = Config.workspaceRoot(explicitWorkspace, repositoryRoot)
File outputRoot = new File(workspaceRoot, Config.WORKLOG_CHAT_ROOT_NAME)
File stateFile = new File(outputRoot, ".index/${Config.WORKLOG_SWEEP_STATE_NAME}")
Map state = WorklogEmitter.loadState(stateFile)

List<File> sources = explicitSources.collect { String path ->
    File requested = new File(path)
    return (requested.absolute ? requested : new File(workspaceRoot, path)).canonicalFile
}
if (!sources) {
    File root = new File(workspaceRoot, 'worklog')
    if (root.exists()) {
        root.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
            if (file.name.toLowerCase().endsWith('.log') && !file.name.equalsIgnoreCase('TEMPLATE.log')) sources << file.canonicalFile
        }
    }
}
sources = sources.unique { it.canonicalPath }.sort { a, b -> a.canonicalPath <=> b.canonicalPath }

Map totals = [discovered: sources.size(), unchanged: 0, changed: 0, structured: 0, evidence: 0, emitted: 0, replaced: 0, deleted: 0, failed: 0, capsules: 0]
Map<String, List<Map>> ticketChunks = [:].withDefault { [] }
Set<String> changedTickets = [] as Set<String>

sources.each { File source ->
    String relative = relativePath(workspaceRoot, source)
    try {
        String sourceHash = sha256(source)
        Map parsed = WorklogParser.parseFile(source, relative)
        boolean evidence = source.name ==~ /(?i).*(?:_jira|_oomkilled|_zoltan-alerts|_raw)\.log$/ || !parsed.structured
        if (parsed.ticket && !evidence) {
            (parsed.chunks as List<Map>).each { Map chunk ->
                chunk.source_mtime = source.lastModified()
                ticketChunks[parsed.ticket.toString()] << chunk
            }
        }
        Map previous = (state.sources[relative] ?: [:]) as Map
        if (previous.source_hash == sourceHash) {
            totals.unchanged++
            return
        }
        totals.changed++
        totals[evidence ? 'evidence' : 'structured']++
        Map staged = WorklogEmitter.stageSource(outputRoot, parsed, source, evidence, dryRun)
        Map replaced = WorklogEmitter.replaceSource(outputRoot, state, relative, staged, sourceHash, source, dryRun)
        totals.emitted += replaced.emitted as int
        totals.replaced += replaced.replaced as int
        totals.deleted += replaced.deleted as int
        if (parsed.ticket) changedTickets << parsed.ticket.toString()
    } catch (Exception error) {
        totals.failed++
        System.err.println("${relative}\tFAILED\t${error.message}")
    }
}

changedTickets.each { String ticket ->
    List<Map> chunks = ticketChunks[ticket]
    if (chunks) {
        String content = TicketStateCapsule.render(TicketStateCapsule.assemble(ticket, chunks))
        File capsule = WorklogEmitter.writeCapsule(outputRoot, ticket, content, dryRun)
        if (!dryRun) state.capsules[ticket] = relativePath(outputRoot, capsule)
        totals.capsules++
    }
}

if (!dryRun) WorklogEmitter.saveState(stateFile, state)
if (syncDb && !dryRun && (totals.changed > 0 || !new File(Config.DATA_DIR, Config.WORKLOGS_DB_NAME).exists())) {
    Config.ensureDataDir()
    syncDirectory(new File(outputRoot, 'ticket-state'), new File(Config.DATA_DIR, Config.WORKLOGS_DB_NAME), Config.WORKLOG_STATE_ARCHIVE)
    syncDirectory(new File(outputRoot, 'worklogs'), new File(Config.DATA_DIR, Config.WORKLOGS_DB_NAME), Config.WORKLOG_CHUNKS_ARCHIVE)
    syncEvidence(new File(outputRoot, 'artifacts'), new File(Config.DATA_DIR, Config.ARTIFACTS_DB_NAME))
    reconcileDatasets()
}

println "WORKLOG_TOTAL\t" + totals.collect { key, value -> "${key}=${value}" }.join('\t') + "\tdry_run=${dryRun}\tsync_db=${syncDb}"
if (totals.failed > 0) exitApp(1)

static void syncDirectory(File root, File database, String archive) {
    List<File> files = []
    if (root.exists()) root.eachFileRecurse(groovy.io.FileType.FILES) { File file -> if (file.name.endsWith('.md')) files << file }
    files.sort { a, b -> a.canonicalPath <=> b.canonicalPath }
    syncFiles(root, files, database, archive)
}

static void syncEvidence(File root, File database) {
    List<File> files = []
    if (root.exists()) root.eachFileRecurse(groovy.io.FileType.FILES) { File file -> if (file.name.startsWith('worklog-evidence-') && file.name.endsWith('.md')) files << file }
    files.sort { a, b -> a.canonicalPath <=> b.canonicalPath }
    syncFiles(root, files, database, Config.WORKLOG_EVIDENCE_ARCHIVE)
}

static void syncFiles(File root, List<File> files, File database, String archive) {
    MemoryEngine engine = new MemoryEngine(database.absolutePath)
    try {
        engine.purgeArchive(archive)
        engine.beginBatch()
        long totalBytes = 0L
        files.each { File file ->
            String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            String content = file.getText('UTF-8')
            long bytes = content.getBytes(StandardCharsets.UTF_8).length
            totalBytes += bytes
            engine.insertDocument(archive, relative, file.name, '.md', bytes, content)
        }
        engine.recordManifest(archive, totalBytes, files.size(), totalBytes, root.path, WorklogDocument.hash(files*.path.join('\n')), WorklogDocument.hash(files.collect { it.length() }.join(',')))
        engine.endBatch()
        engine.optimizeIndex()
    } finally {
        engine.close()
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

static String relativePath(File root, File file) {
    return root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
}

static String sha256(File file) {
    return MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
}

static String valueAfter(List<String> arguments, String option) {
    int index = arguments.indexOf(option)
    return index >= 0 && index + 1 < arguments.size() ? arguments[index + 1] : null
}

static List<String> valuesAfter(List<String> arguments, String option) {
    List<String> values = []
    arguments.eachWithIndex { String argument, int index ->
        if (argument == option && index + 1 < arguments.size()) values << arguments[index + 1]
    }
    return values
}

void exitApp(int code) {
    if (binding?.hasVariable('inProcess') && binding.getVariable('inProcess')) return
    System.exit(code)
}
