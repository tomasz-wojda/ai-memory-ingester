/**
 * 03_query_memory.groovy
 *
 * Phase 4: Interactive Query CLI
 *
 * Provides a command-line interface for querying the Archive Memory Context
 * database using FTS5 full-text search. Supports both single-shot CLI
 * queries and an interactive REPL mode.
 *
 * Usage:
 *   Interactive: groovy 03_query_memory.groovy
 *   Single-shot: groovy 03_query_memory.groovy "search query"
 *
 * REPL Commands:
 *   <text>        - FTS5 full-text search
 *   :stats        - Display database statistics
 *   :doc <id>     - Display full document content by ID
 *   :ext <.ext>   - List files with given extension
 *   :files <pat>  - List files matching path pattern (SQL LIKE)
 *   :help         - Show command reference
 *   :quit         - Exit the REPL
 */

// Classes loaded by run.groovy: Config, ArchiveHandler, ContentExtractor, MemoryEngine

if (!Config.DB_PATH.exists()) {
    println "ERROR: Database not found at ${Config.DB_PATH.absolutePath}"
    println "Run 02_ingest_archive.groovy first to populate the database."
    System.exit(1)
}

MemoryEngine engine = new MemoryEngine(Config.DB_PATH.absolutePath)

// -----------------------------------------------------------------------
// -----------------------------------------------------------------------
// Single-shot mode: query passed as CLI argument
// -----------------------------------------------------------------------

if (args.length > 0) {
    int limit = 20
    int snippetSize = 64
    int maxLines = 200
    boolean raw = false
    List<String> extFilter = []
    String archiveFilter = null
    List<String> queryTokens = []
    int i = 0
    while (i < args.length) {
        String arg = args[i]
        if ((arg == '--limit' || arg == '-n' || arg == '-l') && i + 1 < args.length) {
            try { limit = args[i + 1].toInteger() } catch (Exception ignored) {}
            i += 2
        } else if (arg.startsWith('--limit=')) {
            try { limit = arg.substring('--limit='.length()).toInteger() } catch (Exception ignored) {}
            i++
        } else if ((arg == '--snippet-size' || arg == '-s') && i + 1 < args.length) {
            try { snippetSize = args[i + 1].toInteger() } catch (Exception ignored) {}
            i += 2
        } else if (arg.startsWith('--snippet-size=')) {
            try { snippetSize = arg.substring('--snippet-size='.length()).toInteger() } catch (Exception ignored) {}
            i++
        } else if (arg == '--no-ext') {
            extFilter.add('')
            i++
        } else if ((arg == '--ext' || arg == '-e' || arg == '--extension') && i + 1 < args.length) {
            extFilter.addAll(args[i + 1].split(',').collect { it.trim() })
            i += 2
        } else if (arg.startsWith('--ext=')) {
            extFilter.addAll(arg.substring('--ext='.length()).split(',').collect { it.trim() })
            i++
        } else if (arg.startsWith('--extension=')) {
            extFilter.addAll(arg.substring('--extension='.length()).split(',').collect { it.trim() })
            i++
        } else if ((arg == '--archive' || arg == '-A') && i + 1 < args.length) {
            archiveFilter = args[i + 1].trim()
            i += 2
        } else if (arg.startsWith('--archive=')) {
            archiveFilter = arg.substring('--archive='.length()).trim()
            i++
        } else if ((arg == '--lines' || arg == '-L') && i + 1 < args.length) {
            try { maxLines = args[i + 1].toInteger() } catch (Exception ignored) {}
            i += 2
        } else if (arg.startsWith('--lines=')) {
            try { maxLines = arg.substring('--lines='.length()).toInteger() } catch (Exception ignored) {}
            i++
        } else if (arg == '--all' || arg == '-a') {
            maxLines = Integer.MAX_VALUE
            i++
        } else if (arg == '--raw' || arg == '-r') {
            raw = true
            i++
        } else {
            queryTokens << arg
            i++
        }
    }

    String input = queryTokens.join(' ').trim()
    if (input == ':stats') {
        printStats(engine)
    } else if (input == ':files' || input.startsWith(':files ') || input.startsWith(':files')) {
        String pattern = input.startsWith(':files ') ? input.substring(':files '.length()).trim() : (input.length() > 6 ? input.substring(6).trim() : '%')
        executeFileList(engine, pattern.isEmpty() ? '%' : pattern, limit)
    } else if (input.startsWith(':doc ') || input.startsWith(':doc')) {
        String idStr = input.startsWith(':doc ') ? input.substring(':doc '.length()).trim() : input.substring(4).trim()
        executeDocView(engine, idStr, raw, maxLines)
    } else if (input == ':ext' || input.startsWith(':ext ') || input.startsWith(':ext')) {
        String ext = input.startsWith(':ext ') ? input.substring(':ext '.length()).trim() : (input.length() > 4 ? input.substring(4).trim() : '')
        executeExtList(engine, ext, limit)
    } else if (input == ':help' || input == ':h') {
        printHelp()
    } else if (input.startsWith(':')) {
        println "Unknown command: ${input}. Type :help for available commands."
    } else if (!input.isEmpty()) {
        executeSearch(engine, input, limit, raw, snippetSize, extFilter, archiveFilter)
    }
    engine.close()
    System.exit(0)
}

// -----------------------------------------------------------------------
// Interactive REPL mode
// -----------------------------------------------------------------------

println "=" * 70
println "Memory Query Engine"
println "=" * 70
printStats(engine)
println ""
println "Type a search query, or :help for commands. :quit to exit."
println ""

BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
String line
int replDefaultLimit = 20
int replDefaultSnippetSize = 64

while (true) {
    print "memory> "
    line = reader.readLine()

    // Handle EOF (Ctrl+D / Ctrl+Z)
    if (line == null) {
        println ""
        break
    }

    line = line.trim()
    if (line.isEmpty()) continue

    // Check for inline flags in REPL
    int limit = replDefaultLimit
    int snippetSize = replDefaultSnippetSize
    if (line.contains('--') || line.contains(' -')) {
        def parts = line.split('\\s+')
        List<String> cleanParts = []
        int i = 0
        while (i < parts.length) {
            if ((parts[i] == '--limit' || parts[i] == '-n') && i + 1 < parts.length) {
                try { limit = parts[i + 1].toInteger() } catch (Exception ignored) {}
                i += 2
            } else if ((parts[i] == '--snippet-size' || parts[i] == '-s') && i + 1 < parts.length) {
                try { snippetSize = parts[i + 1].toInteger() } catch (Exception ignored) {}
                i += 2
            } else {
                cleanParts << parts[i]
                i++
            }
        }
        line = cleanParts.join(' ').trim()
    }

    // Command dispatch
    if (line == ':quit' || line == ':q') {
        break
    } else if (line == ':help' || line == ':h') {
        printHelp()
    } else if (line == ':stats') {
        printStats(engine)
    } else if (line.startsWith(':limit ')) {
        try {
            replDefaultLimit = line.substring(':limit '.length()).trim().toInteger()
            println "Default result limit set to: ${replDefaultLimit}"
        } catch (Exception e) {
            println "Invalid limit number."
        }
    } else if (line.startsWith(':snippet ')) {
        try {
            replDefaultSnippetSize = line.substring(':snippet '.length()).trim().toInteger()
            println "Default snippet size set to: ${replDefaultSnippetSize} tokens"
        } catch (Exception e) {
            println "Invalid snippet size number."
        }
    } else if (line == ':files' || line.startsWith(':files ') || line.startsWith(':files')) {
        String pattern = line.startsWith(':files ') ? line.substring(':files '.length()).trim() : (line.length() > 6 ? line.substring(6).trim() : '%')
        executeFileList(engine, pattern.isEmpty() ? '%' : pattern, limit)
    } else if (line.startsWith(':doc ') || line.startsWith(':doc')) {
        String sub = line.startsWith(':doc ') ? line.substring(':doc '.length()).trim() : line.substring(4).trim()
        boolean isRaw = false
        int docMaxLines = 200
        if (sub.contains('--all') || sub.contains(' -a')) {
            docMaxLines = Integer.MAX_VALUE
            sub = sub.replaceAll(' --all|-a', '').trim()
        }
        if (sub.contains('--lines ') || sub.contains(' -L ')) {
            def parts = sub.split('\\s+')
            List<String> clean = []
            int j = 0
            while (j < parts.length) {
                if ((parts[j] == '--lines' || parts[j] == '-L') && j + 1 < parts.length) {
                    try { docMaxLines = parts[j + 1].toInteger() } catch (Exception ignored) {}
                    j += 2
                } else {
                    clean << parts[j]
                    j++
                }
            }
            sub = clean.join(' ').trim()
        }
        if (sub.endsWith(' --raw') || sub.endsWith(' -r')) {
            isRaw = true
            sub = sub.replaceAll(' --raw|-r', '').trim()
        }
        executeDocView(engine, sub, isRaw, docMaxLines)
    } else if (line == ':ext' || line.startsWith(':ext ') || line.startsWith(':ext')) {
        String ext = line.startsWith(':ext ') ? line.substring(':ext '.length()).trim() : (line.length() > 4 ? input.substring(4).trim() : '')
        executeExtList(engine, ext, limit)
    } else if (line.startsWith(':')) {
        println "Unknown command: ${line}. Type :help for available commands."
    } else {
        executeSearch(engine, line, limit, false, snippetSize)
    }

    println ""
}

engine.close()
println "Session ended."

// -----------------------------------------------------------------------
// Command implementations
// -----------------------------------------------------------------------

/**
 * Executes an FTS5 search query and displays ranked results with snippets
 * along with high-precision query retrieval latency in milliseconds.
 *
 * @param engine        MemoryEngine instance
 * @param query         FTS5 match expression
 * @param limit         Maximum number of results (default: 20)
 * @param raw           If true, outputs raw snippets without gutter formatting
 * @param snippetSize   Maximum token length for the excerpt window (default: 64)
 * @param extensions    Optional list of file extensions to filter by
 * @param archiveFilter Optional archive name to filter by
 */
static void executeSearch(MemoryEngine engine, String query, int limit = 20, boolean raw = false, int snippetSize = 64, List<String> extensions = null, String archiveFilter = null) {
    if (!raw) {
        println "-" * 50
        StringBuilder header = new StringBuilder("Searching: \"${query}\"")
        if (extensions != null && !extensions.isEmpty()) {
            List<String> displayExts = extensions.collect { ext ->
                String clean = ext ? ext.trim().replaceAll("^['\"]+|['\"]+\$", '') : ''
                (clean.isEmpty() || clean == 'none' || clean == 'empty') ? '(no ext)' : clean
            }
            header.append(" [ext: ").append(displayExts.join(', ')).append("]")
        }
        if (archiveFilter != null && !archiveFilter.isEmpty()) {
            header.append(" [archive: ").append(archiveFilter).append("]")
        }
        if (snippetSize != 64) {
            header.append(" (limit: ${limit}, snippet-size: ${snippetSize})")
        } else {
            header.append(" (limit: ${limit})")
        }
        println header.toString()
        println "-" * 50
    }

    try {
        long startNanos = System.nanoTime()
        List<Map> results = engine.search(query, limit, snippetSize, extensions, archiveFilter)
        long elapsedNanos = System.nanoTime() - startNanos
        double elapsedMs = elapsedNanos / 1_000_000.0

        if (results.isEmpty()) {
            printf "No results found (%.2f ms).%n", elapsedMs
            if (!raw) {
                println ""
                println "Tips:"
                println "  - Use * for prefix matching:  Address*"
                println "  - Use quotes for phrases:     \"CREATE TABLE\""
                println "  - Use column filters:         file_name:Main"
            }
            return
        }

        results.eachWithIndex { Map result, int idx ->
            printf "[%d] ID:%d  %s%n", (idx + 1), result.id, result.file_path
            printf "    Archive: %s  |  Type: %s%n", result.source_archive, result.extension
            String snippet = (result.snippet ?: '').toString()
            snippet = snippet.replaceAll('\\s+', ' ').trim()
            if (snippet.length() > 240) {
                snippet = snippet.substring(0, 240) + '...'
            }
            if (snippet) {
                printHangingSnippet(snippet, 95)
            }
            println ""
        }

        if (!raw) {
            println "-" * 50
        }
        printf "Found %d result(s) in %.2f ms%n", results.size(), elapsedMs
    } catch (Exception e) {
        println "Search error: ${e.message}"
        println "Ensure your query uses valid FTS5 syntax."
    }
}

/**
 * Displays full content of a document by its ID.
 *
 * @param engine   MemoryEngine instance
 * @param idStr    String representation of the document ID
 * @param raw      If true, outputs pure document text without headers or line numbering
 * @param maxLines Maximum number of lines to display in formatted mode (default: 200)
 */
static void executeDocView(MemoryEngine engine, String idStr, boolean raw = false, int maxLines = 200) {
    try {
        int id = idStr.toInteger()
        long startNanos = System.nanoTime()
        Map doc = engine.getDocument(id)
        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0

        if (doc == null) {
            printf "Document ID %d not found (%.2f ms).%n", id, elapsedMs
            return
        }

        if (raw) {
            println(doc.content ?: '')
            return
        }

        println "=" * 70
        println "Document ID:  ${doc.id}"
        println "Archive:      ${doc.source_archive}"
        println "Path:         ${doc.file_path}"
        println "Extension:    ${doc.extension}"
        println "Size:         ${doc.size_bytes} bytes"
        println "Compression:  ${doc.is_compressed == 1 ? 'zlib compressed' : 'uncompressed'}"
        println "Indexed at:   ${doc.indexed_at}"
        println "=" * 70

        String content = doc.content?.toString() ?: ''
        List<String> lines = content.readLines()
        int displayLines = Math.min(lines.size(), maxLines)
        lines.take(displayLines).eachWithIndex { String l, int i ->
            printf "%4d | %s%n", (i + 1), l
        }
        if (lines.size() > displayLines) {
            println "... (${lines.size() - displayLines} more lines truncated. Use --all or --lines N to view more)"
        }
        println "=" * 70
        printf "Retrieved in %.2f ms%n", elapsedMs
    } catch (NumberFormatException e) {
        println "Invalid ID: ${idStr}. Usage: :doc <numeric_id>"
    }
}

/**
 * Lists all files with a given extension.
 *
 * @param engine    MemoryEngine instance
 * @param extension Extension to filter (with or without leading dot)
 * @param limit     Maximum results (default: 50)
 */
static void executeExtList(MemoryEngine engine, String extension, int limit = 50) {
    String clean = extension ? extension.trim().replaceAll('^[\'"\\\\\\s]+|[\'"\\\\\\s]+$', '') : ''
    String targetExt = ''
    if (clean.isEmpty() || clean.toLowerCase() == 'none' || clean.toLowerCase() == 'empty' || clean == '--no-ext') {
        targetExt = ''
    } else {
        targetExt = clean.startsWith('.') ? clean.toLowerCase() : '.' + clean.toLowerCase()
    }

    List<Map> results = engine.listByExtension(targetExt, limit)

    String displayLabel = targetExt.isEmpty() ? '(no extension)' : targetExt
    if (results.isEmpty()) {
        println "No files found with extension: ${displayLabel}"
        return
    }

    println "Files with extension ${displayLabel} (${results.size()} shown):"
    println ""
    results.eachWithIndex { Map r, int idx ->
        printf "  [%d] ID:%-5d %s  (%d bytes)%n", (idx + 1), r.id, r.file_path, r.size_bytes
    }
}

/**
 * Lists files matching a path pattern using SQL LIKE.
 *
 * @param engine  MemoryEngine instance
 * @param pattern Search pattern (automatically wrapped in %...% if no wildcards)
 * @param limit   Maximum results (default: 50)
 */
static void executeFileList(MemoryEngine engine, String pattern, int limit = 50) {
    // Auto-wrap with wildcards if user didn't include them
    if (!pattern.contains('%') && !pattern.contains('_')) {
        pattern = "%${pattern}%"
    }

    List<Map> results = engine.listFiles(pattern, limit)

    if (results.isEmpty()) {
        println "No files matching pattern: ${pattern}"
        return
    }

    println "Files matching '${pattern}' (${results.size()} shown):"
    println ""
    results.eachWithIndex { Map r, int idx ->
        printf "  [%d] ID:%-5d %-10s %s%n", (idx + 1), r.id, r.extension, r.file_path
    }
}

/**
 * Prints database statistics and recorded archive manifests.
 *
 * @param engine MemoryEngine instance
 */
static void printStats(MemoryEngine engine) {
    Map stats = engine.getStats()
    long dbFileSizeBytes = Config.DB_PATH.exists() ? Config.DB_PATH.length() : 0
    int totalDocs = (stats.total_documents ?: 0) as int
    int compressedDocs = (stats.compressed_documents ?: 0) as int

    String storageState = "UNCOMPRESSED"
    if (totalDocs > 0) {
        if (compressedDocs == totalDocs) {
            storageState = "COMPRESSED (100%)"
        } else if (compressedDocs > 0) {
            double pct = (compressedDocs * 100.0) / totalDocs
            storageState = String.format("HYBRID (%d/%d compressed, %.1f%%)", compressedDocs, totalDocs, pct)
        } else {
            storageState = "UNCOMPRESSED (0% compressed)"
        }
    }

    println "Database: ${Config.DB_PATH.absolutePath}"
    println "  Disk File Size:  ${formatSize(dbFileSizeBytes)}"
    println "  Total documents: ${stats.total_documents}"
    println "  Total content:   ${formatSize(stats.total_size_bytes as long)}"
    println "  Storage State:   ${storageState}"
    println ""

    if (stats.manifest) {
        println "  Ingested Archives (Manifest):"
        stats.manifest.each { m ->
            String compState = m.compression_state ?: 'uncompressed'
            printf "    - %-25s %d docs | %s text | %-12s | archive %s | %s%n",
                m.source_archive, m.ingested_documents, formatSize(m.total_text_bytes as long),
                compState, formatSize(m.archive_size_bytes as long), m.ingested_at
        }
        println ""
    }

    if (stats.by_archive) {
        println "  By archive:"
        stats.by_archive.each { archive, count ->
            printf "    %-30s %d docs%n", archive, count
        }
    }

    if (stats.by_extension) {
        println "  Top extensions:"
        stats.by_extension.each { ext, count ->
            printf "    %-15s %d%n", ext, count
        }
    }
}

/**
 * Prints the REPL help text.
 */
static void printHelp() {
    println """
Commands:
  <text>          FTS5 full-text search (supports --limit N, --snippet-size N, --raw)
  :limit <N>      Change default result limit (e.g. :limit 10)
  :snippet <N>    Change default snippet size (e.g. :snippet 128)
  :stats          Database statistics and compression states
  :doc <id>       View document by ID (supports --all, --lines N, --raw)
  :ext <.ext>     List files by extension (e.g., :ext .sql)
  :files <pat>    List files matching path pattern (e.g., :files Address)
  :help           This help text
  :quit           Exit

Document Inspection Flags:
  :doc <id>               Display first 200 lines (default)
  :doc <id> --lines <N>   Display first N lines with line numbers
  :doc <id> --all         Display all lines with line numbers
  :doc <id> --raw         Display verbatim raw text without header or numbers

Search Filtering Flags:
  --ext <.ext>            Filter by extension (e.g. --ext pdf, --ext java,xml, --ext '')
  --no-ext                Filter exclusively for files with no file extension
  --archive <name>        Filter by archive (e.g. --archive sample_archive.zip)
  --limit <N> / -n <N>    Maximum result count (default: 20)
  --snippet-size <N>      Snippet token window size (default: 64)
  --raw                   Output results without border framing

FTS5 Search Syntax:
  simple terms    address write
  phrases         "CREATE TABLE"
  prefix match    Address*
  column filter   file_name:Registry
  boolean         billing AND customer
  NOT             billing NOT customer
  ext filter      Provision* --ext pdf,md,txt,'' --limit 5
  no-ext filter   Makefile --no-ext
"""
}

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

/**
 * Prints a search result snippet with hanging indentation so wrapped lines
 * align precisely underneath the start of the snippet content.
 *
 * @param snippet  Snippet text to print
 * @param maxWidth Maximum character width per line before wrapping
 */
static void printHangingSnippet(String snippet, int maxWidth = 95) {
    if (!snippet) return
    String firstPrefix = "    Snippet: "
    String hangingIndent = "             "
    int wrapWidth = Math.max(30, maxWidth - firstPrefix.length())

    List<String> words = snippet.split(' ') as List
    List<String> lines = []
    StringBuilder currentLine = new StringBuilder()

    for (String word : words) {
        if (currentLine.length() + word.length() + 1 > wrapWidth && currentLine.length() > 0) {
            lines << currentLine.toString()
            currentLine = new StringBuilder(word)
        } else {
            if (currentLine.length() > 0) currentLine.append(' ')
            currentLine.append(word)
        }
    }
    if (currentLine.length() > 0) {
        lines << currentLine.toString()
    }

    lines.eachWithIndex { String l, int i ->
        if (i == 0) {
            println "${firstPrefix}${l}"
        } else {
            println "${hangingIndent}${l}"
        }
    }
}

