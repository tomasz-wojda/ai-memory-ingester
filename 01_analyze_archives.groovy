import groovy.json.JsonOutput

/**
 * 01_analyze_archives.groovy
 *
 * Phase 1: Archive Analysis Script
 *
 * Scans all ZIP and RAR archives in the configured archive directory, catalogues their
 * file entries by extension, classifies each into an extraction strategy,
 * and produces both a console summary and a JSON report.
 *
 * Run: groovy 01_analyze_archives.groovy
 * Output: data/archive_analysis.json + console summary
 */

// Classes loaded by run.groovy: Config, ArchiveHandler, ContentExtractor, MemoryEngine

File targetDir = null
int aIdx = 0
while (aIdx < args.length) {
    String a = args[aIdx]
    if ((a == '--dir' || a == '-d') && aIdx + 1 < args.length) {
        targetDir = new File(args[aIdx + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')).canonicalFile
        aIdx += 2
    } else if (a.startsWith('--dir=')) {
        targetDir = new File(a.substring('--dir='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')).canonicalFile
        aIdx++
    } else {
        aIdx++
    }
}

File sourceRoot = targetDir ?: Config.ARCHIVE_DIR

println "=" * 70
println "Archive Analyzer"
println "=" * 70
println "Source location: ${sourceRoot.absolutePath}"
println ""

if (targetDir) {
    if (!targetDir.exists() || !targetDir.isDirectory()) {
        println "ERROR: Directory not found: ${targetDir.absolutePath}"
        System.exit(1)
    }

    println "Analyzing directory: ${targetDir.name}"
    def entries = ArchiveHandler.listDirectoryEntries(targetDir)
    println "  Total files found: ${entries.size()}"
    println ""
    def byExtension = entries.groupBy { it.extension }
    printf "  %-25s %6s  %s%n", "Extension", "Count", "Strategy"
    printf "  %-25s %6s  %s%n", "-" * 25, "-" * 6, "-" * 15
    byExtension.sort { -it.value.size() }.each { ext, entryList ->
        String displayExt = ext ?: '(no extension)'
        def strategy = ContentExtractor.classify(ext)
        String stratLabel = strategy.name().replace('_', ' ')
        printf "  %-25s %6d  %s%n", displayExt, entryList.size(), stratLabel
    }
    println ""
    return
}

// Collect all archive files (ZIP + RAR)
List<File> archives = []
Config.ARCHIVE_DIR.eachFile { file ->
    String ext = file.name.toLowerCase()
    if (ext.endsWith('.zip') || ext.endsWith('.rar')) {
        archives << file
    }
}

if (archives.isEmpty()) {
    println "ERROR: No archives found in ${Config.ARCHIVE_DIR.absolutePath}"
    System.exit(1)
}

archives.sort { it.name.toLowerCase() }
println "Found ${archives.size()} archive(s):"
archives.each { println "  - ${it.name} (${formatSize(it.length())})" }
println ""

// Analyse each archive
Map<String, Object> report = [
    source_directory: Config.ARCHIVE_DIR.absolutePath,
    analyzed_at:      new Date().format("yyyy-MM-dd'T'HH:mm:ss"),
    archives:         []
]

int totalIndexable = 0
int totalSkipped = 0
int totalDeferred = 0
int totalUnknown = 0

archives.each { File archive ->
    println "-" * 70
    println "Analyzing: ${archive.name} (${formatSize(archive.length())})"

    Map archiveReport = [
        name:      archive.name,
        size_bytes: archive.length(),
        status:    'OK',
        entries:   [:],
        summary:   [:]
    ]

    try {
        // List all entries using the unified ArchiveHandler
        def entries = ArchiveHandler.listEntries(archive)

        // Group by extension
        def byExtension = entries.groupBy { it.extension }

        // Classify and count
        int indexable = 0
        int skipped = 0
        int deferred = 0
        int unknown = 0

        Map<String, Integer> extCounts = [:]
        Map<String, String> extStrategies = [:]

        byExtension.sort { -it.value.size() }.each { ext, entryList ->
            String displayExt = ext ?: '(no extension)'
            extCounts[displayExt] = entryList.size()

            def strategy = ContentExtractor.classify(ext)
            extStrategies[displayExt] = strategy.name()

            String sName = strategy.name()
            if (sName == 'TEXT_PLAIN') indexable += entryList.size()
            else if (sName == 'BINARY_SKIP') skipped += entryList.size()
            else if (sName == 'DOCUMENT') deferred += entryList.size()
            else unknown += entryList.size()
        }

        // Console output — extension table
        println "  Total entries: ${entries.size()}"
        println ""
        printf "  %-25s %6s  %s%n", "Extension", "Count", "Strategy"
        printf "  %-25s %6s  %s%n", "-" * 25, "-" * 6, "-" * 15
        byExtension.sort { -it.value.size() }.each { ext, entryList ->
            String displayExt = ext ?: '(no extension)'
            def strategy = ContentExtractor.classify(ext)
            String stratLabel = strategy.name().replace('_', ' ')
            printf "  %-25s %6d  %s%n", displayExt, entryList.size(), stratLabel
        }
        println ""
        println "  Summary: ${indexable} indexable | ${skipped} binary (skip) | ${deferred} document (deferred) | ${unknown} unknown"

        totalIndexable += indexable
        totalSkipped += skipped
        totalDeferred += deferred
        totalUnknown += unknown

        archiveReport.entries = extCounts
        archiveReport.summary = [
            total_entries: entries.size(),
            indexable:     indexable,
            binary_skip:   skipped,
            document_deferred: deferred,
            unknown:       unknown
        ]

    } catch (Exception e) {
        println "  ERROR: ${e.message}"
        archiveReport.status = 'ERROR'
        archiveReport.error = e.message
    }

    report.archives << archiveReport
    println ""
}

// Aggregate summary
println "=" * 70
println "AGGREGATE SUMMARY"
println "=" * 70
println "  Total indexable (text):     ${totalIndexable}"
println "  Total binary (skip):        ${totalSkipped}"
println "  Total document (deferred):  ${totalDeferred}"
println "  Total unknown:              ${totalUnknown}"
println "  Grand total:                ${totalIndexable + totalSkipped + totalDeferred + totalUnknown}"
println ""

// Write JSON report
report.aggregate = [
    total_indexable:       totalIndexable,
    total_binary_skip:     totalSkipped,
    total_document_deferred: totalDeferred,
    total_unknown:         totalUnknown,
    grand_total:           totalIndexable + totalSkipped + totalDeferred + totalUnknown
]

File reportFile = new File(Config.DATA_DIR, 'archive_analysis.json')
reportFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(report))
println "Report saved: ${reportFile.absolutePath}"

// -----------------------------------------------------------------------
// Utility
// -----------------------------------------------------------------------

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
