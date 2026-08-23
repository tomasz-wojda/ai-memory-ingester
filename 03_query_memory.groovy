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
    } else if (arg == '--data-dir' && i + 1 < args.length) {
        String customDir = args[i + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')
        Config.setDataDir(customDir)
        i += 2
    } else if (arg.startsWith('--data-dir=')) {
        String customDir = arg.substring('--data-dir='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')
        Config.setDataDir(customDir)
        i++
    } else if ((arg == '--db' || arg == '-D') && i + 1 < args.length) {
        explicitDb = args[i + 1].trim()
        i += 2
    } else if (arg.startsWith('--db=')) {
        explicitDb = arg.substring('--db='.length()).trim()
        i++
    } else if ((arg == '--dataset' || arg == '--set' || arg == '-S') && i + 1 < args.length) {
        explicitSet = args[i + 1].trim()
        i += 2
    } else if (arg.startsWith('--dataset=')) {
        explicitSet = arg.substring('--dataset='.length()).trim()
        i++
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
    } else if (arg == '--datasets' || arg == 'datasets' || arg == '--sets' || arg == 'sets') {
        printDatasets()
        System.exit(0)
    } else if ((arg == '--create-dataset' || arg == '--create-set') && i + 1 < args.length) {
        String setName = args[i + 1]
        List<String> dbs = (i + 2 < args.length) ? args[(i + 2)..-1].toList() : []
        executeDatasetCreate(setName, dbs)
        System.exit(0)
    } else if ((arg == '--delete-dataset' || arg == '--delete-set') && i + 1 < args.length) {
        executeDatasetDelete(args[i + 1])
        System.exit(0)
    } else if ((arg == '--rename-dataset' || arg == '--rename-set') && i + 2 < args.length) {
        executeDatasetRename(args[i + 1], args[i + 2])
        System.exit(0)
    } else if ((arg == '--add-db-to-dataset' || arg == '--add-db-to-set') && i + 2 < args.length) {
        executeDatasetAddDb(args[i + 1], args[i + 2])
        System.exit(0)
    } else if ((arg == '--remove-db-from-dataset' || arg == '--remove-db-from-set') && i + 2 < args.length) {
        executeDatasetRemoveDb(args[i + 1], args[i + 2])
        System.exit(0)
    } else if ((arg == '--use-dataset' || arg == '--use-set') && i + 1 < args.length) {
        executeDatasetUse(args[i + 1])
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
    if (input == 'datasets' || input == ':datasets' || input == 'sets' || input == ':sets') {
        printDatasets()
        System.exit(0)
    } else if (input == 'dbs' || input == ':dbs' || input == 'databases') {
        printDatabases()
        System.exit(0)
    } else if (input.startsWith('dataset ') || input.startsWith(':dataset ') || input.startsWith('set ') || input.startsWith(':set ')) {
        String cmdStr = input
        if (cmdStr.startsWith(':dataset ')) cmdStr = cmdStr.substring(':dataset '.length()).trim()
        else if (cmdStr.startsWith('dataset ')) cmdStr = cmdStr.substring('dataset '.length()).trim()
        else if (cmdStr.startsWith(':set ')) cmdStr = cmdStr.substring(':set '.length()).trim()
        else cmdStr = cmdStr.substring('set '.length()).trim()
        handleDatasetSubcommand(cmdStr)
        System.exit(0)
    } else if (input.startsWith('use ') || input.startsWith(':use ')) {
        String targetName = input.startsWith(':use ') ? input.substring(':use '.length()).trim() : input.substring('use '.length()).trim()
        executeDatasetUse(targetName)
        System.exit(0)
    } else if (input == ':stats' || input == 'stats') {
        if (explicitDb != null) {
            File targetDb = Config.resolveDatabase(explicitDb)
            MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
            printStats(eng)
            eng.close()
        } else {
            String targetDataset = explicitSet ?: SetRegistry.getActiveSet()
            printDatasetStats(targetDataset)
        }
        System.exit(0)
    } else if (input == ':archives' || input == 'archives' || input == ':archs' || input == 'archs') {
        if (explicitDb != null) {
            File targetDb = Config.resolveDatabase(explicitDb)
            MemoryEngine eng = new MemoryEngine(targetDb.absolutePath)
            printArchives(eng)
            eng.close()
        } else {
            String targetDataset = explicitSet ?: SetRegistry.getActiveSet()
            printDatasetArchives(targetDataset)
        }
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
            // Tier 2 & 3: Dataset Federated Search with RRF Fusion
            String targetDataset = explicitSet ?: DatasetRegistry.getActiveDataset()
            executeFederatedSearch(targetDataset, input, limit, raw, snippetSize, extFilter, archiveFilter)
        }
        System.exit(0)
    }
}

// -----------------------------------------------------------------------
// Interactive REPL Mode
// -----------------------------------------------------------------------

String activeDataset = DatasetRegistry.getActiveDataset()
println "=" * 70
println "Memory Query Engine — Interactive REPL"
println "=" * 70
println "Active Dataset: ${activeDataset}"
printDatasets()
println "Type a search query, or :help for commands. :quit to exit."
println ""

BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
String line
int replDefaultLimit = 20
int replDefaultSnippetSize = 64

while (true) {
    print "memory[${activeDataset}]> "
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
    } else if (line == ':sets' || line == 'sets' || line == ':datasets' || line == 'datasets') {
        printDatasets()
    } else if (line == ':dbs' || line == ':databases' || line == 'dbs') {
        printDatabases()
    } else if (line.startsWith(':use ') || line.startsWith(':dataset ') || line.startsWith(':set ')) {
        String newSet = (line.startsWith(':use ') ? line.substring(':use '.length()) : (line.startsWith(':dataset ') ? line.substring(':dataset '.length()) : line.substring(':set '.length()))).trim()
        try {
            DatasetRegistry.setActiveDataset(newSet)
            activeDataset = DatasetRegistry.getActiveDataset()
            println "Active dataset switched to: ${activeDataset}"
        } catch (Exception e) {
            println "ERROR: ${e.message}"
        }
    } else if (line == ':stats') {
        printDatasetStats(activeDataset)
    } else if (line == ':archives' || line == ':archs') {
        printDatasetArchives(activeDataset)
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
 * Handles dataset subcommands from CLI arguments.
 */
static void handleDatasetSubcommand(String commandString) {
    def parts = commandString.split('\\s+')
    if (parts.length == 0) return
    String sub = parts[0].toLowerCase()

    if (sub == 'list') {
        printDatasets()
    } else if (sub == 'use' || sub == 'default') {
        if (parts.length >= 2) executeDatasetUse(parts[1])
        else println "Usage: dataset use <dataset_name>"
    } else if (sub == 'create') {
        if (parts.length >= 2) {
            String sName = parts[1]
            List<String> dbs = (parts.length > 2) ? parts[2..-1].toList() : []
            executeDatasetCreate(sName, dbs)
        } else {
            println "Usage: dataset create <dataset_name> [db1,db2,...]"
        }
    } else if (sub == 'delete' || sub == 'remove') {
        if (parts.length >= 2) executeDatasetDelete(parts[1])
        else println "Usage: dataset delete <dataset_name>"
    } else if (sub == 'rename') {
        if (parts.length >= 3) executeDatasetRename(parts[1], parts[2])
        else println "Usage: dataset rename <old_name> <new_name>"
    } else if (sub == 'add-db' || sub == 'add') {
        if (parts.length >= 3) executeDatasetAddDb(parts[1], parts[2])
        else println "Usage: dataset add-db <dataset_name> <db_name>"
    } else if (sub == 'remove-db' || sub == 'rm-db') {
        if (parts.length >= 3) executeDatasetRemoveDb(parts[1], parts[2])
        else println "Usage: dataset remove-db <dataset_name> <db_name>"
    } else {
        println "Unknown dataset command: ${sub}. Available: list, use, create, delete, rename, add-db, remove-db"
    }
}

/** Backward compatibility alias. */
static void handleSetSubcommand(String commandString) {
    handleDatasetSubcommand(commandString)
}

/**
 * Creates a dataset.
 */
static void executeDatasetCreate(String datasetName, List<String> databases) {
    try {
        DatasetRegistry.createDataset(datasetName, databases)
        println "Successfully created dataset '${datasetName}'."
    } catch (Exception e) {
        println "ERROR: Failed to create dataset '${datasetName}': ${e.message}"
    }
}

static void executeSetCreate(String setName, List<String> databases) {
    executeDatasetCreate(setName, databases)
}

/**
 * Deletes a dataset.
 */
static void executeDatasetDelete(String datasetName) {
    try {
        DatasetRegistry.deleteDataset(datasetName)
        println "Successfully deleted dataset '${datasetName}'. Physical database files were preserved."
    } catch (Exception e) {
        println "ERROR: Failed to delete dataset '${datasetName}': ${e.message}"
    }
}

static void executeSetDelete(String setName) {
    executeDatasetDelete(setName)
}

/**
 * Renames a dataset.
 */
static void executeDatasetRename(String oldName, String newName) {
    try {
        DatasetRegistry.renameDataset(oldName, newName)
        println "Successfully renamed dataset '${oldName}' -> '${newName}'."
    } catch (Exception e) {
        println "ERROR: Failed to rename dataset: ${e.message}"
    }
}

static void executeSetRename(String oldName, String newName) {
    executeDatasetRename(oldName, newName)
}

/**
 * Adds a database to a dataset.
 */
static void executeDatasetAddDb(String datasetName, String dbName) {
    try {
        DatasetRegistry.addDatabaseToDataset(datasetName, dbName)
        println "Successfully added database '${dbName}' to dataset '${datasetName}'."
    } catch (Exception e) {
        println "ERROR: Failed to add database to dataset: ${e.message}"
    }
}

static void executeSetAddDb(String setName, String dbName) {
    executeDatasetAddDb(setName, dbName)
}

/**
 * Removes a database from a dataset.
 */
static void executeDatasetRemoveDb(String datasetName, String dbName) {
    try {
        DatasetRegistry.removeDatabaseFromDataset(datasetName, dbName)
        println "Successfully removed database '${dbName}' from dataset '${datasetName}'."
    } catch (Exception e) {
        println "ERROR: Failed to remove database from dataset: ${e.message}"
    }
}

static void executeSetRemoveDb(String setName, String dbName) {
    executeDatasetRemoveDb(setName, dbName)
}

/**
 * Switches the active default dataset.
 */
static void executeDatasetUse(String datasetName) {
    try {
        DatasetRegistry.setActiveDataset(datasetName)
        println "Active dataset switched to: ${DatasetRegistry.getActiveDataset()}"
    } catch (Exception e) {
        println "ERROR: Failed to switch active dataset: ${e.message}"
    }
}

static void executeSetUse(String setName) {
    executeDatasetUse(setName)
}

/**
 * Displays the Datasets table.
 */
static void printDatasets() {
    List<Map> datasets = DatasetRegistry.listDatasets()
    println "=" * 104
    println "Discovered Datasets (${datasets.size()} found):"
    println "=" * 104
    if (datasets.isEmpty()) {
        println "  No datasets defined in ${Config.DATASETS_FILE.absolutePath}"
        return
    }
    printf "  %-16s %-10s %15s %16s %s%n", "Dataset Name", "Status", "Databases Count", "Total Text Size", "Member Databases"
    printf "  %-16s %-10s %15s %16s %s%n", "-" * 16, "-" * 10, "-" * 15, "-" * 16, "-" * 35
    datasets.each { Map s ->
        String status = s.is_active ? "[ACTIVE]" : ""
        printf "  %-16s %-10s %15d %16s  %s%n",
            s.name, status, s.database_count, formatSize(s.total_bytes as long), s.members_formatted
    }
    println "=" * 104
    println ""
}

static void printSets() {
    printDatasets()
}

/**
 * Executes a federated search across all databases in a dataset with RRF fusion.
 */
static void executeFederatedSearch(
    String datasetName,
    String query,
    int limit = 20,
    boolean raw = false,
    int snippetSize = 64,
    List<String> extensions = null,
    String archiveFilter = null
) {
    if (!raw) {
        println "-" * 50
        StringBuilder header = new StringBuilder("Searching Dataset [${datasetName}]: \"${query}\"")
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

    Map res = FederatedEngine.searchDataset(
        datasetName,
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
 * Prints aggregated statistics across all database members in a dataset.
 */
static void printDatasetStats(String datasetName) {
    String setName = datasetName ?: SetRegistry.getActiveSet()
    List<File> dbFiles = SetRegistry.getDatabasesForSet(setName)

    println "=" * 85
    println "Statistics — Dataset: ${setName} (${dbFiles.size()} database members in scope)"
    println "=" * 85

    if (dbFiles.isEmpty()) {
        println "  No database members found for dataset '${setName}'."
        println "=" * 85
        return
    }

    long totalDocs = 0
    long totalContentBytes = 0
    long totalDiskBytes = 0
    int totalArchives = 0

    dbFiles.each { File dbFile ->
        MemoryEngine eng = null
        try {
            eng = new MemoryEngine(dbFile.absolutePath)
            Map stats = eng.getStats()
            long dbDiskBytes = dbFile.exists() ? dbFile.length() : 0
            long docCount = (stats.total_documents ?: 0) as long
            long contentBytes = (stats.total_size_bytes ?: 0) as long

            totalDocs += docCount
            totalContentBytes += contentBytes
            totalDiskBytes += dbDiskBytes

            println "Database Member: ${dbFile.name} (${formatSize(dbDiskBytes)} on disk)"
            println "  Total documents: ${docCount}"
            println "  Total content:   ${formatSize(contentBytes)}"

            if (stats.manifest) {
                stats.manifest.each { m ->
                    totalArchives++
                    String compState = m.compression_state ?: 'uncompressed'
                    printf "    - %-25s %6d docs | %9s text | %-12s | %s%n",
                        m.source_archive, m.ingested_documents, formatSize(m.total_text_bytes as long),
                        compState, m.ingested_at
                }
            }
            println ""
        } catch (Exception e) {
            println "  ${dbFile.name}: Error reading stats - ${e.message}"
        } finally {
            if (eng != null) {
                try { eng.close() } catch (Exception ignored) {}
            }
        }
    }

    println "=" * 85
    printf "Summary for Dataset [%s]: %d DB member(s) | %d archive(s) | %d docs | %s total text%n",
        setName, dbFiles.size(), totalArchives, totalDocs, formatSize(totalContentBytes)
    println "=" * 85
    println ""
}

/**
 * Prints aggregated archives across all database members in a dataset.
 */
static void printDatasetArchives(String datasetName) {
    String setName = datasetName ?: SetRegistry.getActiveSet()
    List<File> dbFiles = SetRegistry.getDatabasesForSet(setName)

    println "=" * 104
    println "Archives — Dataset: ${setName} (${dbFiles.size()} database members in scope)"
    println "=" * 104

    if (dbFiles.isEmpty()) {
        println "  No database members found for dataset '${setName}'."
        println "=" * 104
        return
    }

    printf "  %-16s %-25s %8s %12s %13s %16s %s%n",
        "Database", "Archive Name", "Docs", "Text Size", "Density", "Compressed Size", "Ingested At"
    printf "  %-16s %-25s %8s %12s %13s %16s %s%n",
        "-" * 16, "-" * 25, "-" * 8, "-" * 12, "-" * 13, "-" * 16, "-" * 19

    long totalDocs = 0
    long totalTextBytes = 0
    long totalStoredBytes = 0
    int totalArchives = 0

    dbFiles.each { File dbFile ->
        MemoryEngine eng = null
        try {
            eng = new MemoryEngine(dbFile.absolutePath)
            List<Map> archives = eng.listArchives()
            archives.each { Map a ->
                totalArchives++
                long textBytes = a.live_text_bytes as long
                long storedBytes = a.stored_bytes != null ? (a.stored_bytes as long) : textBytes
                long compDocs = a.compressed_documents as long
                long liveDocs = a.live_documents as long

                totalDocs += liveDocs
                totalTextBytes += textBytes
                totalStoredBytes += storedBytes

                double densityBytes = liveDocs > 0 ? (textBytes / (double) liveDocs) : 0.0
                String densityStr = formatDensity(densityBytes)

                String compDisplay
                if (compDocs == 0 || textBytes == 0) {
                    compDisplay = "0.0% (raw)"
                } else {
                    double ratio = ((textBytes - storedBytes) * 100.0) / (double) textBytes
                    compDisplay = "${formatSize(storedBytes)} (${String.format('%.1f%%', Math.max(0.0, ratio))})"
                }

                printf "  %-16s %-25s %8d %12s %13s %16s %s%n",
                    dbFile.name, a.source_archive, liveDocs, formatSize(textBytes),
                    densityStr, compDisplay, a.ingested_at ?: '-'
            }
        } catch (Exception e) {
            println "  ${dbFile.name}: Error reading archives - ${e.message}"
        } finally {
            if (eng != null) {
                try { eng.close() } catch (Exception ignored) {}
            }
        }
    }

    println "=" * 104
    printf "Total Databases in Scope: %d  |  Total Archives: %d  |  Total Documents: %d  |  Total Content: %s%n",
        dbFiles.size(), totalArchives, totalDocs, formatSize(totalTextBytes)
    println "=" * 104
    println ""
}

/**
 * Prints single-database statistics (when targeted via explicit --db).
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
        printf "  %-30s %12s%n", db.name, formatSize(db.length())
    }
    println ""
}

/**
 * Lists all distinct archives in a single database (when targeted via explicit --db).
 */
static void printArchives(MemoryEngine engine) {
    List<Map> archives = engine.listArchives()
    println "=" * 85
    println "Database Archives (${archives.size()} recorded):"
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
  <query>                  Federated FTS5 search across active dataset
  <query> --db <name>      Direct single-database search (bypasses federation)
  <query> --dataset <name> Federated search across specific dataset

Dataset Management:
  datasets                 List all datasets and member databases in scope
  dataset use <name>       Switch the active default dataset
  dataset create <n> [dbs] Create a new dataset
  dataset delete <name>    Delete a dataset definition (preserves databases)
  dataset rename <old> <n> Rename a dataset
  dataset add-db <set> <d> Add a database identifier to a dataset
  dataset remove-db <s> <d>Remove a database identifier from a dataset

Inspection Commands:
  :stats                   Aggregated statistics for active dataset (or --db)
  :doc <id>                View document content (supports --all, --lines N, --raw)
  :ext <.ext>              List files by extension (e.g. :ext .sql, :ext "")
  :files <pattern>         List files matching SQL LIKE pattern
  :dbs / :databases        List all physical database files in data/
  :datasets / :sets        List all datasets
  :dataset / :use <name>   Switch active dataset in REPL
  :help                    Display this command reference
  :quit                    Exit REPL
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
