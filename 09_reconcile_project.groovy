String mode = 'both'
if (binding?.hasVariable('commandName')) {
    String commandName = binding.getVariable('commandName')?.toString()
    if (commandName == 'reconcile-transcripts') mode = 'reconcile'
    if (commandName == 'project-journal') mode = 'project'
}
boolean dryRun = false
boolean rebuildProjection = false
String explicitWorkspace = null
String journalDbOverride = null
List<String> sourceFilter = []

int argumentIndex = 0
while (argumentIndex < args.length) {
    String argument = args[argumentIndex]
    if (argument == '--reconcile-only') {
        mode = 'reconcile'
    } else if (argument == '--project-only') {
        mode = 'project'
    } else if (argument == '--dry-run') {
        dryRun = true
    } else if (argument == '--rebuild-projection') {
        rebuildProjection = true
    } else if (argument == '--workspace-root' && argumentIndex + 1 < args.length) {
        explicitWorkspace = args[++argumentIndex]
    } else if (argument.startsWith('--workspace-root=')) {
        explicitWorkspace = argument.substring('--workspace-root='.length())
    } else if (argument == '--journal-db' && argumentIndex + 1 < args.length) {
        journalDbOverride = args[++argumentIndex]
    } else if (argument.startsWith('--journal-db=')) {
        journalDbOverride = argument.substring('--journal-db='.length())
    } else if (argument == '--source' && argumentIndex + 1 < args.length) {
        sourceFilter << args[++argumentIndex]
    } else if (argument.startsWith('--source=')) {
        sourceFilter << argument.substring('--source='.length())
    } else if (argument == '--data-dir' && argumentIndex + 1 < args.length) {
        Config.setDataDir(args[++argumentIndex])
    } else if (argument.startsWith('--data-dir=')) {
        Config.setDataDir(argument.substring('--data-dir='.length()))
    }
    argumentIndex++
}

File repositoryRoot = new File('.').canonicalFile
File workspaceRoot = Config.workspaceRoot(explicitWorkspace, repositoryRoot)
File outputRoot = new File(workspaceRoot, Config.WORKLOG_CHAT_ROOT_NAME)
File journalPath = journalDbOverride ? new File(journalDbOverride).canonicalFile : Config.journalDbPath()
File homeDir = new File(System.getProperty('user.home') ?: '.').canonicalFile

JournalStore store = new JournalStore(journalPath)
try {
    Map reconcileReport = null
    Map projectReport = null
    if (mode in ['both', 'reconcile']) {
        reconcileReport = TranscriptReconciler.reconcile(store, workspaceRoot, homeDir, dryRun, sourceFilter ?: null)
        printReconcileReport(reconcileReport)
        if (reconcileReport.status != 'ok') System.exit(1)
    }
    if (mode in ['both', 'project']) {
        projectReport = rebuildProjection
            ? JournalProjector.rebuild(store, outputRoot, dryRun)
            : JournalProjector.project(store, outputRoot, dryRun)
        printProjectReport(projectReport)
    }
    Map integrity = store.verifyIntegrity(!dryRun)
    println "INTEGRITY\t${integrity.status}\tchecked=${integrity.observations_checked}\tchain_valid=${integrity.chain_valid}\tconflicts=${integrity.canonical_conflicts}"
    if (integrity.status != 'ok') System.exit(1)
} finally {
    store.close()
}

static void printReconcileReport(Map report) {
    Map totals = report.totals as Map
    println "RECONCILE_TRANSCRIPTS\tstatus=${report.status}\tsources=${totals.sources_scanned}\tparsed=${totals.turns_parsed}\tappended=${totals.observations_appended}\texisting=${totals.observations_existing}\tlinks=${totals.links_created}\tconflicts=${totals.conflicts}\tlag=${totals.lag_reports}\terrors=${totals.errors}\tunknown_format=${totals.unknown_format_failures}\telapsed_ms=${report.elapsed_ms}"
    (report.conflicts as List ?: []).each { Map conflict ->
        println "RECONCILE_CONFLICT\t${conflict.existing_observation_id}\t${conflict.transcript_logical_turn_key}\t${conflict.match_basis}"
    }
    (report.lag as List ?: []).each { Map lag ->
        println "RECONCILE_LAG\t${lag.rule_observation_id}\t${lag.transcript_logical_turn_key}\t${lag.basis}"
    }
    (report.sources as List ?: []).findAll { Map source -> source.status != 'ok' }.each { Map source ->
        println "RECONCILE_SOURCE_ERROR\t${source.source_ide}\t${source.source_path}\t${source.status}\t${source.error}"
    }
}

static void printProjectReport(Map report) {
    Map totals = report.totals as Map
    println "PROJECT_JOURNAL\tstatus=${report.status}\tscanned=${totals.canonical_scanned}\temitted=${totals.emitted}\tduplicate=${totals.duplicate}\trefreshed=${totals.refreshed}\tskipped_dross=${totals.skipped_dross}\tcursor=${totals.cursor_before}->${totals.cursor_after}\telapsed_ms=${report.elapsed_ms}"
}
