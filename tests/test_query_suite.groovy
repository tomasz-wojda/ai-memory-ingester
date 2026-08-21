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
            { out -> out.contains("Searching: \"BusinessPartner\"") && out.contains("Found ") && out.contains("ms") }
        )

        runTest("1.2 Multi-word Search",
            ["query", "customer contract"],
            { out -> out.contains("Searching: \"customer contract\"") && out.contains("Found ") }
        )

        runTest("1.3 Exact Phrase Query",
            ["query", '"CUSTOMER.NEW"'],
            { out -> out.contains("Searching:") && out.contains("Found ") }
        )

        runTest("1.4 Prefix Wildcard Query",
            ["query", '"Provis*"'],
            { out -> out.contains("Searching: \"Provis*\"") && out.contains("Found ") }
        )

        runTest("1.5 Column-Scoped Filter",
            ["query", "file_name:Registry"],
            { out -> out.contains("Searching: \"file_name:Registry\"") && out.contains("Found ") }
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
            { out -> out.contains("Database:") && out.contains("Total documents:") && out.contains("Ingested Archives") }
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
            { out -> out.contains("Searching: \"file_name:Contract\"") && out.contains("(limit: 3, snippet-size: 128)") && out.contains("Found 3 result(s)") }
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
     * @param testName    Descriptive name of the test case
     * @param cliArgs     List of CLI arguments to pass to run.groovy
     * @param validator   Closure accepting output String and returning boolean
     */
    private static void runTest(String testName, List<String> cliArgs, Closure<Boolean> validator) {
        totalTests++
        long startNanos = System.nanoTime()

        try {
            List<String> command = ["cmd.exe", "/c", "groovy", "run.groovy"] + cliArgs
            ProcessBuilder pb = new ProcessBuilder(command)
            pb.directory(new File(".").canonicalFile)
            pb.redirectErrorStream(true)

            Process process = pb.start()
            String output = new String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
            int exitCode = process.waitFor()

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            boolean isPass = (exitCode == 0) && validator.call(output)

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
