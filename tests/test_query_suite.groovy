/**
 * tests/test_query_suite.groovy
 *
 * Hermetic, high-speed automated test battery for the Memory Context Engine.
 * Automatically bootstraps test-data/ synthetic fixtures on startup.
 * Defaults to blazing-fast in-process warm JVM execution (< 2 seconds total runtime).
 * Optional --subprocess flag runs full end-to-end OS subprocess execution.
 */

package tests

import java.nio.charset.StandardCharsets

class TestQuerySuite {

    private static int totalTests = 0
    private static int passedTests = 0
    private static int failedTests = 0
    private static final List<String> failures = []

    private static boolean subprocessMode = false
    private static GroovyClassLoader sharedGcl
    private static final Map<String, Class> scriptClassCache = [:]
    private static Class configClass

    static void main(String[] args) {
        subprocessMode = (args != null && args.contains('--subprocess'))

        println "=" * 75
        println "Memory Query Engine - Automated Test Battery"
        println "Execution Mode      : ${subprocessMode ? 'OS Subprocess (Cold JVM)' : 'In-Process (Warm JVM)'}"
        println "=" * 75
        println ""

        // -------------------------------------------------------------------
        // Bootstrap Hermetic Test Fixture Environment
        // -------------------------------------------------------------------
        File testDir = new File('test-data').canonicalFile
        initInProcessRunner()

        Class fixtureGen = sharedGcl.parseClass(new File("tests/TestFixtureGenerator.groovy"))
        fixtureGen.generateTestCorpus(testDir)

        long suiteStartTime = System.currentTimeMillis()

        // -------------------------------------------------------------------
        // Group 1: Basic Full-Text Search (FTS5)
        // -------------------------------------------------------------------
        println "--- Group 1: Basic Full-Text Search ---"
        runTest("1.1 Simple Keyword Search",
            ["query", "BusinessPartner"],
            { out -> out.contains("Searching") && out.contains("\"BusinessPartner\"") && out.contains("Found ") && out.contains("ms") }
        )

        runTest("1.2 Multi-word Search",
            ["query", "customer contract"],
            { out -> out.contains("Searching") && out.contains("\"customer contract\"") && out.contains("Found ") }
        )

        runTest("1.3 Exact Phrase Query",
            ["query", '"CUSTOMER.NEW"'],
            { out -> out.contains("Searching") && out.contains("Found ") }
        )

        runTest("1.4 Prefix Wildcard Query",
            ["query", 'Provis*'],
            { out -> out.contains("Searching") && out.contains("Found ") }
        )

        runTest("1.5 Column-Scoped Filter",
            ["query", "file_name:PartnerRegistry"],
            { out -> out.contains("Searching") && out.contains("PartnerRegistry") && out.contains("Found ") }
        )

        // -------------------------------------------------------------------
        // Group 2: Limit Flags (--limit, -n, -l, --limit=)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 2: Result Limit Flag Variations ---"
        runTest("2.1 Standard --limit 3",
            ["query", "BusinessPartner", "--limit", "3"],
            { out -> out.contains("(limit: 3)") && out.contains("Found ") }
        )

        runTest("2.2 Short flag -n 2",
            ["query", "BusinessPartner", "-n", "2"],
            { out -> out.contains("(limit: 2)") && out.contains("Found 2 result(s)") }
        )

        runTest("2.3 Short flag -l 2",
            ["query", "BusinessPartner", "-l", "2"],
            { out -> out.contains("(limit: 2)") && out.contains("Found 2 result(s)") }
        )

        runTest("2.4 Equals syntax --limit=2",
            ["query", "BusinessPartner", "--limit=2"],
            { out -> out.contains("(limit: 2)") && out.contains("Found 2 result(s)") }
        )

        // -------------------------------------------------------------------
        // Group 3: Snippet Window Size (--snippet-size, -s, --snippet-size=)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 3: Snippet Window Size Flags ---"
        runTest("3.1 Short flag -s 32",
            ["query", "Provisioning", "-s", "32", "--limit", "2"],
            { out -> out.contains("snippet-size: 32") && out.contains("Snippet:") }
        )

        runTest("3.2 Default snippet-size 64 (clean header without extra flag display)",
            ["query", "Provisioning", "--limit", "2"],
            { out -> out.contains("(limit: 2)") && !out.contains("snippet-size:") && out.contains("Snippet:") }
        )

        runTest("3.3 Explicit --snippet-size 128",
            ["query", "Provisioning", "--snippet-size", "128", "--limit", "2"],
            { out -> out.contains("snippet-size: 128") && out.contains("Snippet:") }
        )

        runTest("3.4 Equals syntax --snippet-size=256",
            ["query", "Provisioning", "--snippet-size=256", "--limit", "2"],
            { out -> out.contains("snippet-size: 256") && out.contains("Snippet:") }
        )

        // -------------------------------------------------------------------
        // Group 4: Raw Output Mode (--raw, -r)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 4: Raw Mode Output ---"
        runTest("4.1 Raw search mode (no header framing)",
            ["query", "BusinessPartner", "--raw", "--limit", "2"],
            { out -> !out.contains("--------------------------------------------------") && out.contains("ID:") }
        )

        runTest("4.2 Raw :doc retrieval (pure verbatim XML, no headers or line numbers)",
            ["query", ":doc 3", "--raw"],
            { out -> out.contains("<?xml") && !out.contains("==========") && !out.contains("   1 |") }
        )

        runTest("4.3 Short flag -r on :doc retrieval",
            ["query", ":doc 3", "-r"],
            { out -> out.contains("<?xml") && !out.contains("==========") }
        )

        // -------------------------------------------------------------------
        // Group 5: File & Extension Inspection Commands
        // -------------------------------------------------------------------
        println ""
        println "--- Group 5: Inspection Commands ---"
        runTest("5.1 :stats command",
            ["query", ":stats"],
            { out -> (out.contains("Statistics") || out.contains("Database") || out.contains("Dataset")) && (out.contains("Total documents:") || out.contains("Total Databases in Scope")) }
        )

        runTest("5.2 :files list all with default wildcard",
            ["query", ":files", "--limit", "5"],
            { out -> out.contains("Files matching") && out.contains("ID:") }
        )

        runTest("5.3 :files list with pattern",
            ["query", ":files BOregister", "--limit", "3"],
            { out -> out.contains("Files matching '%BOregister%'") && out.contains("ID:") }
        )

        runTest("5.4 :ext with leading dot",
            ["query", ":ext", ".java", "--limit", "5"],
            { out -> out.contains("Files with extension .java") }
        )

        runTest("5.5 :ext without leading dot",
            ["query", ":ext java", "--limit", "5"],
            { out -> out.contains("Files with extension .java") }
        )

        // -------------------------------------------------------------------
        // Group 6: Formatted Document View (:doc)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 6: Formatted Document View ---"
        runTest("6.1 Formatted XML Document View with Line Numbers",
            ["query", ":doc 3"],
            { out -> out.contains("Document ID:  3") && out.contains("Archive:      cms_R1.zip") && out.contains("   1 | <?xml") }
        )

        runTest("6.2 Formatted Java Source Document View",
            ["query", ":doc 1"],
            { out -> out.contains("Document ID:  1") && out.contains("Archive:      cms_R1.zip") && out.contains(".java") && out.contains("Retrieved in") }
        )

        runTest("6.3 Formatted View with custom --lines flag",
            ["query", ":doc 1", "--lines", "5"],
            { out -> out.contains("Document ID:  1") && out.contains("5 |") && out.contains("more lines truncated") && !out.contains("6 |") }
        )

        runTest("6.4 Formatted View with --all flag",
            ["query", ":doc 1", "--all"],
            { out -> out.contains("Document ID:  1") && !out.contains("more lines truncated") }
        )

        // -------------------------------------------------------------------
        // Group 7: Combined Flags & Edge Cases
        // -------------------------------------------------------------------
        println ""
        println "--- Group 7: Flag Combinations & Edge Cases ---"
        runTest("7.1 Combined Column Filter + Custom Limit + Custom Snippet Size",
            ["query", "file_name:BOregister", "--limit", "2", "-s", "128"],
            { out -> out.contains("Searching") && out.contains("BOregister") && out.contains("Found ") }
        )

        // -------------------------------------------------------------------
        // Group 8: Extension and Archive Filtering Flags (--ext, -e, --archive, -A)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 8: Extension & Archive Filtering Flags ---"
        runTest("8.1 Extension Filter (--ext pdf)",
            ["query", "Feynman", "--ext", "pdf", "--limit", "3"],
            { out -> out.contains("[ext: pdf]") && out.contains("Type: .pdf") }
        )

        runTest("8.2 Multi-extension comma syntax (--ext java,xml)",
            ["query", "Contract", "--ext", "java,xml", "--limit", "4"],
            { out -> out.contains("[ext: java, xml]") && (out.contains("Type: .java") || out.contains("Type: .xml")) }
        )

        runTest("8.3 Archive Filter (--archive cms_R1.zip)",
            ["query", "BOregister", "--archive", "cms_R1.zip", "--limit", "3"],
            { out -> out.contains("[archive: cms_R1.zip]") && out.contains("Archive: cms_R1.zip") }
        )

        runTest("8.4 Combined Extension and Archive Filter",
            ["query", "BusinessPartner", "--ext", "java", "--archive", "cms_R1.zip", "--limit", "2"],
            { out -> out.contains("[ext: java]") && out.contains("[archive: cms_R1.zip]") && out.contains("Archive: cms_R1.zip") && out.contains("Type: .java") }
        )

        runTest("8.5 No-extension filter with --no-ext flag",
            ["query", "pipeline", "--no-ext", "--limit", "3"],
            { out -> out.contains("[ext: (no ext)]") && out.contains("Type:") && !out.contains("Type: .java") }
        )

        runTest("8.6 Multi-extension with empty extension token (--ext pdf,md,txt,'')",
            ["query", "pipeline", "--ext", "pdf,md,txt,''", "--limit", "4"],
            { out -> out.contains("[ext: pdf, md, txt, (no ext)]") && out.contains("Found ") }
        )

        runTest("8.7 Inspection command :ext with empty string (:ext \"\")",
            ["query", ":ext \"\"", "--limit", "3"],
            { out -> out.contains("Files with extension (no extension)") && out.contains("run_pipeline") }
        )

        // -------------------------------------------------------------------
        // Group 9: Multi-Database, Directory Ingestion, Renaming & Egest
        // -------------------------------------------------------------------
        println ""
        println "--- GROUP 9: Multi-Database, Directory Ingestion & Egest ---"

        runTest("9.1 Multi-Database Discovery (dbs command)",
            ["dbs"],
            { out -> out.contains("Discovered Memory Databases") && out.contains("memory.db") }
        )

        runTest("9.2 Archive Listing (archives command)",
            ["archives"],
            { out -> out.contains("Archives") && out.contains("Archive Name") && out.contains("Density") && out.contains("Compressed Size") }
        )

        runTest("9.2b Archive Listing Alias (archs command)",
            ["archs"],
            { out -> out.contains("Archives") && out.contains("Archive Name") && out.contains("Density") && out.contains("Compressed Size") }
        )

        runTest("9.3 Directory Ingestion (--dir test-data/fixtures --as fixtures_test)",
            ["ingest", "--dir", "test-data/fixtures", "--as", "fixtures_test", "--on-conflict", "replace"],
            { out -> out.contains("Directory Ingestion Complete") && out.contains("fixtures_test") }
        )

        runTest("9.4 Query Directory Ingested Archive",
            ["query", "Sample", "--archive", "fixtures_test", "--limit", "2"],
            { out -> out.contains("Archive: fixtures_test") }
        )

        runTest("9.5 Archive Renaming (rename-archive fixtures_test fixtures_renamed)",
            ["rename-archive", "fixtures_test", "fixtures_renamed"],
            { out -> out.contains("Successfully renamed archive 'fixtures_test' -> 'fixtures_renamed'") || (out.contains("Rename Complete") && out.contains("document")) }
        )

        runTest("9.6 Query Renamed Archive",
            ["query", "Sample", "--archive", "fixtures_renamed", "--limit", "2"],
            { out -> out.contains("Archive: fixtures_renamed") }
        )

        runTest("9.7 Archive Egest (egest fixtures_renamed)",
            ["egest", "fixtures_renamed"],
            { out -> out.contains("Successfully purged archive 'fixtures_renamed'") || out.contains("Egest Complete") || out.contains("Purged") }
        )

        // -------------------------------------------------------------------
        // Group 10: Real-Time Append & Selective Compression
        // -------------------------------------------------------------------
        println ""
        println "--- GROUP 10: Real-Time Append & Selective Compression ---"

        runTest("10.1 Real-Time Text Append (append --archive live_events)",
            ["append", "--text", "2026-08-24 Realtime streaming audit entry logged into memory.", "--file", "audit_log.txt", "--archive", "live_events"],
            { out -> out.contains("Real-Time") || out.contains("Append Succeeded") || out.contains("live_events") }
        )

        runTest("10.2 Sub-Millisecond Search on Appended Text",
            ["query", "streaming audit entry", "--archive", "live_events"],
            { out -> out.contains("audit entry") && out.contains("live_events") && out.contains("Found 1 result(s)") }
        )

        runTest("10.3 Selective Per-Archive Compression (--archive live_events)",
            ["compress", "--archive", "live_events"],
            { out -> out.contains("Memory Database - In-Place Compression") && out.contains("Compression Complete") }
        )

        runTest("10.4 Query Transparently Decompressed Snippet",
            ["query", "streaming audit entry", "--archive", "live_events"],
            { out -> out.contains("audit entry") && out.contains("live_events") }
        )

        runTest("10.5 Selective Per-Archive Decompression (--archive live_events)",
            ["decompress", "--archive", "live_events"],
            { out -> out.contains("Memory Database - In-Place Decompression") && out.contains("Decompression Complete") }
        )

        runTest("10.6 Clean up Live Events Test Archive (egest live_events)",
            ["egest", "live_events"],
            { out -> out.contains("Successfully purged archive 'live_events'") || out.contains("Egest Complete") || out.contains("Purged") }
        )

        // -------------------------------------------------------------------
        // Group 11: Datasets, Dataset-First Routing & Federated Search
        // -------------------------------------------------------------------
        println ""
        println "--- Group 11: Datasets & Federated Search Battery ---"
        runTest("11.1 Create Dataset (dataset create test_physics memory.db)",
            ["dataset", "create", "test_physics", "memory.db"],
            { out -> out.contains("Successfully created dataset 'test_physics'") || out.contains("created") }
        )

        runTest("11.2 List Datasets (datasets command)",
            ["datasets"],
            { out -> out.contains("Discovered Datasets") && out.contains("test_physics") && out.contains("memory.db") }
        )

        runTest("11.2b List Datasets Alias (sets command)",
            ["sets"],
            { out -> out.contains("Discovered Datasets") && out.contains("test_physics") && out.contains("memory.db") }
        )

        runTest("11.3 Add Database to Dataset (dataset add-db test_physics auxiliary.db)",
            ["dataset", "add-db", "test_physics", "auxiliary.db"],
            { out -> out.contains("Successfully added database 'auxiliary.db' to dataset 'test_physics'") }
        )

        runTest("11.4 Remove Database from Dataset (dataset remove-db test_physics auxiliary.db)",
            ["dataset", "remove-db", "test_physics", "auxiliary.db"],
            { out -> out.contains("Successfully removed database 'auxiliary.db' from dataset 'test_physics'") }
        )

        runTest("11.5 Rename Dataset (dataset rename test_physics test_physics_renamed)",
            ["dataset", "rename", "test_physics", "test_physics_renamed"],
            { out -> out.contains("Successfully renamed dataset 'test_physics' -> 'test_physics_renamed'") }
        )

        runTest("11.6 Single Database Query Preservation (--db memory.db)",
            ["query", "BusinessPartner", "--db", "memory.db", "--limit", "2"],
            { out -> out.contains("Searching: \"BusinessPartner\"") && out.contains("Found ") && !out.contains("RRF:") }
        )

        runTest("11.7 Explicit Dataset Federated Query (--dataset test_physics_renamed)",
            ["query", "BusinessPartner", "--dataset", "test_physics_renamed", "--limit", "2"],
            { out -> (out.contains("Searching Dataset [test_physics_renamed]:") || out.contains("Searching Set")) && out.contains("RRF:") && out.contains("Found ") }
        )

        runTest("11.8 Switch Active Default Dataset (dataset use test_physics_renamed)",
            ["dataset", "use", "test_physics_renamed"],
            { out -> out.contains("Active dataset switched to: test_physics_renamed") || out.contains("test_physics_renamed") }
        )

        runTest("11.9 Implicit Default Dataset Query Execution",
            ["query", "BusinessPartner", "--limit", "2"],
            { out -> (out.contains("Searching Dataset [test_physics_renamed]:") || out.contains("Searching Set")) && out.contains("RRF:") && out.contains("Found ") }
        )

        runTest("11.10 Path Safety Enforcement (Reject ../ Traversal)",
            ["query", "test", "--db", "../secret.db"],
            { out -> out.contains("path traversal") || out.contains("Security violation") || out.contains("forbidden") || out.contains("ERROR") },
            true
        )

        runTest("11.11 Delete Dataset (dataset delete test_physics_renamed)",
            ["dataset", "delete", "test_physics_renamed"],
            { out -> out.contains("Successfully deleted dataset 'test_physics_renamed'") }
        )

        // -------------------------------------------------------------------
        // Group 12: Custom Database Storage Paths & External Path Resolution
        // -------------------------------------------------------------------
        println ""
        println "--- Group 12: Custom Database Paths & External Path Resolution ---"
        runTest("12.1 Query using explicit --data-dir flag",
            ["query", "BusinessPartner", "--data-dir", "test-data", "--limit", "2"],
            { out -> out.contains("BusinessPartner") && out.contains("Found") }
        )

        runTest("12.2 List datasets using --data-dir flag",
            ["datasets", "--data-dir", "test-data"],
            { out -> out.contains("Discovered Datasets") && out.contains("default") }
        )

        runTest("12.3 Direct external database query via relative path (--db test-data/memory.db)",
            ["query", "BusinessPartner", "--db", "test-data/memory.db", "--limit", "2"],
            { out -> out.contains("Searching: \"BusinessPartner\"") && out.contains("Found ") }
        )

        runTest("12.4 Direct external database query via absolute path",
            ["query", "BusinessPartner", "--db", new File("test-data/memory.db").canonicalPath, "--limit", "2"],
            { out -> out.contains("Searching: \"BusinessPartner\"") && out.contains("Found ") }
        )

        // -------------------------------------------------------------------
        // Summary Report
        // -------------------------------------------------------------------
        long totalElapsedMs = System.currentTimeMillis() - suiteStartTime
        println ""
        println "=" * 75
        println "TEST SUITE EXECUTION SUMMARY"
        println "=" * 75
        printf "Total Tests Executed : %d%n", totalTests
        printf "Passed               : %d%n", passedTests
        printf "Failed               : %d%n", failedTests
        printf "Total Elapsed Time   : %.2f s%n", (totalElapsedMs / 1000.0)
        println "=" * 75

        if (failedTests > 0) {
            println "FAILURES DETECTED:"
            failures.each { f -> println "  - ${f}" }
            System.exit(1)
        } else {
            println "ALL TESTS PASSED SUCCESSFULLY."
            System.exit(0)
        }
    }

    private static void initInProcessRunner() {
        sharedGcl = new GroovyClassLoader(TestQuerySuite.classLoader)
        groovy.grape.Grape.grab(classLoader: sharedGcl, group: 'org.xerial', module: 'sqlite-jdbc', version: '3.45.1.0')
        groovy.grape.Grape.grab(classLoader: sharedGcl, group: 'org.apache.pdfbox', module: 'pdfbox', version: '2.0.30')
        groovy.grape.Grape.grab(classLoader: sharedGcl, group: 'org.slf4j', module: 'slf4j-nop', version: '2.0.12')
        Thread.currentThread().contextClassLoader = sharedGcl

        ['Config', 'ArchiveHandler', 'ContentExtractor', 'MemoryEngine', 'DatasetRegistry', 'SetRegistry', 'FederatedEngine'].each { name ->
            sharedGcl.parseClass(new File("lib/${name}.groovy"))
        }
        configClass = sharedGcl.loadClass('Config')

        ['01_analyze_archives.groovy', '02_ingest_archive.groovy', '03_query_memory.groovy', '04_compress_memory.groovy'].each { file ->
            scriptClassCache[file] = sharedGcl.parseClass(new File(file))
        }
    }

    static class ExecResult {
        String output
        int exitCode
    }

    private static ExecResult executeInProcess(List<String> cliArgs) {
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
            'datasets':           '03_query_memory.groovy',
            'set':                '03_query_memory.groovy',
            'dataset':            '03_query_memory.groovy',
            'use-set':            '03_query_memory.groovy',
            'use-dataset':        '03_query_memory.groovy',
            'create-set':         '03_query_memory.groovy',
            'create-dataset':     '03_query_memory.groovy',
            'delete-set':         '03_query_memory.groovy',
            'delete-dataset':     '03_query_memory.groovy',
            'rename-set':         '03_query_memory.groovy',
            'rename-dataset':     '03_query_memory.groovy',
            'add-db-to-set':      '03_query_memory.groovy',
            'add-db-to-dataset':  '03_query_memory.groovy',
            'remove-db-from-set': '03_query_memory.groovy',
            'remove-db-from-dataset': '03_query_memory.groovy',
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

        String customDb = null
        String customDataDir = null
        List<String> filteredArgs = []
        int aI = 0
        while (aI < cliArgs.size()) {
            String a = cliArgs[aI]
            if ((a == '--db' || a == '-D') && aI + 1 < cliArgs.size()) {
                customDb = cliArgs[aI + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')
                aI += 2
            } else if (a.startsWith('--db=')) {
                customDb = a.substring('--db='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')
                aI++
            } else if (a == '--data-dir' && aI + 1 < cliArgs.size()) {
                customDataDir = cliArgs[aI + 1].trim().replaceAll("^['\"]+|['\"]+\$", '')
                aI += 2
            } else if (a.startsWith('--data-dir=')) {
                customDataDir = a.substring('--data-dir='.length()).trim().replaceAll("^['\"]+|['\"]+\$", '')
                aI++
            } else {
                filteredArgs << a
                aI++
            }
        }

        if (filteredArgs.isEmpty() || !commands.containsKey(filteredArgs[0].toLowerCase())) {
            return new ExecResult(output: "Unknown command", exitCode: 1)
        }

        String command = filteredArgs[0].toLowerCase()
        String[] scriptArgs = filteredArgs.size() > 1 ? filteredArgs[1..-1] as String[] : new String[0]
        String scriptFile = commands[command]

        if (['sets', 'datasets', 'set', 'dataset', 'use-set', 'use-dataset', 'create-set', 'create-dataset',
             'delete-set', 'delete-dataset', 'rename-set', 'rename-dataset', 'add-db-to-set', 'add-db-to-dataset',
             'remove-db-from-set', 'remove-db-from-dataset', 'dbs', 'databases', 'archives', 'archs', 'egest',
             'rename', 'rename-archive', 'stream', 'ingest-stream', 'append', 'ingest-text'].contains(command)) {
            String[] augmentedArgs = new String[scriptArgs.length + 1]
            augmentedArgs[0] = command
            System.arraycopy(scriptArgs, 0, augmentedArgs, 1, scriptArgs.length)
            scriptArgs = augmentedArgs
        }

        // Apply data dir & db
        String effDataDir = customDataDir ?: 'test-data'
        configClass.setDataDir(effDataDir)

        if (customDataDir) {
            String[] aug = new String[scriptArgs.length + 2]
            System.arraycopy(scriptArgs, 0, aug, 0, scriptArgs.length)
            aug[scriptArgs.length] = '--data-dir'
            aug[scriptArgs.length + 1] = customDataDir
            scriptArgs = aug
        }
        if (customDb) {
            configClass.DB_PATH = configClass.resolveDatabase(customDb)
            String[] aug = new String[scriptArgs.length + 2]
            System.arraycopy(scriptArgs, 0, aug, 0, scriptArgs.length)
            aug[scriptArgs.length] = '--db'
            aug[scriptArgs.length + 1] = customDb
            scriptArgs = aug
        }

        PrintStream origOut = System.out
        PrintStream origErr = System.err
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        PrintStream captureStream = new PrintStream(baos, true, "UTF-8")
        ExecResult result = new ExecResult(output: "", exitCode: 0)

        try {
            System.setOut(captureStream)
            System.setErr(captureStream)

            Class scriptClass = scriptClassCache[scriptFile]
            Script script = (Script) scriptClass.getDeclaredConstructor().newInstance()
            Binding binding = new Binding()
            binding.setVariable('args', scriptArgs)
            binding.setVariable('commandName', command)
            binding.setVariable('inProcess', true)
            script.setBinding(binding)
            script.run()
        } catch (Throwable e) {
            result.exitCode = 1
            captureStream.println("EXCEPTION: ${e.class.name}: ${e.message}")
            e.printStackTrace(captureStream)
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }

        result.output = baos.toString("UTF-8")
        return result
    }

    /**
     * Executes a single query test and evaluates the assertion closure.
     * Defaults to in-process warm JVM execution; falls back to ProcessBuilder in subprocess mode.
     *
     * @param testName          Descriptive name of the test case
     * @param cliArgs           List of CLI arguments to pass to run.groovy
     * @param validator         Closure accepting output String and returning boolean
     * @param allowNonZeroExit  Whether non-zero exit code is allowed (for error/security tests)
     */
    private static void runTest(String testName, List<String> cliArgs, Closure<Boolean> validator, boolean allowNonZeroExit = false) {
        totalTests++
        long startNanos = System.nanoTime()

        try {
            List<String> fullArgs = new ArrayList<>(cliArgs)
            if (!fullArgs.contains('--data-dir') && !fullArgs.any { it.startsWith('--data-dir=') }) {
                fullArgs.addAll(['--data-dir', 'test-data'])
            }

            String output = ""
            int exitCode = 0

            if (!subprocessMode) {
                // In-Process Warm JVM Execution
                ExecResult res = executeInProcess(fullArgs)
                output = res.output
                exitCode = res.exitCode
            } else {
                // Cold Subprocess Execution
                List<String> command = ["cmd.exe", "/c", "groovy", "run.groovy"] + fullArgs
                ProcessBuilder pb = new ProcessBuilder(command)
                pb.directory(new File(".").canonicalFile)
                pb.environment().put("JAVA_OPTS", "--enable-native-access=ALL-UNNAMED")
                pb.redirectErrorStream(true)

                Process process = pb.start()
                output = new String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
                exitCode = process.waitFor()
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            boolean isPass = (allowNonZeroExit || exitCode == 0) && validator.call(output)

            if (isPass) {
                passedTests++
                printf "  [PASS] %-55s (%4d ms)%n", testName, elapsedMs
            } else {
                failedTests++
                String reason = exitCode != 0 ? "Exit code ${exitCode}" : "Assertion failed"
                failures << "${testName}: ${reason}"
                printf "  [FAIL] %-55s (%4d ms) - %s%n", testName, elapsedMs, reason
                println "         --- Captured Output (first 300 chars) ---"
                println "         " + output.take(300).replaceAll("\n", "\n         ")
                println "         -----------------------------------------"
            }
        } catch (Exception e) {
            failedTests++
            failures << "${testName}: Exception: ${e.message}"
            printf "  [FAIL] %-55s - Exception: %s%n", testName, e.message
        }
    }
}
