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
    'analyze':    '01_analyze_archives.groovy',
    '01':         '01_analyze_archives.groovy',
    'ingest':     '02_ingest_archive.groovy',
    '02':         '02_ingest_archive.groovy',
    'query':      '03_query_memory.groovy',
    '03':         '03_query_memory.groovy',
    'compress':   '04_compress_memory.groovy',
    'decompress': '04_compress_memory.groovy',
    'uncompress': '04_compress_memory.groovy',
    '04':         '04_compress_memory.groovy'
]

if (args.length == 0 || !commands.containsKey(args[0])) {
    println "Archive Memory Context Engine"
    println ""
    println "Usage: groovy run.groovy <command> [args]"
    println ""
    println "Commands:"
    println "  analyze          Analyze all archives in configured directory"
    println "  ingest [name]    Ingest archive into memory DB (use 'all' for all archives)"
    println "  query [terms]    Query the memory DB (supports --ext, --no-ext, --archive, --limit, :doc)"
    println "  compress         Compress existing documents in-place (reduces disk size)"
    println "  decompress       Decompress existing documents in-place (maximizes speed)"
    println ""
    System.exit(1)
}

String command = args[0]
String[] scriptArgs = args.length > 1 ? args[1..-1] as String[] : new String[0]
String scriptFile = commands[command]

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
['Config', 'ArchiveHandler', 'ContentExtractor', 'MemoryEngine'].each { name ->
    gcl.parseClass(new File("lib/${name}.groovy"))
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
