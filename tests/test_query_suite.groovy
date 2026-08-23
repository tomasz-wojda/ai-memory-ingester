/**
 * tests/test_query_suite.groovy
 *
 * Automated test suite for the Memory Query Engine (03_query_memory.groovy).
 * Validates FTS5 search capabilities, CLI flags (--limit, -n, -l, --snippet-size, -s, --raw, -r),
 * inspection commands (:stats, :files, :ext, :doc), and multi-flag combinations.
 */

package tests

import java.nio.charset.StandardCharsets

class TestQuerySuite {

    private static int totalTests = 0
    private static int passedTests = 0
    private static int failedTests = 0
    private static final List<String> failures = []

    static void main(String[] args) {
        println "=" * 75
        println "Memory Query Engine - Automated Test Battery"
        println "=" * 75
        println ""

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
            ["query", '"Provis*"'],
            { out -> out.contains("Searching") && out.contains("Found ") }
        )

        runTest("1.5 Column-Scoped Filter",
            ["query", "file_name:Registry"],
            { out -> out.contains("Searching") && out.contains("Registry") && out.contains("Found ") }
        )

        // -------------------------------------------------------------------
        // Group 2: Limit Flags (--limit, -n, -l, --limit=)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 2: Result Limit Flag Variations ---"
        runTest("2.1 Standard --limit 5",
            ["query", "BusinessPartner", "--limit", "5"],
            { out -> out.contains("(limit: 5)") && out.contains("Found 5 result(s)") }
        )

        runTest("2.2 Short flag -n 3",
            ["query", "BusinessPartner", "-n", "3"],
            { out -> out.contains("(limit: 3)") && out.contains("Found 3 result(s)") }
        )

        runTest("2.3 Short flag -l 2",
            ["query", "BusinessPartner", "-l", "2"],
            { out -> out.contains("(limit: 2)") && out.contains("Found 2 result(s)") }
        )

        runTest("2.4 Equals syntax --limit=4",
            ["query", "BusinessPartner", "--limit=4"],
            { out -> out.contains("(limit: 4)") && out.contains("Found 4 result(s)") }
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
            ["query", ":doc 398", "--raw"],
            { out -> out.contains("<?xml") && !out.contains("==========") && !out.contains("   1 |") }
        )

        runTest("4.3 Short flag -r on :doc retrieval",
            ["query", ":doc 398", "-r"],
            { out -> out.contains("<?xml") && !out.contains("==========") }
        )

        // -------------------------------------------------------------------
        // Group 5: File & Extension Inspection Commands
        // -------------------------------------------------------------------
        println ""
        println "--- Group 5: Inspection Commands ---"
        runTest("5.1 :stats command",
            ["query", ":stats"],
            { out -> (out.contains("Statistics") || out.contains("Database")) && out.contains("Total documents:") }
        )

        runTest("5.2 :files list all with default wildcard",
            ["query", ":files", "--limit", "5"],
            { out -> out.contains("Files matching '%' (5 shown):") && out.contains("ID:") }
        )

        runTest("5.3 :files list with pattern",
            ["query", ":files Address", "--limit", "3"],
            { out -> out.contains("Files matching '%Address%' (3 shown):") && out.contains("ID:") }
        )

        runTest("5.4 :ext with leading dot",
            ["query", ":ext", ".java", "--limit", "5"],
            { out -> out.contains("Files with extension .java (5 shown):") }
        )

        runTest("5.5 :ext without leading dot",
            ["query", ":ext java", "--limit", "5"],
            { out -> out.contains("Files with extension .java (5 shown):") }
        )

        // -------------------------------------------------------------------
        // Group 6: Formatted Document View (:doc)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 6: Formatted Document View ---"
        runTest("6.1 Formatted XML Document View with Line Numbers",
            ["query", ":doc 398"],
            { out -> out.contains("Document ID:  398") && out.contains("Archive:      cms_R1.zip") && out.contains("   1 | <?xml") }
        )

        runTest("6.2 Formatted Java Source Document View",
            ["query", ":doc 638"],
            { out -> out.contains("Document ID:  638") && out.contains("Archive:      cms_R1.zip") && out.contains(".java") && out.contains("Retrieved in") }
        )

        runTest("6.3 Formatted View with custom --lines flag",
            ["query", ":doc 638", "--lines", "10"],
            { out -> out.contains("Document ID:  638") && out.contains("10 |") && out.contains("more lines truncated") && !out.contains("11 |") }
        )

        runTest("6.4 Formatted View with --all flag",
            ["query", ":doc 638", "--all"],
            { out -> out.contains("Document ID:  638") && !out.contains("more lines truncated") }
        )

        // -------------------------------------------------------------------
        // Group 7: Combined Flags & Edge Cases
        // -------------------------------------------------------------------
        println ""
        println "--- Group 7: Flag Combinations & Edge Cases ---"
        runTest("7.1 Combined Column Filter + Custom Limit + Custom Snippet Size",
            ["query", "file_name:Contract", "--limit", "3", "-s", "128"],
            { out -> out.contains("Searching") && out.contains("\"file_name:Contract\"") && out.contains("(limit: 3, snippet-size: 128)") && out.contains("Found 3 result(s)") }
        )

        // -------------------------------------------------------------------
        // Group 8: Extension and Archive Filtering Flags (--ext, -e, --archive, -A)
        // -------------------------------------------------------------------
        println ""
        println "--- Group 8: Extension & Archive Filtering Flags ---"
        runTest("8.1 Extension Filter (--ext pdf)",
            ["query", "Provisioning", "--ext", "pdf", "--limit", "3"],
            { out -> out.contains("[ext: pdf]") && out.contains("Type: .pdf") && !out.contains("Type: .java") }
        )

        runTest("8.2 Multi-extension comma syntax (--ext java,xml)",
            ["query", "Contract", "--ext", "java,xml", "--limit", "4"],
            { out -> out.contains("[ext: java, xml]") && (out.contains("Type: .java") || out.contains("Type: .xml")) && !out.contains("Type: .pdf") }
        )

        runTest("8.3 Archive Filter (--archive cms_R1.zip)",
            ["query", "ContractTaskHandler", "--archive", "cms_R1.zip", "--limit", "3"],
            { out -> out.contains("[archive: cms_R1.zip]") && out.contains("Archive: cms_R1.zip") }
        )

        runTest("8.4 Combined Extension and Archive Filter",
            ["query", "Contract", "--ext", "java", "--archive", "cms_R1.zip", "--limit", "2"],
            { out -> out.contains("[ext: java]") && out.contains("[archive: cms_R1.zip]") && out.contains("Archive: cms_R1.zip") && out.contains("Type: .java") }
        )

        runTest("8.5 No-extension filter with --no-ext flag",
            ["query", "all", "--no-ext", "--limit", "3"],
            { out -> out.contains("[ext: (no ext)]") && out.contains("Type:") && !out.contains("Type: .java") }
        )

        runTest("8.6 Multi-extension with empty extension token (--ext pdf,md,txt,'')",
            ["query", "Provisioning", "--ext", "pdf,md,txt,''", "--limit", "4"],
            { out -> out.contains("[ext: pdf, md, txt, (no ext)]") && (out.contains("Type: .pdf") || out.contains("Type:")) }
        )

        runTest("8.7 Inspection command :ext with empty string (:ext \"\")",
            ["query", ":ext \"\"", "--limit", "3"],
            { out -> out.contains("Files with extension (no extension)") && out.contains("Makefile") }
        )

        // -------------------------------------------------------------------
        // Group 9: Multi-Database, Directory Ingestion, Renaming & Egest
        // -------------------------------------------------------------------
        println "\n--- GROUP 9: Multi-Database, Directory Ingestion & Egest ---"

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

        runTest("9.3 Directory Ingestion (--dir lib --as lib_test)",
            ["ingest", "--dir", "lib", "--as", "lib_test", "--on-conflict", "replace"],
            { out -> out.contains("Directory Ingestion Complete") && out.contains("lib_test") }
        )

        runTest("9.4 Query Directory Ingested Archive",
            ["query", "Config", "--archive", "lib_test", "--limit", "2"],
            { out -> out.contains("Archive: lib_test") }
        )

        runTest("9.5 Archive Renaming (rename-archive lib_test lib_renamed)",
            ["rename-archive", "lib_test", "lib_renamed"],
            { out -> out.contains("Rename Complete") }
        )

        runTest("9.6 Query Renamed Archive",
            ["query", "Config", "--archive", "lib_renamed", "--limit", "2"],
            { out -> out.contains("Archive: lib_renamed") }
        )

        runTest("9.7 Archive Egest (egest lib_renamed)",
            ["egest", "lib_renamed"],
            { out -> out.contains("Egest Complete") && out.contains("cleared FTS5 index") }
        )

        // -------------------------------------------------------------------
        // Group 10: Real-Time Append, Stdin Stream & Per-Archive Compression
        // -------------------------------------------------------------------
        println "\n--- GROUP 10: Real-Time Append & Selective Compression ---"

        runTest("10.1 Real-Time Text Append (append --archive live_events)",
            ["append", "--archive", "live_events", "--file", "syslog.txt", "--text", "ERR_DATABASE_TIMEOUT occurred on node-04"],
            { out -> out.contains("Successfully ingested/appended document") && out.contains("live_events") }
        )

        runTest("10.2 Sub-Millisecond Search on Appended Text",
            ["query", "ERR_DATABASE_TIMEOUT", "--archive", "live_events"],
            { out -> out.contains("ERR_DATABASE_TIMEOUT") && out.contains("Archive: live_events") }
        )

        runTest("10.3 Selective Per-Archive Compression (--archive live_events)",
            ["compress", "--archive", "live_events"],
            { out -> out.contains("Compression Complete:") && out.contains("live_events") }
        )

        runTest("10.4 Query Transparently Decompressed Snippet",
            ["query", "ERR_DATABASE_TIMEOUT", "--archive", "live_events"],
            { out -> out.contains("ERR_DATABASE_TIMEOUT") && out.contains("Archive: live_events") }
        )

        runTest("10.5 Selective Per-Archive Decompression (--archive live_events)",
            ["decompress", "--archive", "live_events"],
            { out -> out.contains("Decompression Complete:") && out.contains("live_events") }
        )

        runTest("10.6 Clean up Live Events Test Archive (egest live_events)",
            ["egest", "live_events"],
            { out -> out.contains("Egest Complete") }
        )

        // -------------------------------------------------------------------
        // Group 11: Database Sets, Set-First Routing & Federated Search
        // -------------------------------------------------------------------
        println ""
        println "--- Group 11: Database Sets & Federated Search Battery ---"
        runTest("11.1 Create Database Set (set create test_physics memory.db)",
            ["set", "create", "test_physics", "memory.db"],
            { out -> out.contains("Successfully created database set 'test_physics'") }
        )

        runTest("11.2 List Database Sets (sets command)",
            ["sets"],
            { out -> out.contains("Discovered Database Sets") && out.contains("test_physics") && out.contains("memory.db") }
        )

        runTest("11.2b List Database Sets Alias (datasets command)",
            ["datasets"],
            { out -> out.contains("Discovered Database Sets") && out.contains("test_physics") && out.contains("memory.db") }
        )

        runTest("11.3 Add Database to Set (set add-db test_physics auxiliary.db)",
            ["set", "add-db", "test_physics", "auxiliary.db"],
            { out -> out.contains("Successfully added database 'auxiliary.db' to set 'test_physics'") }
        )

        runTest("11.4 Remove Database from Set (set remove-db test_physics auxiliary.db)",
            ["set", "remove-db", "test_physics", "auxiliary.db"],
            { out -> out.contains("Successfully removed database 'auxiliary.db' from set 'test_physics'") }
        )

        runTest("11.5 Rename Database Set (set rename test_physics test_physics_renamed)",
            ["set", "rename", "test_physics", "test_physics_renamed"],
            { out -> out.contains("Successfully renamed database set 'test_physics' -> 'test_physics_renamed'") }
        )

        runTest("11.6 Single Database Query Preservation (--db memory.db)",
            ["query", "BusinessPartner", "--db", "memory.db", "--limit", "3"],
            { out -> out.contains("Searching: \"BusinessPartner\"") && out.contains("Found 3 result(s)") && !out.contains("RRF:") }
        )

        runTest("11.7 Explicit Set Federated Query (--set test_physics_renamed)",
            ["query", "BusinessPartner", "--set", "test_physics_renamed", "--limit", "3"],
            { out -> out.contains("Searching Set [test_physics_renamed]:") && out.contains("RRF:") && out.contains("Found 3 result(s)") }
        )

        runTest("11.8 Switch Active Default Set (set use test_physics_renamed)",
            ["set", "use", "test_physics_renamed"],
            { out -> out.contains("Active database set switched to: test_physics_renamed") }
        )

        runTest("11.9 Implicit Default Set Query Execution",
            ["query", "BusinessPartner", "--limit", "3"],
            { out -> out.contains("Searching Set [test_physics_renamed]:") && out.contains("RRF:") && out.contains("Found 3 result(s)") }
        )

        runTest("11.10 Path Safety Enforcement (Reject ../ Traversal)",
            ["query", "test", "--db", "../secret.db"],
            { out -> out.contains("path traversal") || out.contains("Security violation") || out.contains("forbidden") || out.contains("ERROR") },
            true
        )

        runTest("11.11 Delete Database Set (set delete test_physics_renamed)",
            ["set", "delete", "test_physics_renamed"],
            { out -> out.contains("Successfully deleted database set 'test_physics_renamed'") }
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

    /**
     * Executes a single query test via subprocess and evaluates the assertion closure.
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
            List<String> command = ["cmd.exe", "/c", "groovy", "run.groovy"] + cliArgs
            ProcessBuilder pb = new ProcessBuilder(command)
            pb.directory(new File(".").canonicalFile)
            pb.environment().put("JAVA_OPTS", "--enable-native-access=ALL-UNNAMED")
            pb.redirectErrorStream(true)

            Process process = pb.start()
            String output = new String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
            int exitCode = process.waitFor()

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
