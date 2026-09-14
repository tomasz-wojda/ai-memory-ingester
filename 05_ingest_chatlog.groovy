import groovy.io.FileType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Transforms workspace prompt journals into deduplicated, faceted Markdown memory files.
 */

boolean allSources = false
boolean sinceLast = false
boolean dryRun = false
boolean deferDigest = false
boolean journalBackfill = false
String explicitWorkspace = null
String journalDbOverride = null
long spillBytes = Config.CHAT_SPILL_BYTES
List<String> sourceArguments = []

int argumentIndex = 0
while (argumentIndex < args.length) {
    String argument = args[argumentIndex]
    if (argument == '--all') {
        allSources = true
    } else if (argument == '--since-last') {
        sinceLast = true
        allSources = true
    } else if (argument == '--dry-run') {
        dryRun = true
    } else if (argument == '--defer-digest') {
        deferDigest = true
    } else if (argument == '--source' && argumentIndex + 1 < args.length) {
        sourceArguments << args[++argumentIndex]
    } else if (argument.startsWith('--source=')) {
        sourceArguments << argument.substring('--source='.length())
    } else if (argument == '--workspace-root' && argumentIndex + 1 < args.length) {
        explicitWorkspace = args[++argumentIndex]
    } else if (argument.startsWith('--workspace-root=')) {
        explicitWorkspace = argument.substring('--workspace-root='.length())
    } else if (argument == '--spill-bytes' && argumentIndex + 1 < args.length) {
        spillBytes = args[++argumentIndex].toLong()
    } else if (argument.startsWith('--spill-bytes=')) {
        spillBytes = argument.substring('--spill-bytes='.length()).toLong()
    } else if (argument == '--journal-backfill') {
        journalBackfill = true
    } else if (argument == '--journal-db' && argumentIndex + 1 < args.length) {
        journalDbOverride = args[++argumentIndex]
    } else if (argument.startsWith('--journal-db=')) {
        journalDbOverride = argument.substring('--journal-db='.length())
    }
    argumentIndex++
}

File repositoryRoot = new File('.').canonicalFile
File workspaceRoot = Config.workspaceRoot(explicitWorkspace, repositoryRoot)
File outputRoot = new File(workspaceRoot, Config.WORKLOG_CHAT_ROOT_NAME)
File indexFile = new File(new File(outputRoot, '.index'), Config.CHAT_HASH_INDEX_NAME)
File stateFile = new File(new File(outputRoot, '.index'), Config.CHAT_SWEEP_STATE_NAME)

List<File> sources = []
sourceArguments.each { String path ->
    File source = new File(path)
    if (!source.isAbsolute()) source = new File(workspaceRoot, path)
    if (source.exists() && source.isFile()) sources << source.canonicalFile
}
if (allSources || sources.isEmpty()) {
    workspaceRoot.eachFileRecurse(FileType.FILES) { File candidate ->
        String relative = relativePath(workspaceRoot, candidate)
        if (!relative.tokenize('/').contains('.git') &&
            isPromptJournalName(candidate.name)) {
            sources << candidate.canonicalFile
        }
    }
}
sources = sources.unique { it.canonicalPath }.sort { File left, File right ->
    int bySize = right.length() <=> left.length()
    return bySize != 0 ? bySize : left.canonicalPath <=> right.canonicalPath
}

Map<String, Map> hashIndex = TurnEmitter.loadHashIndex(indexFile)
Map sweepState = SweepState.load(stateFile)
long sequence = (hashIndex.values().collect { (it.sequence ?: 0L) as long }.max() ?: 0L) + 1L
Set<String> knownFileHashes = [] as Set<String>
(sweepState.sources as Map).values().each { Object record ->
    String fullHash = (record as Map).full_hash?.toString()
    if (fullHash) knownFileHashes << fullHash
}

Map totals = [
    raw: [turns: 0, artifacts: 0, dross: 0],
    unique: [turns: 0, artifacts: 0, dross: 0],
    duplicate: [turns: 0, artifacts: 0, dross: 0],
    spill: [raw: 0, unique: 0, duplicate: 0],
    refacet: [rescannedSources: 0, metadataRefreshes: 0, pathMoves: 0, canonicalUnchanged: 0],
    skippedUnchanged: 0,
    skippedDuplicate: 0
]
Map journalTotals = [
    sources: 0,
    snapshots: 0,
    observations: 0,
    idempotent: 0,
    unparsed_ranges: 0
]
long started = System.nanoTime()
boolean sourceStateChanged = false
JournalStore journalStore = null
if (journalBackfill && !dryRun) {
    File journalPath = journalDbOverride ? new File(journalDbOverride).canonicalFile : Config.journalDbPath()
    journalStore = new JournalStore(journalPath)
}

sources.each { File source ->
    String sourcePath = relativePath(workspaceRoot, source)
    String fullHash = sha256(source)
    Map record = (sweepState.sources as Map)?.get(sourcePath) as Map
    boolean parserMismatch = record && ((record.parser_version ?: 0) as int) != Config.CHAT_PARSER_VERSION
    boolean unchanged = record && !parserMismatch && record.full_hash == fullHash && (record.byte_offset as long) == source.length()
    boolean duplicateFile = knownFileHashes.contains(fullHash) && !unchanged && !parserMismatch

    if ((unchanged || duplicateFile) && !journalBackfill) {
        if (unchanged) totals.skippedUnchanged++ else totals.skippedDuplicate++
        String reason = unchanged ? 'unchanged hash' : 'duplicate hash'
        println "${sourcePath}\tSKIPPED\t${reason}"
        if (!dryRun && duplicateFile) {
            SweepState.record(sweepState, sourcePath, source)
            (sweepState.sources[sourcePath] as Map).full_hash = fullHash
            sourceStateChanged = true
        }
        return
    }

    long startOffset = 0L
    boolean fullRescan = parserMismatch
    if (sinceLast && record) {
        fullRescan = parserMismatch || SweepState.needsFullRescan(source, record)
        startOffset = fullRescan ? 0L : SweepState.offsetFor(sweepState, sourcePath)
    }

    List<Map> entries = ChatLogParser.parseFile(source, sourcePath, startOffset)
    entries.each { Map entry -> entry.timestampRepair = TimestampRepair.parse(entry.rawTimestamp?.toString()) }
    TimestampRepair.interpolate(entries)

    if (journalStore) {
        journalTotals.sources++
        Map snapshot = journalStore.recordSourceSnapshot(sourcePath, sourcePath, source)
        if (snapshot.inserted) journalTotals.snapshots++
        List<Map> unparsed = JournalStore.computeUncoveredRanges(source.bytes, entries)
        journalStore.recordUnparsedRanges(snapshot.snapshot_id as String, unparsed)
        journalTotals.unparsed_ranges += unparsed.size()
    }

    Map sourceCounts = [
        raw: [turns: 0, artifacts: 0, dross: 0],
        unique: [turns: 0, artifacts: 0, dross: 0],
        duplicate: [turns: 0, artifacts: 0, dross: 0],
        spill: [raw: 0, unique: 0, duplicate: 0],
        refacet: [metadataRefreshes: 0, pathMoves: 0, canonicalUnchanged: 0]
    ]
    entries.each { Map parsed ->
        Map entry = TurnClassifier.classify(parsed, spillBytes)
        entry.turnHash = TurnDocument.hash(entry)
        if (journalStore) {
            long legacySequence = hashIndex[entry.turnHash]?.sequence ?: sequence
            Map journalEvent = JournalEvent.fromParsedEntry(entry, sourcePath, legacySequence)
            Map journalResult = journalStore.backfillObservation(journalEvent)
            if (journalResult.idempotency == 'existing') journalTotals.idempotent++ else journalTotals.observations++
        }
        long currentSequence = sequence
        String classifiedTier = entry.tier.toString()
        sourceCounts.raw[classifiedTier]++

        if (entry.spillRequired) {
            sourceCounts.spill.raw++
            Map artifactEntry = new LinkedHashMap(entry)
            artifactEntry.dialect = 'Artifact'
            artifactEntry.tier = 'artifacts'
            artifactEntry.user = entry.user
            artifactEntry.assistant = entry.rawBody
            artifactEntry.sections = [:]
            artifactEntry.turnHash = TurnDocument.hash("artifact ${entry.rawBody}")
            Map artifactResult = TurnEmitter.emit(
                outputRoot, artifactEntry, 'artifacts', currentSequence, hashIndex, null, dryRun, parserMismatch
            )
            recordRefacetResult(sourceCounts.refacet as Map, artifactResult)
            if (artifactResult.emitted) sourceCounts.spill.unique++ else sourceCounts.spill.duplicate++
            String spillPath = artifactResult.path?.toString()
            Map turnResult = TurnEmitter.emit(
                outputRoot, entry, classifiedTier, currentSequence, hashIndex, spillPath, dryRun, parserMismatch
            )
            recordRefacetResult(sourceCounts.refacet as Map, turnResult)
            if (turnResult.emitted) sourceCounts.unique[classifiedTier]++ else sourceCounts.duplicate[classifiedTier]++
            if (artifactResult.emitted || turnResult.emitted) sequence++
        } else {
            Map result = TurnEmitter.emit(outputRoot, entry, classifiedTier, currentSequence, hashIndex, null, dryRun, parserMismatch)
            recordRefacetResult(sourceCounts.refacet as Map, result)
            if (result.emitted) {
                sourceCounts.unique[classifiedTier]++
                sequence++
            } else {
                sourceCounts.duplicate[classifiedTier]++
            }
        }
    }

    ['raw', 'unique', 'duplicate'].each { String category ->
        ['turns', 'artifacts', 'dross'].each { String tier ->
            totals[category][tier] += sourceCounts[category][tier]
        }
    }
    ['raw', 'unique', 'duplicate'].each { String category ->
        totals.spill[category] += sourceCounts.spill[category]
    }
    if (parserMismatch) totals.refacet.rescannedSources++
    ['metadataRefreshes', 'pathMoves', 'canonicalUnchanged'].each { String metric ->
        totals.refacet[metric] += sourceCounts.refacet[metric]
    }
    boolean sourceReconciled = ['turns', 'artifacts', 'dross'].every { String tier ->
        sourceCounts.raw[tier] == sourceCounts.unique[tier] + sourceCounts.duplicate[tier]
    }
    println "${sourcePath}\t${fullRescan ? 'FULL_RESCAN' : 'PROCESSED'}\t${reportFields(sourceCounts)}\t${refacetFields(sourceCounts.refacet as Map)}\treconciled=${sourceReconciled}"

    if (!dryRun) {
        SweepState.record(sweepState, sourcePath, source)
        (sweepState.sources[sourcePath] as Map).full_hash = fullHash
        sourceStateChanged = true
    }
    knownFileHashes << fullHash
}

if (journalStore) {
    journalStore.close()
}
if (!dryRun && sourceStateChanged) {
    TurnEmitter.saveHashIndex(indexFile, hashIndex)
    SweepState.save(stateFile, sweepState)
}
boolean digestUpdated = false
if (!dryRun && !deferDigest && (sourceStateChanged || !new File(outputRoot, Config.CHAT_RESUME_DIGEST_NAME).exists())) {
    ResumeDigest.write(outputRoot, Config.RESUME_BUDGET_BYTES)
    digestUpdated = true
}

boolean reconciled = ['turns', 'artifacts', 'dross'].every { String tier ->
    totals.raw[tier] == totals.unique[tier] + totals.duplicate[tier]
}
double elapsedMs = (System.nanoTime() - started) / 1_000_000.0
printf "TOTAL\t%s\trefacet_rescanned_sources=%d\tmetadata_refreshes=%d\tpath_moves=%d\tcanonical_unchanged=%d\tskipped_unchanged=%d\tskipped_duplicate_hash=%d\treconciled=%s\telapsed_ms=%.2f\tdry_run=%s\tdigest_updated=%s",
    reportFields(totals), totals.refacet.rescannedSources, totals.refacet.metadataRefreshes, totals.refacet.pathMoves,
    totals.refacet.canonicalUnchanged, totals.skippedUnchanged, totals.skippedDuplicate, reconciled, elapsedMs, dryRun, digestUpdated
if (journalBackfill) {
    printf "\tjournal_backfill=%s\tjournal_sources=%d\tjournal_snapshots=%d\tjournal_observations=%d\tjournal_idempotent=%d\tjournal_unparsed_ranges=%d",
        !dryRun, journalTotals.sources, journalTotals.snapshots, journalTotals.observations, journalTotals.idempotent, journalTotals.unparsed_ranges
}
println()

/**
 * Returns a normalized path relative to the workspace.
 *
 * @param root Workspace root
 * @param file Source file
 * @return Relative path
 */
static String relativePath(File root, File file) {
    return root.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
}

static boolean isPromptJournalName(String name) {
    return name == 'PR.log' || name ==~ /(?i).*prompt\.log(?:\.(?:old|new))?$/
}

static String reportFields(Map counts) {
    List<String> fields = []
    ['turns', 'artifacts', 'dross'].each { String tier ->
        fields << "raw_${tier}=${counts.raw[tier]}"
        fields << "unique_${tier}=${counts.unique[tier]}"
        fields << "duplicate_${tier}=${counts.duplicate[tier]}"
    }
    fields << "spill_raw=${counts.spill.raw}"
    fields << "spill_unique=${counts.spill.unique}"
    fields << "spill_duplicate=${counts.spill.duplicate}"
    return fields.join('\t')
}

static void recordRefacetResult(Map counts, Map result) {
    if (!result.containsKey('refreshed')) return
    if (result.metadataChanged) counts.metadataRefreshes++
    if (result.moved) counts.pathMoves++
    if (!result.metadataChanged && !result.moved) counts.canonicalUnchanged++
}

static String refacetFields(Map counts) {
    return [
        "metadata_refreshes=${counts.metadataRefreshes}",
        "path_moves=${counts.pathMoves}",
        "canonical_unchanged=${counts.canonicalUnchanged}"
    ].join('\t')
}

/**
 * Computes a full-file SHA-256 hash.
 *
 * @param file Source file
 * @return Lowercase hexadecimal hash
 */
static String sha256(File file) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    file.withInputStream { InputStream input ->
        byte[] buffer = new byte[8192]
        int read
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().collect { String.format('%02x', it & 0xff) }.join()
}
