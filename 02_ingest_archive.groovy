/**
 * 02_ingest_archive.groovy
 *
 * Phase 2 + Phase 4: Archive Ingestion Engine
 *
 * Reads archives from the configured archive directory, extracts text content from all
 * indexable files using ContentExtractor, indexes them into SQLite FTS5 via
 * MemoryEngine, and automatically compresses them in-place with zlib + VACUUM.
 *
 * Usage:
 *   groovy run.groovy ingest [archive_name] [--force] [--uncompressed]
 *   groovy run.groovy ingest all            [--force] [--uncompressed]
 *
 * Arguments & Flags:
 *   archive_name    - Single archive (e.g. sample.zip) or 'all' for all archives in archive directory
 *   --force, -f     - Purges existing documents for the archive before re-ingesting
 *   --uncompressed  - Skips automatic post-ingestion in-place compression
 */

Config.ensureDataDir()

// -----------------------------------------------------------------------
// CLI Argument Parsing
// -----------------------------------------------------------------------

boolean force = false
boolean autoCompress = true
List<String> targetNames = []
File targetDir = null
String asName = null
String conflictMode = 'replace' // replace, rename, merge, skip
String streamMode = null
String appendText = null
String targetFilePath = null

int argIdx = 0
while (argIdx < args.length) {
    String a = args[argIdx]
    if (a == '--force' || a == '-f') {
        force = true
    } else if (a == '--uncompressed') {
        autoCompress = false
    } else if (a == '--compress' || a == '-c') {
        autoCompress = true
    } else if ((a == '--dir' || a == '-d') && argIdx + 1 < args.length) {
        targetDir = new File(args[argIdx + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')).canonicalFile
        argIdx += 2
        continue
    } else if (a.startsWith('--dir=')) {
        targetDir = new File(a.substring('--dir='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')).canonicalFile
    } else if ((a == '--as' || a == '--name') && argIdx + 1 < args.length) {
        asName = args[argIdx + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')
        argIdx += 2
        continue
    } else if (a.startsWith('--as=')) {
        asName = a.substring('--as='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')
    } else if ((a == '--on-conflict' || a == '--conflict') && argIdx + 1 < args.length) {
        conflictMode = args[argIdx + 1].trim().toLowerCase()
        argIdx += 2
        continue
    } else if (a.startsWith('--on-conflict=')) {
        conflictMode = a.substring('--on-conflict='.length()).trim().toLowerCase()
    } else if (a == 'stream' || a == '--stream') {
        streamMode = 'stream'
    } else if (a == 'append' || a == '--append') {
        streamMode = 'append'
    } else if ((a == '--text' || a == '-t') && argIdx + 1 < args.length) {
        appendText = args[argIdx + 1]
        argIdx += 2
        continue
    } else if (a.startsWith('--text=')) {
        appendText = a.substring('--text='.length())
    } else if ((a == '--file' || a == '-F') && argIdx + 1 < args.length) {
        targetFilePath = args[argIdx + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')
        argIdx += 2
        continue
    } else if (a.startsWith('--file=')) {
        targetFilePath = a.substring('--file='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')
    } else if (!a.startsWith('-')) {
        targetNames << a
    }
    argIdx++
}

MemoryEngine engine = new MemoryEngine(Config.DB_PATH.absolutePath)

// -----------------------------------------------------------------------
// Stream / Append Ingestion Modes
// -----------------------------------------------------------------------
if (streamMode == 'stream' || streamMode == 'append') {
    String archiveName = asName ?: (targetNames.isEmpty() ? 'stream_logs' : targetNames[0])
    String filePath = targetFilePath ?: 'stream.log'
    String fileName = filePath.contains('/') ? filePath.substring(filePath.lastIndexOf('/') + 1) : (filePath.contains('\\') ? filePath.substring(filePath.lastIndexOf('\\') + 1) : filePath)
    int dotIdx = fileName.lastIndexOf('.')
    String ext = (dotIdx >= 0) ? fileName.substring(dotIdx).toLowerCase() : '.log'

    String contentToIngest = appendText
    if (contentToIngest == null) {
        // Read from System.in
        contentToIngest = System.in.text
    }

    if (contentToIngest == null || contentToIngest.trim().isEmpty()) {
        println "No content provided to stream/append. Exiting."
        System.exit(0)
    }

    println "=" * 70
    println "Memory Database - Real-Time Stream / Append Ingestion"
    println "=" * 70
    println "Target Database : ${Config.DB_PATH.absolutePath}"
    println "Archive Target  : ${archiveName}"
    println "File Path       : ${filePath}"
    println "Payload Size    : ${formatSize(contentToIngest.length())}"
    println ""

    long docId = engine.appendDocument(archiveName, filePath, fileName, ext, contentToIngest)
    engine.optimizeIndex()

    println "Successfully ingested/appended document ID: ${docId} in archive '${archiveName}'."
    println "Content is immediately tokenized and searchable in FTS5."
    engine.close()
    return
}

// -----------------------------------------------------------------------
// Directory Ingestion Mode (--dir / -d)
// -----------------------------------------------------------------------
if (targetDir != null) {
    if (!targetDir.exists() || !targetDir.isDirectory()) {
        println "ERROR: Directory not found: ${targetDir.absolutePath}"
        System.exit(1)
    }

    String archiveName = asName ?: targetDir.name
    println "=" * 70
    println "Memory Database - Directory Ingestion Engine"
    println "=" * 70
    println "Target Database : ${Config.DB_PATH.absolutePath}"
    println "Source Directory: ${targetDir.absolutePath}"
    println "Archive Alias   : ${archiveName}"
    println "Conflict Policy : ${conflictMode}"
    println "=" * 70
    println ""

    boolean alreadyIngested = engine.isArchiveIngested(archiveName)
    if (alreadyIngested) {
        if (conflictMode == 'skip') {
            println "Archive '${archiveName}' already exists. Skipping due to --on-conflict skip."
            engine.close()
            return
        } else if (conflictMode == 'rename') {
            int suffix = 2
            while (engine.isArchiveIngested("${archiveName}_${suffix}")) {
                suffix++
            }
            archiveName = "${archiveName}_${suffix}"
            println "Archive collision resolved. Renamed to: ${archiveName}"
        } else if (conflictMode == 'replace' || force) {
            println "Purging existing records for '${archiveName}' due to replace policy..."
            int purged = engine.purgeArchive(archiveName)
            println "Purged ${purged} old document(s)."
        }
    }

    def entries = ArchiveHandler.listDirectoryEntries(targetDir)
    def indexable = entries.findAll { entry ->
        def strategy = ContentExtractor.classify(entry.extension)
        String sName = strategy.name()
        return sName == 'TEXT_PLAIN' || sName == 'DOCUMENT' || sName == 'UNKNOWN'
    }

    println "Total files: ${entries.size()} | Indexable: ${indexable.size()}"
    long startMs = System.currentTimeMillis()
    int ingested = 0
    long totalBytes = 0

    engine.beginBatch()
    indexable.eachWithIndex { ArchiveEntry entry, int idx ->
        try {
            String content = null
            ArchiveHandler.withDirectoryEntryStream(targetDir, entry.path) { is ->
                content = ContentExtractor.extractText(is, entry.extension, entry.size)
            }
            if (content != null && !content.trim().isEmpty()) {
                engine.insertDocument(archiveName, entry.path, entry.name, entry.extension, entry.size, content)
                ingested++
                totalBytes += content.length()
            }
        } catch (Exception e) {
            // ignore failure
        }
    }
    engine.endBatch()

    String originHash = Integer.toHexString(targetDir.canonicalPath.hashCode())
    engine.recordManifest(archiveName, totalBytes, ingested, totalBytes, targetDir.absolutePath, originHash, null)
    engine.optimizeIndex()

    long elapsed = System.currentTimeMillis() - startMs
    printf "Directory Ingestion Complete: %d documents (%s text) in %.2f s%n", ingested, formatSize(totalBytes), (elapsed / 1000.0)

    if (autoCompress && ingested > 0) {
        print "Compressing ${archiveName} in-place (zlib + VACUUM)... "
        Map compRes = engine.compressDatabase(archiveName)
        printf "Done (Saved %s, Final DB: %s)%n", formatSize(compRes.saved_bytes as long), formatSize(compRes.final_size_bytes as long)
    }

    engine.close()
    return
}

String targetSelector = targetNames.isEmpty() ? 'all' : targetNames[0]

// Determine list of archives to process
List<File> archivesToProcess = []
if (targetSelector.equalsIgnoreCase('all') || targetSelector.equalsIgnoreCase('--all')) {
    Config.ARCHIVE_DIR.eachFile { f ->
        String name = f.name.toLowerCase()
        if (name.endsWith('.zip') || name.endsWith('.rar')) {
            archivesToProcess << f
        }
    }
    archivesToProcess.sort { a, b -> a.length() <=> b.length() } // Process smaller archives first
} else {
    File f = new File(Config.ARCHIVE_DIR, targetSelector)
    if (!f.exists()) {
        println "ERROR: Archive not found: ${f.absolutePath}"
        println "Available archives in ${Config.ARCHIVE_DIR.absolutePath}:"
        Config.ARCHIVE_DIR.eachFile { File af ->
            if (af.name.toLowerCase().endsWith('.zip') || af.name.toLowerCase().endsWith('.rar')) {
                println "  - ${af.name} (${formatSize(af.length())})"
            }
        }
        System.exit(1)
    }
    archivesToProcess << f
}

println "=" * 70
println "Memory Database - Archive Ingestion Engine"
println "=" * 70
println "Target Database : ${Config.DB_PATH.absolutePath}"
println "Archives Queue  : ${archivesToProcess.size()} archive(s)"
println "Auto-Compress   : ${autoCompress ? 'ENABLED (zlib in-place + VACUUM)' : 'DISABLED'}"
println "Force Re-ingest : ${force ? 'YES (purge existing)' : 'NO (skip already ingested)'}"
println "=" * 70
println ""
long globalStart = System.currentTimeMillis()
int totalIngestedAll = 0
int totalSkippedArchives = 0

archivesToProcess.eachWithIndex { File archive, int archiveIdx ->
    String archiveName = archive.name
    println "[${archiveIdx + 1}/${archivesToProcess.size()}] Processing: ${archiveName} (${formatSize(archive.length())})"

    // Idempotent check
    boolean alreadyIngested = engine.isArchiveIngested(archiveName)
    if (alreadyIngested) {
        if (!force) {
            println "  Archive '${archiveName}' is already ingested in database. Skipping (use --force to re-ingest)."
            println ""
            totalSkippedArchives++
            return
        } else {
            println "  Archive '${archiveName}' already exists. Purging existing records due to --force..."
            int purged = engine.purgeArchive(archiveName)
            println "  Purged ${purged} old document(s)."
        }
    }

    // Step 1: List and filter entries
    List<ArchiveEntry> entries = []
    try {
        entries = ArchiveHandler.listEntries(archive)
    } catch (Exception e) {
        println "  ERROR: Failed to list entries from ${archiveName}: ${e.message}"
        return
    }

    def indexable = entries.findAll { entry ->
        def strategy = ContentExtractor.classify(entry.extension)
        String sName = strategy.name()
        return sName == 'TEXT_PLAIN' || sName == 'DOCUMENT' || sName == 'UNKNOWN'
    }

    println "  Total entries: ${entries.size()} | Indexable text files: ${indexable.size()} | Binary skipped: ${entries.size() - indexable.size()}"

    if (indexable.isEmpty()) {
        println "  No indexable files found in archive."
        println ""
        return
    }

    // Step 2: Extract & Insert in batch
    long archiveStart = System.currentTimeMillis()
    int ingested = 0
    int failed = 0
    int nullContent = 0
    long totalBytes = 0

    // Accelerate damaged ZIPs and RAR archives via single-pass unpack
    ArchiveHandler.prepareArchive(archive)

    try {
        engine.beginBatch()

        indexable.eachWithIndex { ArchiveEntry entry, int idx ->
            try {
                String content = null
                ArchiveHandler.withEntryStream(archive, entry.path) { InputStream stream ->
                    content = ContentExtractor.extractText(stream, entry.extension, entry.size)
                }

                if (content != null && !content.trim().isEmpty()) {
                    engine.insertDocument(
                        archiveName,
                        entry.path,
                        entry.name,
                        entry.extension,
                        entry.size,
                        content
                    )
                    ingested++
                    totalBytes += content.length()
                } else {
                    nullContent++
                }
            } catch (Exception e) {
                failed++
                if (failed <= 3) {
                    println "\n    WARN: Failed to extract ${entry.path}: ${e.message}"
                }
            }

            // Progress bar reporting
            if ((idx + 1) % 250 == 0 || idx == indexable.size() - 1) {
                long el = System.currentTimeMillis() - archiveStart
                double rate = (idx + 1) / Math.max(0.001, (el / 1000.0))
                printf "\r  Extracting & Indexing: %d / %d files (%.0f files/sec)...", (idx + 1), indexable.size(), rate
            }
        }

        engine.endBatch()
        println ""
    } finally {
        ArchiveHandler.cleanupArchive(archive)
    }

    // Step 3: Record manifest & optimize index
    engine.recordManifest(archiveName, archive.length(), ingested, totalBytes)
    engine.optimizeIndex()

    long archiveElapsed = System.currentTimeMillis() - archiveStart
    printf "  Ingested %d documents (%s text) in %.2f s%n", ingested, formatSize(totalBytes), (archiveElapsed / 1000.0)

    // Step 4: Auto in-place compression
    if (autoCompress && ingested > 0) {
        print "  Compressing ${archiveName} in-place (zlib + VACUUM)... "
        Map compRes = engine.compressDatabase(archiveName)
        printf "Done (Saved %s, Final DB: %s)%n",
            formatSize(compRes.saved_bytes as long), formatSize(compRes.final_size_bytes as long)
    }

    totalIngestedAll += ingested
    println ""
}

long globalElapsed = System.currentTimeMillis() - globalStart

println "=" * 70
println "BATCH INGESTION COMPLETE"
println "=" * 70
printf "Total Time Elapsed    : %.2f s%n", (globalElapsed / 1000.0)
println "Total Documents Added : ${totalIngestedAll}"
println "Skipped Archives      : ${totalSkippedArchives}"
println ""

// Display final stats
Map finalStats = engine.getStats()
long dbFileSizeBytes = Config.DB_PATH.exists() ? Config.DB_PATH.length() : 0
println "Final Database State:"
println "  Database Path       : ${Config.DB_PATH.absolutePath}"
println "  Disk File Size      : ${formatSize(dbFileSizeBytes)}"
println "  Total Documents     : ${finalStats.total_documents}"
println "  Total Content Size  : ${formatSize(finalStats.total_size_bytes as long)}"
println ""

if (finalStats.manifest) {
    println "Ingested Archives Manifest:"
    finalStats.manifest.each { m ->
        String compState = m.compression_state ?: 'uncompressed'
        printf "  - %-25s %6d docs | %9s text | %-12s | archive %9s | %s%n",
            m.source_archive, m.ingested_documents, formatSize(m.total_text_bytes as long),
            compState, formatSize(m.archive_size_bytes as long), m.ingested_at
    }
}

engine.close()
println "=" * 70

/**
 * Formats a byte count into a human-readable size string.
 *
 * @param bytes Size in bytes
 * @return Formatted string (e.g., "12.5 MB")
 */
static String formatSize(long bytes) {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
