/**
 * 04_compress_memory.groovy
 *
 * In-place database compression and decompression driver for the Archive Memory Context system.
 * Allows toggling between zlib compressed BLOB storage and raw plaintext storage without
 * requiring the original ZIP/RAR archive files.
 *
 * Invoked via run.groovy:
 *   groovy run.groovy compress
 *   groovy run.groovy decompress
 *   groovy run.groovy uncompress
 */

// MemoryEngine and Config are loaded via classloader in run.groovy
if (!Config.DB_PATH.exists()) {
    println "Error: Memory database not found at ${Config.DB_PATH.absolutePath}"
    println "Run 'groovy run.groovy ingest' first."
    System.exit(1)
}

// Determine target action: check command passed or CLI args
String action = 'compress'
String targetArchive = null

if (binding.hasVariable('commandName')) {
    action = binding.getVariable('commandName').toString().toLowerCase()
}

if (args && args.length > 0) {
    if (args[0].equalsIgnoreCase('compress') || args[0].equalsIgnoreCase('decompress') || args[0].equalsIgnoreCase('uncompress')) {
        action = args[0].toLowerCase()
        if (args.length > 1) {
            targetArchive = args[1].trim()
        }
    } else {
        targetArchive = args[0].trim()
    }
}

MemoryEngine engine = new MemoryEngine(Config.DB_PATH.absolutePath)

println "=" * 70
if (action == 'decompress' || action == 'uncompress') {
    println "Memory Database - In-Place Decompression"
    println "=" * 70
    println "Target Database : ${Config.DB_PATH.absolutePath}"
    println "Target Archive  : ${targetArchive ?: 'ALL ARCHIVES'}"
    println "Operation       : Inflating compressed documents to plaintext UTF-8"
    println ""

    Map res = engine.decompressDatabase(targetArchive) { int processed, int total ->
        printf "\rDecompressing: %d / %d documents (%.1f%%)...", processed, total, (processed * 100.0 / total)
    }
    println ""
    println ""
    println "Decompression Complete:"
    println "  Target Archive      : ${targetArchive ?: 'ALL ARCHIVES'}"
    println "  Documents Processed : ${res.processed_count}"
    println "  Initial Disk Size   : ${formatSize(res.initial_size_bytes as long)}"
    println "  Final Disk Size     : ${formatSize(res.final_size_bytes as long)}"
    printf "  Elapsed Time        : %.2f s%n", (res.elapsed_ms / 1000.0)
} else {
    println "Memory Database - In-Place Compression"
    println "=" * 70
    println "Target Database : ${Config.DB_PATH.absolutePath}"
    println "Target Archive  : ${targetArchive ?: 'ALL ARCHIVES'}"
    println "Operation       : Deflating documents with zlib compression (VACUUM)"
    println ""

    Map res = engine.compressDatabase(targetArchive) { int processed, int total ->
        printf "\rCompressing: %d / %d documents (%.1f%%)...", processed, total, (processed * 100.0 / total)
    }
    println ""
    println ""
    println "Compression Complete:"
    println "  Target Archive      : ${targetArchive ?: 'ALL ARCHIVES'}"
    println "  Documents Processed : ${res.processed_count}"
    println "  Initial Disk Size   : ${formatSize(res.initial_size_bytes as long)}"
    println "  Final Disk Size     : ${formatSize(res.final_size_bytes as long)}"
    if (res.saved_bytes > 0) {
        double savedPct = (res.saved_bytes * 100.0) / (res.initial_size_bytes as double)
        println "  Disk Space Saved    : ${formatSize(res.saved_bytes as long)} (${String.format('%.1f%%', savedPct)} reduction)"
    }
    printf "  Elapsed Time        : %.2f s%n", (res.elapsed_ms / 1000.0)
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
