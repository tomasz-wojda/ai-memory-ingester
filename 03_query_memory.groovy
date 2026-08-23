/**
 * 03_query_memory.groovy
 *
 * Interactive Query CLI & Database Sets Federation Controller for Archive Memory Context Engine.
 * Supports single-database queries (direct FTS5/BM25), federated database set searches (with Reciprocal
 * Rank Fusion RRF), set inspection/mutation commands, and an interactive REPL with set switching.
 *
 * Role in Project:
 * Primary search entry point and query dispatch controller for single-database and federated set searches.
 * Loaded by run.groovy (`groovy run.groovy query ...`).
 */

Config.ensureDataDir()

// -----------------------------------------------------------------------
// CLI Argument & Flag Parsing
// -----------------------------------------------------------------------

int limit = 20
int snippetSize = 64
int maxLines = 200
boolean raw = false
List<String> extFilter = []
String archiveFilter = null
String explicitDb = null
String explicitSet = null
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
    } else if ((arg == '--db' || arg == '-D') && i + 1 < args.length) {
        explicitDb = args[i + 1].trim()
        i += 2
    } else if (arg.startsWith('--db=')) {
        explicitDb = arg.substring('--db='.length()).trim()
        i++
    } else if ((arg == '--set' || arg == '-S') && i + 1 < args.length) {
        explicitSet = args[i + 1].trim()
        i += 2
    } else if (arg.startsWith('--set=')) {
        explicitSet = arg.substring('--set='.length()).trim()
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
    } else if (arg == '--sets' || arg == 'sets') {
        printSets()
        System.exit(0)
    } else if (arg == '--create-set' && i + 1 < args.length) {
        String setName = args[i + 1]
        List<String> dbs = (i + 2 < args.length) ? args[(i + 2)..-1].toList() : []
        executeSetCreate(setName, dbs)
        System.exit(0)
    } else if (arg == '--delete-set' && i + 1 < args.length) {
        executeSetDelete(args[i + 1])
        System.exit(0)
    } else if (arg == '--rename-set' && i + 2 < args.length) {
        executeSetRename(args[i + 1], args[i + 2])
        System.exit(0)
    } else if (arg == '--add-db-to-set' && i + 2 < args.length) {
        executeSetAddDb(args[i + 1], args[i + 2])
        System.exit(0)
    } else if (arg == '--remove-db-from-set' && i + 2 < args.length) {
        executeSetRemoveDb(args[i + 1], args[i + 2])
        System.exit(0)
    } else if (arg == '--use-set' && i + 1 < args.length) {
        executeSetUse(args[i + 1])
        System.exit(0)
    } else {
        queryTokens << arg
        i++
    }
}

// -----------------------------------------------------------------------
// Direct Command Dispatches
// -----------------------------------------------------------------------

String input = queryTokens.join(' ').trim()

if (!input.isEmpty()) {
    if (input == 'sets' || input == ':sets') {
        printSets()
        System.exit(0)
    } else if (input == 'dbs' || input == ':dbs' || input == 'databases') {
        printDatabases()
        System.exit(0)
    } else if (input.startsWith('set ') || input.startsWith(':set ')) {
        handleSetSubcommand(input.startsWith(':set ') ? input.substring(':set '.length()).trim() : input.substring('set '.length()).trim())
        System.exit(0)
    } else if (input.startsWith('use ') || input.startsWith(':use ')) {
        String targetName = input.startsWith(':use ') ? input.substring(':use '.length()).trim() : input.substring('use '.length()).trim()
        executeSetUse(targetName)
        System.exit(0)
    } else if (input == ':stats' || input == 'stats') {
        File targetDb = explicitDb ? Config.resolveDatabase(explicitDb) : Config.DB_PATH
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        printStats(eng)
        eng.close()
        System.exit(0)
    } else if (input == ':archives' || input == 'archives' || input == ':archs' || input == 'archs') {
        File targetDb = explicitDb ? Config.resolveDatabase(explicitDb) : Config.DB_PATH
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        printArchives(eng)
        eng.close()
        System.exit(0)
    } else if (input.startsWith(':egest ') || input.startsWith('egest ')) {
        String arch = input.startsWith(':egest ') ? input.substring(':egest '.length()).trim() : input.substring('egest '.length()).trim()
        File targetDb = explicitDb ? Config.resolveDatabase(explicitDb) : Config.DB_PATH
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        executeEgest(eng, arch)
        eng.close()
        System.exit(0)
    } else if (input.startsWith(':rename ') || input.startsWith('rename-archive ')) {
        String rest = input.startsWith(':rename ') ? input.substring(':rename '.length()).trim() : input.substring('rename-archive '.length()).trim()
        def parts = rest.split('\\s+')
        if (parts.length >= 2) {
            File targetDb = explicitDb ? Config.resolveDatabase(explicitDb) : Config.DB_PATH
            MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
            executeRename(eng, parts[0], parts[1])
            eng.close()
        } else {
            println "Usage: rename-archive <old_name> <new_name>"
        }
        System.exit(0)
    } else if (input == ':files' || input.startsWith(':files ') || input.startsWith(':files')) {
        String pattern = input.startsWith(':files ') ? input.substring(':files '.length()).trim() : (input.length() > 6 ? input.substring(6).trim() : '%')
        File targetDb = explicitDb ? Config.resolveDatabase(explicitDb) : Config.DB_PATH
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        executeFileList(eng, pattern.isEmpty() ? '%' : pattern, limit)
        eng.close()
        System.exit(0)
    } else if (input.startsWith(':doc ') || input.startsWith(':doc')) {
        String idStr = input.startsWith(':doc ') ? input.substring(':doc '.length()).trim() : input.substring(4).trim()
        File targetDb = explicitDb ? Config.resolveDatabase(explicitDb) : Config.DB_PATH
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        executeDocView(eng, idStr, raw, maxLines)
        eng.close()
        System.exit(0)
    } else if (input == ':ext' || input.startsWith(':ext ') || input.startsWith(':ext')) {
        String ext = input.startsWith(':ext ') ? input.substring(':ext '.length()).trim() : (input.length() > 4 ? input.substring(4).trim() : '')
        File targetDb = explicitDb ? Config.resolveDatabase(explicitDb) : Config.DB_PATH
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        executeExtList(eng, ext, limit)
        eng.close()
        System.exit(0)
    } else if (input == ':help' || input == ':h') {
        printHelp()
        System.exit(0)
    } else if (input.startsWith(':')) {
        println "Unknown command: ${input}. Type :help for available commands."
        System.exit(0)
    } else {
        // Search Query Routing: 3-Tier Precedence
        if (explicitDb != null) {
            // Tier 1: Single Database Search (raw BM25, no federation)
            File targetDb = Config.resolveDatabase(explicitDb)
            if (!targetDb.exists()) {
                println "ERROR: Database not found: ${targetDb.absolutePath}"
                System.exit(1)
            }
            MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
            executeSearch(eng, input, limit, raw, snippetSize, extFilter, archiveFilter)
            eng.close()
        } else {
            // Tier 2 & 3: Database Set Federated Search with RRF Fusion
            String targetSet = explicitSet ?: SetRegistry.getActiveSet()
            executeFederatedSearch(targetSet, input, limit, raw, snippetSize, extFilter, archiveFilter)
        }
        System.exit(0)
    }
}

// -----------------------------------------------------------------------
// Interactive REPL Mode
// -----------------------------------------------------------------------

String activeSet = SetRegistry.getActiveSet()
println "=" * 70
println "Memory Query Engine — Interactive REPL"
println "=" * 70
println "Active Database Set: ${activeSet}"
printSets()
println "Type a search query, or :help for commands. :quit to exit."
println ""

BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
String line
int replDefaultLimit = 20
int replDefaultSnippetSize = 64

while (true) {
    print "memory{${activeSet}}> "
    line = reader.readLine()

    if (line == null) {
        println ""
        break
    }

    line = line.trim()
    if (line.isEmpty()) continue

    // Inline flag parsing in REPL
    int curLimit = replDefaultLimit
    int curSnippet = replDefaultSnippetSize
    if (line.contains('--') || line.contains(' -')) {
        def parts = line.split('\\s+')
        List<String> cleanParts = []
        int j = 0
        while (j < parts.length) {
            if ((parts[j] == '--limit' || parts[j] == '-n') && j + 1 < parts.length) {
                try { curLimit = parts[j + 1].toInteger() } catch (Exception ignored) {}
                j += 2
            } else if ((parts[j] == '--snippet-size' || parts[j] == '-s') && j + 1 < parts.length) {
                try { curSnippet = parts[j + 1].toInteger() } catch (Exception ignored) {}
                j += 2
            } else {
                cleanParts << parts[j]
                j++
            }
        }
        line = cleanParts.join(' ').trim()
    }

    if (line == ':quit' || line == ':q') {
        break
    } else if (line == ':help' || line == ':h') {
        printHelp()
    } else if (line == ':sets' || line == 'sets') {
        printSets()
    } else if (line == ':dbs' || line == ':databases' || line == 'dbs') {
        printDatabases()
    } else if (line.startsWith(':use ') || line.startsWith(':set ')) {
        String newSet = (line.startsWith(':use ') ? line.substring(':use '.length()) : line.substring(':set '.length())).trim()
        try {
            SetRegistry.setActiveSet(newSet)
            activeSet = SetRegistry.getActiveSet()
            println "Active set switched to: ${activeSet}"
        } catch (Exception e) {
            println "ERROR: ${e.message}"
        }
    } else if (line == ':stats') {
        File targetDb = Config.resolveDatabase(Config.DB_PATH.name)
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        printStats(eng)
        eng.close()
    } else if (line == ':archives' || line == ':archs') {
        File targetDb = Config.resolveDatabase(Config.DB_PATH.name)
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        printArchives(eng)
        eng.close()
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
        File targetDb = Config.resolveDatabase(Config.DB_PATH.name)
        MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
        executeDocView(eng, sub, isRaw, docMaxLines)
        eng.close()
    } else if (line.startsWith(':')) {
        println "Unknown command: ${line}. Type :help for commands."
    } else {
        executeFederatedSearch(activeSet, line, curLimit, false, curSnippet)
    }

    println ""
}

println "Session ended."

// -----------------------------------------------------------------------
// Helper Implementations
// -----------------------------------------------------------------------

/**
 * Handles set subcommands from CLI arguments.
 */
static void handleSetSubcommand(String commandString) {
    def parts = commandString.split('\\s+')
    if (parts.length == 0) return
    String sub = parts[0].toLowerCase()

    if (sub == 'list') {
        printSets()
    } else if (sub == 'use' || sub == 'default') {
        if (parts.length >= 2) executeSetUse(parts[1])
        else println "Usage: set use <set_name>"
    } else if (sub == 'create') {
        if (parts.length >= 2) {
            String sName = parts[1]
            List<String> dbs = (parts.length > 2) ? parts[2..-1].toList() : []
            executeSetCreate(sName, dbs)
        } else {
            println "Usage: set create <set_name> [db1,db2,...]"
        }
    } else if (sub == 'delete' || sub == 'remove') {
        if (parts.length >= 2) executeSetDelete(parts[1])
        else println "Usage: set delete <set_name>"
    } else if (sub == 'rename') {
        if (parts.length >= 3) executeSetRename(parts[1], parts[2])
        else println "Usage: set rename <old_name> <new_name>"
    } else if (sub == 'add-db' || sub == 'add') {
        if (parts.length >= 3) executeSetAddDb(parts[1], parts[2])
        else println "Usage: set add-db <set_name> <db_name>"
    } else if (sub == 'remove-db' || sub == 'rm-db') {
        if (parts.length >= 3) executeSetRemoveDb(parts[1], parts[2])
        else println "Usage: set remove-db <set_name> <db_name>"
    } else {
        println "Unknown set command: ${sub}. Available: list, use, create, delete, rename, add-db, remove-db"
    }
}

/**
 * Creates a database set.
 */
static void executeSetCreate(String setName, List<String> databases) {
    try {
        SetRegistry.createSet(setName, databases)
        println "Successfully created database set '${setName}'."
    } catch (Exception e) {
        println "ERROR: Failed to create set '${setName}': ${e.message}"
    }
}

/**
 * Deletes a database set.
 */
static void executeSetDelete(String setName) {
    try {
        SetRegistry.deleteSet(setName)
        println "Successfully deleted database set '${setName}'. Physical database files were preserved."
    } catch (Exception e) {
        println "ERROR: Failed to delete set '${setName}': ${e.message}"
    }
}

/**
 * Renames a database set.
 */
static void executeSetRename(String oldName, String newName) {
    try {
        SetRegistry.renameSet(oldName, newName)
        println "Successfully renamed database set '${oldName}' -> '${newName}'."
    } catch (Exception e) {
        println "ERROR: Failed to rename set: ${e.message}"
    }
}

/**
 * Adds a database to a set.
 */
static void executeSetAddDb(String setName, String dbName) {
    try {
        SetRegistry.addDatabaseToSet(setName, dbName)
        println "Successfully added database '${dbName}' to set '${setName}'."
    } catch (Exception e) {
        println "ERROR: Failed to add database to set: ${e.message}"
    }
}

/**
 * Removes a database from a set.
 */
static void executeSetRemoveDb(String setName, String dbName) {
    try {
        SetRegistry.removeDatabaseFromSet(setName, dbName)
        println "Successfully removed database '${dbName}' from set '${setName}'."
    } catch (Exception e) {
        println "ERROR: Failed to remove database from set: ${e.message}"
    }
}

/**
 * Switches the active default set.
 */
static void executeSetUse(String setName) {
    try {
        SetRegistry.setActiveSet(setName)
        println "Active database set switched to: ${SetRegistry.getActiveSet()}"
    } catch (Exception e) {
        println "ERROR: Failed to switch active set: ${e.message}"
    }
}

/**
 * Displays the Database Sets table.
 */
static void printSets() {
    List<Map> sets = SetRegistry.listSets()
    println "=" * 104
    println "Discovered Database Sets (${sets.size()} found):"
    println "=" * 104
    if (sets.isEmpty()) {
        println "  No database sets defined in ${Config.SETS_FILE.absolutePath}"
        return
    }
    printf "  %-16s %-10s %15s %16s %s%n", "Set Name", "Status", "Databases Count", "Total Text Size", "Member Databases"
    printf "  %-16s %-10s %15s %16s %s%n", "-" * 16, "-" * 10, "-" * 15, "-" * 16, "-" * 35
    sets.each { Map s ->
        String status = s.is_active ? "[ACTIVE]" : ""
        printf "  %-16s %-10s %15d %16s  %s%n",
            s.name, status, s.database_count, formatSize(s.total_bytes as long), s.members_formatted
    }
    println "=" * 104
    println ""
}

/**
 * Executes a federated search across all databases in a set with RRF fusion.
 */
static void executeFederatedSearch(
    String setName,
    String query,
    int limit = 20,
    boolean raw = false,
    int snippetSize = 64,
    List<String> extensions = null,
    String archiveFilter = null
) {
    if (!raw) {
        println "-" * 50
        StringBuilder header = new StringBuilder("Searching Set [${setName}]: \"${query}\"")
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

    boolean noExt = (extensions != null && extensions.contains(''))
    List<String> cleanExts = extensions ? extensions.findAll { it != '' } : null

    Map res = FederatedEngine.searchSet(
        setName,
        query,
        limit,
        cleanExts,
        noExt,
        archiveFilter,
        snippetSize
    )

    List<Map> matches = (res.results as List<Map>) ?: []

    if (matches.isEmpty()) {
        printf "No results found across %d database(s) in %.2f ms.%n", res.database_count, res.total_duration_ms
        return
    }

    matches.eachWithIndex { Map doc, int idx ->
        double rrf = (doc.rrf_score as double) ?: 0.0
        printf "[%d] [%s] ID:%d  %s%n", (idx + 1), doc.origin_db, doc.id, doc.file_path
        printf "    Archive: %s  |  Type: %s  |  RRF: %.4f%n", doc.source_archive, doc.extension, rrf
        String snippet = (doc.snippet ?: '').toString()
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
    printf "Found %d result(s) across %d database(s) in %.2f ms (Fusion: %.2f ms)%n",
        matches.size(), res.successful_databases, res.total_duration_ms, res.fusion_duration_ms
}

/**
 * Executes a single-database FTS5 search query.
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
    }
}

/**
 * Displays full content of a document by ID.
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
        lines.take(displayLines).eachWithIndex { String l, int idx ->
            printf "%4d | %s%n", (idx + 1), l
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
 * Lists all files matching extension.
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
 * Lists files matching path pattern.
 */
static void executeFileList(MemoryEngine engine, String pattern, int limit = 50) {
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
 * Prints database statistics.
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
}

/**
 * Lists all discovered database files in data/ directory.
 */
static void printDatabases() {
    List<File> dbs = Config.listDatabases()
    println "=" * 70
    println "Discovered Memory Databases (${dbs.size()} found):"
    println "=" * 70
    if (dbs.isEmpty()) {
        println "  No database files found in ${Config.DATA_DIR.absolutePath}"
        return
    }
    printf "  %-30s %12s  %s%n", "Database Name", "Disk Size", "Status"
    printf "  %-30s %12s  %s%n", "-" * 30, "-" * 12, "-" * 15
    dbs.each { File db ->
        String activeMarker = (db.canonicalPath == Config.DB_PATH.canonicalPath) ? " [ACTIVE]" : ""
        printf "  %-30s %12s %s%n", db.name, formatSize(db.length()), activeMarker
    }
    println ""
}

/**
 * Lists all distinct archives in the active database.
 */
static void printArchives(MemoryEngine engine) {
    List<Map> archives = engine.listArchives()
    println "=" * 85
    println "Active Database Archives (${archives.size()} recorded):"
    println "=" * 85
    if (archives.isEmpty()) {
        println "  No archives recorded in database."
        return
    }
    printf "  %-25s %8s %12s %13s %16s %s%n", "Archive Name", "Docs", "Text Size", "Density", "Compressed Size", "Ingested At"
    printf "  %-25s %8s %12s %13s %16s %s%n", "-" * 25, "-" * 8, "-" * 12, "-" * 13, "-" * 16, "-" * 19
    archives.each { Map a ->
        long textBytes = a.live_text_bytes as long
        long storedBytes = a.stored_bytes != null ? (a.stored_bytes as long) : textBytes
        long compDocs = a.compressed_documents as long
        long liveDocs = a.live_documents as long

        double densityBytes = liveDocs > 0 ? (textBytes / (double) liveDocs) : 0.0
        String densityStr = formatDensity(densityBytes)

        String compDisplay
        if (compDocs == 0 || textBytes == 0) {
            compDisplay = "0.0% (raw)"
        } else {
            double ratio = ((textBytes - storedBytes) * 100.0) / (double) textBytes
            compDisplay = "${formatSize(storedBytes)} (${String.format('%.1f%%', Math.max(0.0, ratio))})"
        }

        printf "  %-25s %8d %12s %13s %16s %s%n",
            a.source_archive, liveDocs, formatSize(textBytes),
            densityStr, compDisplay, a.ingested_at ?: '-'
    }
    println ""
}

/**
 * Formats average document density string.
 */
static String formatDensity(double bytesPerDoc) {
    if (bytesPerDoc <= 0) return "0 B/doc"
    if (bytesPerDoc < 1024) return String.format("%.0f B/doc", bytesPerDoc)
    double kb = bytesPerDoc / 1024.0
    if (kb < 1024) {
        if (kb < 10.0) return String.format("%.2f KB/doc", kb)
        if (kb < 100.0) return String.format("%.1f KB/doc", kb)
        return String.format("%.0f KB/doc", kb)
    }
    double mb = kb / 1024.0
    if (mb < 10.0) return String.format("%.2f MB/doc", mb)
    if (mb < 100.0) return String.format("%.1f MB/doc", mb)
    return String.format("%.0f MB/doc", mb)
}

/**
 * Egests archive.
 */
static void executeEgest(MemoryEngine engine, String archiveName) {
    String clean = archiveName.trim().replaceAll("^['\"]+|['\"]+\$", '')
    println "Egesting archive '${clean}' from database..."
    Map res = engine.egestArchive(clean)
    println "Egest Complete: Purged ${res.deleted_documents} document(s) and cleared FTS5 index."
}

/**
 * Renames archive.
 */
static void executeRename(MemoryEngine engine, String oldName, String newName) {
    String cleanOld = oldName.trim().replaceAll("^['\"]+|['\"]+\$", '')
    String cleanNew = newName.trim().replaceAll("^['\"]+|['\"]+\$", '')
    println "Renaming archive '${cleanOld}' -> '${cleanNew}'..."
    int updated = engine.renameArchive(cleanOld, cleanNew)
    println "Rename Complete: Updated ${updated} document(s)."
}

/**
 * Prints REPL help reference.
 */
static void printHelp() {
    println """
Search Commands:
  <query>               Federated FTS5 search across active database set
  <query> --db <name>   Direct single-database search (bypasses federation)
  <query> --set <name>  Federated search across specific database set

Set Management:
  sets                  List all database sets and member databases
  set use <name>        Switch the active default database set
  set create <n> [dbs]  Create a new database set
  set delete <name>     Delete a database set definition
  set rename <old> <n>  Rename a database set
  set add-db <set> <db> Add a database identifier to a set
  set remove-db <s> <d> Remove a database identifier from a set

Inspection Commands:
  :stats                Database statistics and compression states
  :doc <id>             View document content (supports --all, --lines N, --raw)
  :ext <.ext>           List files by extension (e.g. :ext .sql, :ext "")
  :files <pattern>      List files matching SQL LIKE pattern
  :dbs                  List all physical database files in data/
  :sets                 List all database sets
  :set <name>           Switch active set in REPL
  :help                 Display this command reference
  :quit                 Exit REPL
"""
}

/**
 * Formats byte count to human-readable string.
 */
static String formatSize(long bytes) {
    if (bytes < 1024) return "${bytes} B"
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
    if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/**
 * Prints hanging indentation snippet.
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
    if (currentLine.length() > 0) lines << currentLine.toString()

    lines.eachWithIndex { String l, int idx ->
        if (idx == 0) println "${firstPrefix}${l}"
        else println "${hangingIndent}${l}"
    }
}
