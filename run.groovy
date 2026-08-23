System.setProperty('slf4j.internal.verbosity', 'ERROR')
@Grab('org.xerial:sqlite-jdbc:3.45.1.0')
@Grab('org.slf4j:slf4j-nop:2.0.12')
import groovy.sql.Sql

/**
 * run.groovy
 *
 * Entry-point runner for the Memory Context Engine.
 * Compiles all library sources and the target script together
 * using a shared GroovyClassLoader, ensuring all classes are
 * visible to each other at both compile-time and runtime.
 *
 * Usage:
 *   groovy run.groovy analyze          — Phase 1: Analyze archives
 *   groovy run.groovy ingest [name]    — Phase 2+4: Ingest archive (use 'all' for all archives)
 *   groovy run.groovy query [terms]    — Phase 4: Query memory (interactive if no terms)
 *
 * This replaces direct invocation of 01_/02_/03_ scripts.
 */

// -----------------------------------------------------------------------
// Map subcommand to script file
// -----------------------------------------------------------------------

Map<String, String> commands = [
    'analyze':            '01_analyze_archives.groovy',
    '01':                 '01_analyze_archives.groovy',
    'ingest':             '02_ingest_archive.groovy',
    '02':                 '02_ingest_archive.groovy',
    'query':              '03_query_memory.groovy',
    '03':                 '03_query_memory.groovy',
    'compress':           '04_compress_memory.groovy',
    'decompress':         '04_compress_memory.groovy',
    'uncompress':         '04_compress_memory.groovy',
    '04':                 '04_compress_memory.groovy',
    'sets':               '03_query_memory.groovy',
    'set':                '03_query_memory.groovy',
    'use-set':            '03_query_memory.groovy',
    'create-set':         '03_query_memory.groovy',
    'delete-set':         '03_query_memory.groovy',
    'rename-set':         '03_query_memory.groovy',
    'add-db-to-set':      '03_query_memory.groovy',
    'remove-db-from-set': '03_query_memory.groovy',
    'dbs':                '03_query_memory.groovy',
    'databases':          '03_query_memory.groovy',
    'archives':           '03_query_memory.groovy',
    'archs':              '03_query_memory.groovy',
    'egest':              '03_query_memory.groovy',
    'rename':             '03_query_memory.groovy',
    'rename-archive':     '03_query_memory.groovy',
    'stream':             '02_ingest_archive.groovy',
    'ingest-stream':      '02_ingest_archive.groovy',
    'append':             '02_ingest_archive.groovy',
    'ingest-text':        '02_ingest_archive.groovy'
]

// Extract global --db flag if present
String customDb = null
List<String> rawArgsList = args as List
List<String> filteredArgs = []
int aI = 0
while (aI < rawArgsList.size()) {
    String a = rawArgsList[aI]
    if ((a == '--db' || a == '-D') && aI + 1 < rawArgsList.size()) {
        customDb = rawArgsList[aI + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')
        aI += 2
    } else if (a.startsWith('--db=')) {
        customDb = a.substring('--db='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')
        aI++
    } else {
        filteredArgs << a
        aI++
    }
}

if (filteredArgs.isEmpty() || !commands.containsKey(filteredArgs[0].toLowerCase())) {
    println "Archive Memory Context Engine"
    println ""
    println "Usage: groovy run.groovy <command> [args] [--db <name_or_path>] [--set <set_name>]"
    println ""
    println "Core Commands:"
    println "  analyze [--dir <path>]         Analyze archives or uncompressed folder"
    println "  ingest [name|all|--dir <path>] Ingest archives or directory into memory DB"
    println "  query [terms]                  Query the memory DB / sets (supports --set, --db, --ext, --limit)"
    println "  compress [--archive <name>]    Compress documents in-place (zlib + VACUUM)"
    println "  decompress [--archive <name>]  Decompress documents in-place to UTF-8 plaintext"
    println ""
    println "Database Set & Federation Commands:"
    println "  sets                           List all database sets and member databases"
    println "  set use <name>                 Switch active default database set"
    println "  set create <name> [dbs...]     Create a new database set"
    println "  set delete <name>              Delete a database set definition (preserves databases)"
    println "  set rename <old> <new>         Rename a database set"
    println "  set add-db <set> <db>          Add database to a set"
    println "  set remove-db <set> <db>       Remove database from a set"
    println ""
    println "Management & Streaming Commands:"
    println "  dbs / databases                List all discovered databases in data/ directory"
    println "  archs / archives               List all archives in active database"
    println "  egest <archive_name>           Purge archive and clear FTS5 index"
    println "  rename-archive <old> <new>     Rename archive metadata across documents and manifest"
    println "  stream --archive <name>        Ingest/stream text from stdin pipe"
    println "  append --text <txt> --file <f> Append text chunk to living document"
    println ""
    System.exit(1)
}

String command = filteredArgs[0].toLowerCase()
String[] scriptArgs = filteredArgs.size() > 1 ? filteredArgs[1..-1] as String[] : new String[0]
String scriptFile = commands[command]

// Inject commandName into sub-script if needed
if (['sets', 'set', 'use-set', 'create-set', 'delete-set', 'rename-set', 'add-db-to-set', 'remove-db-from-set',
     'dbs', 'databases', 'archives', 'archs', 'egest', 'rename', 'rename-archive', 'stream', 'ingest-stream', 'append', 'ingest-text'].contains(command)) {
    String[] augmentedArgs = new String[scriptArgs.length + 1]
    augmentedArgs[0] = command
    System.arraycopy(scriptArgs, 0, augmentedArgs, 1, scriptArgs.length)
    scriptArgs = augmentedArgs
}

// -----------------------------------------------------------------------
// Compile library sources into a shared classloader
// -----------------------------------------------------------------------

// Create shared classloader and resolve SQLite JDBC into it
GroovyClassLoader gcl = new GroovyClassLoader(this.class.classLoader)

// Resolve SQLite JDBC, PDFBox, and SLF4J NOP dependencies into the shared classloader
groovy.grape.Grape.grab(
    classLoader: gcl,
    group: 'org.xerial', module: 'sqlite-jdbc', version: '3.45.1.0'
)
groovy.grape.Grape.grab(
    classLoader: gcl,
    group: 'org.apache.pdfbox', module: 'pdfbox', version: '2.0.30'
)
groovy.grape.Grape.grab(
    classLoader: gcl,
    group: 'org.slf4j', module: 'slf4j-nop', version: '2.0.12'
)

// Load library modules in dependency order
['Config', 'ArchiveHandler', 'ContentExtractor', 'MemoryEngine', 'SetRegistry', 'FederatedEngine'].each { name ->
    gcl.parseClass(new File("lib/${name}.groovy"))
}

Class configClass = gcl.loadClass('Config')
if (customDb) {
    configClass.DB_PATH = configClass.resolveDatabase(customDb)
    String[] augmentedWithDb = new String[scriptArgs.length + 2]
    System.arraycopy(scriptArgs, 0, augmentedWithDb, 0, scriptArgs.length)
    augmentedWithDb[scriptArgs.length] = '--db'
    augmentedWithDb[scriptArgs.length + 1] = customDb
    scriptArgs = augmentedWithDb
}

// -----------------------------------------------------------------------
// Compile and execute the target script
// -----------------------------------------------------------------------

Class scriptClass = gcl.parseClass(new File(scriptFile))

// Set thread context classloader so DriverManager can discover the SQLite JDBC driver
Thread.currentThread().contextClassLoader = gcl

// Create a Binding with the script arguments
Binding binding = new Binding()
binding.setVariable('args', scriptArgs)
binding.setVariable('commandName', command)

// Instantiate and run the script
Script script = (Script) scriptClass.getDeclaredConstructor().newInstance()
script.setBinding(binding)
script.run()
