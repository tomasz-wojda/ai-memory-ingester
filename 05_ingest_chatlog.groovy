import groovy.io.FileType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Transforms workspace prompt journals into deduplicated, faceted Markdown memory files.
 */

boolean allSources = false
boolean sinceLast = false
boolean dryRun = false
String explicitWorkspace = null
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
    skippedUnchanged: 0,
    skippedDuplicate: 0
]
long started = System.nanoTime()

sources.each { File source ->
    String sourcePath = relativePath(workspaceRoot, source)
    String fullHash = sha256(source)
    Map record = (sweepState.sources as Map)?.get(sourcePath) as Map
    boolean unchanged = record && record.full_hash == fullHash && (record.byte_offset as long) == source.length()
    boolean duplicateFile = knownFileHashes.contains(fullHash) && !unchanged

    if (unchanged || duplicateFile) {
        if (unchanged) totals.skippedUnchanged++ else totals.skippedDuplicate++
        String reason = unchanged ? 'unchanged hash' : 'duplicate hash'
        println "${sourcePath}\tSKIPPED\t${reason}"
        if (!dryRun && duplicateFile) {
            SweepState.record(sweepState, sourcePath, source)
            (sweepState.sources[sourcePath] as Map).full_hash = fullHash
        }
        return
    }

    long startOffset = 0L
    boolean fullRescan = false
    if (sinceLast && record) {
        fullRescan = SweepState.needsFullRescan(source, record)
        startOffset = fullRescan ? 0L : SweepState.offsetFor(sweepState, sourcePath)
    }

    List<Map> entries = ChatLogParser.parseFile(source, sourcePath, startOffset)
    entries.each { Map entry -> entry.timestampRepair = TimestampRepair.parse(entry.rawTimestamp?.toString()) }
    TimestampRepair.interpolate(entries)

    Map sourceCounts = [
        raw: [turns: 0, artifacts: 0, dross: 0],
        unique: [turns: 0, artifacts: 0, dross: 0],
        duplicate: [turns: 0, artifacts: 0, dross: 0],
        spill: [raw: 0, unique: 0, duplicate: 0]
    ]
    entries.each { Map parsed ->
        Map entry = TurnClassifier.classify(parsed, spillBytes)
        entry.turnHash = TurnDocument.hash(entry)
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
                outputRoot, artifactEntry, 'artifacts', currentSequence, hashIndex, null, dryRun
            )
            if (artifactResult.emitted) sourceCounts.spill.unique++ else sourceCounts.spill.duplicate++
            String spillPath = artifactResult.path?.toString()
            Map turnResult = TurnEmitter.emit(
                outputRoot, entry, classifiedTier, currentSequence, hashIndex, spillPath, dryRun
            )
            if (turnResult.emitted) sourceCounts.unique[classifiedTier]++ else sourceCounts.duplicate[classifiedTier]++
            if (artifactResult.emitted || turnResult.emitted) sequence++
        } else {
            Map result = TurnEmitter.emit(outputRoot, entry, classifiedTier, currentSequence, hashIndex, null, dryRun)
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
    boolean sourceReconciled = ['turns', 'artifacts', 'dross'].every { String tier ->
        sourceCounts.raw[tier] == sourceCounts.unique[tier] + sourceCounts.duplicate[tier]
    }
    println "${sourcePath}\t${fullRescan ? 'FULL_RESCAN' : 'PROCESSED'}\t${reportFields(sourceCounts)}\treconciled=${sourceReconciled}"

    if (!dryRun) {
        SweepState.record(sweepState, sourcePath, source)
        (sweepState.sources[sourcePath] as Map).full_hash = fullHash
    }
    knownFileHashes << fullHash
}

if (!dryRun) {
    TurnEmitter.saveHashIndex(indexFile, hashIndex)
    SweepState.save(stateFile, sweepState)
    ResumeDigest.write(outputRoot, Config.RESUME_BUDGET_BYTES)
}

boolean reconciled = ['turns', 'artifacts', 'dross'].every { String tier ->
    totals.raw[tier] == totals.unique[tier] + totals.duplicate[tier]
}
double elapsedMs = (System.nanoTime() - started) / 1_000_000.0
printf "TOTAL\t%s\tskipped_unchanged=%d\tskipped_duplicate_hash=%d\treconciled=%s\telapsed_ms=%.2f\tdry_run=%s%n",
    reportFields(totals), totals.skippedUnchanged, totals.skippedDuplicate, reconciled, elapsedMs, dryRun

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
