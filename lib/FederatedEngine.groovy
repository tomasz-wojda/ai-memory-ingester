/**
 * FederatedEngine.groovy
 *
 * Bounded Parallel Federated Query Engine & Reciprocal Rank Fusion (RRF) for Database Sets.
 * Executes search queries concurrently across member databases in a set, clamps candidate batches,
 * isolates sub-database execution failures, and computes deterministic Reciprocal Rank Fusion (RRF)
 * to produce unified, multi-database search rankings with origin attribution.
 *
 * Role in Project:
 * Core query coordination and result fusion module for Phase 3 federated search. Loaded via classpath
 * by CLI runners (`03_query_memory.groovy`).
 */

import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class FederatedEngine {

    /** Maximum parallel database worker threads. */
    static final int MAX_CONCURRENCY = 8

    /** Timeout in milliseconds per database sub-query. */
    static final long DATABASE_TIMEOUT_MS = 2000L

    /** Number of candidate matches to retrieve per member database for fusion. */
    static final int CANDIDATES_PER_DATABASE = 50

    /** RRF smoothing constant (standard: 60). */
    static final double RRF_K = 60.0

    /**
     * Executes a federated search across all member databases of a specified dataset.
     *
     * @param datasetName Name of the target dataset (null for active default dataset)
     * @param query Search expression (FTS5 syntax)
     * @param limit Maximum final results to return (default: 20)
     * @param extList Optional list of extension filters
     * @param noExt Whether to filter only extension-less files
     * @param archiveName Optional archive name filter
     * @param snippetTokenSize Approximate token window size for context excerpts
     * @return Map containing fused results and performance diagnostics
     */
    static Map searchDataset(
        String datasetName,
        String query,
        int limit = 20,
        List<String> extList = null,
        boolean noExt = false,
        String archiveName = null,
        int snippetTokenSize = 64
    ) {
        long startNano = System.nanoTime()
        String activeDatasetName = datasetName ?: DatasetRegistry.getActiveDataset()
        List<File> dbFiles = DatasetRegistry.getDatabasesForDataset(activeDatasetName)

        if (dbFiles.isEmpty()) {
            return [
                dataset_name: activeDatasetName,
                set_name: activeDatasetName,
                results: [],
                total_results: 0,
                database_count: 0,
                successful_databases: 0,
                failed_databases: [:],
                database_timings: [:],
                fusion_duration_ms: 0.0,
                total_duration_ms: (System.nanoTime() - startNano) / 1_000_000.0
            ]
        }

        // Deduplicate database files by canonical path
        Map<String, File> uniqueDbs = [:]
        dbFiles.each { File f ->
            uniqueDbs[f.name] = f
        }

        int poolSize = Math.min(MAX_CONCURRENCY, uniqueDbs.size())
        ExecutorService executor = Executors.newFixedThreadPool(poolSize)
        Map<String, Future<Map>> futures = [:]
        Map<String, Double> dbTimings = [:]
        Map<String, String> dbErrors = [:]
        Map<String, List<Map>> rawDbResults = [:]

        // Prepare extension filters
        List<String> effectiveExts = (extList != null) ? new ArrayList<>(extList) : null
        if (noExt) {
            if (effectiveExts == null) effectiveExts = []
            effectiveExts.add('')
        }

        // Step 1: Dispatch sub-queries to thread pool
        uniqueDbs.each { String dbName, File dbFile ->
            futures[dbName] = executor.submit({
                long dbStart = System.nanoTime()
                MemoryEngine engine = null
                try {
                    engine = new MemoryEngine(dbFile.absolutePath)
                    List<Map> matches = engine.search(
                        query,
                        CANDIDATES_PER_DATABASE,
                        snippetTokenSize,
                        effectiveExts,
                        archiveName
                    )
                    double dbElapsedMs = (System.nanoTime() - dbStart) / 1_000_000.0
                    return [status: 'SUCCESS', matches: matches, elapsed_ms: dbElapsedMs]
                } catch (Exception e) {
                    double dbElapsedMs = (System.nanoTime() - dbStart) / 1_000_000.0
                    return [status: 'ERROR', error: e.message, elapsed_ms: dbElapsedMs]
                } finally {
                    if (engine != null) {
                        try { engine.close() } catch (Exception ignored) {}
                    }
                }
            })
        }

        // Step 2: Collect sub-query results with timeout protection
        int successfulCount = 0
        futures.each { String dbName, Future<Map> future ->
            try {
                Map res = future.get(DATABASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                dbTimings[dbName] = (res.elapsed_ms as Double) ?: 0.0

                if (res.status == 'SUCCESS') {
                    rawDbResults[dbName] = (res.matches as List<Map>) ?: []
                    successfulCount++
                } else {
                    dbErrors[dbName] = (res.error as String) ?: "Unknown database error"
                }
            } catch (TimeoutException te) {
                future.cancel(true)
                dbTimings[dbName] = (double) DATABASE_TIMEOUT_MS
                dbErrors[dbName] = "Query timed out after ${DATABASE_TIMEOUT_MS} ms"
            } catch (Exception e) {
                dbTimings[dbName] = 0.0
                dbErrors[dbName] = e.message ?: e.toString()
            }
        }

        // Shutdown executor pool
        executor.shutdownNow()

        // Step 3: Compute Reciprocal Rank Fusion (RRF) across candidate sets
        long fusionStart = System.nanoTime()
        Map<String, Map> aggregatedDocs = [:] // Key: "database_id:document_id"

        rawDbResults.each { String dbName, List<Map> matches ->
            matches.eachWithIndex { Map doc, int rankIdx ->
                int rank = rankIdx + 1
                long docId = doc.id as long
                String docKey = "${dbName}:${docId}"
                double rrfContribution = 1.0 / (RRF_K + rank)

                if (!aggregatedDocs.containsKey(docKey)) {
                    Map docCopy = new LinkedHashMap(doc)
                    docCopy.origin_db = dbName
                    docCopy.best_sub_rank = rank
                    docCopy.rrf_score = rrfContribution
                    docCopy.hit_count = 1
                    aggregatedDocs[docKey] = docCopy
                } else {
                    Map existing = aggregatedDocs[docKey]
                    existing.rrf_score = (existing.rrf_score as double) + rrfContribution
                    existing.hit_count = (existing.hit_count as int) + 1
                    if (rank < (existing.best_sub_rank as int)) {
                        existing.best_sub_rank = rank
                    }
                }
            }
        }

        // Step 4: Deterministic Tie-Breaking Sort
        List<Map> fusedResults = aggregatedDocs.values().toList()
        fusedResults.sort { Map a, Map b ->
            // 1. Primary: RRF Score descending
            int c = Double.compare(b.rrf_score as double, a.rrf_score as double)
            if (c != 0) return c

            // 2. Secondary: Best sub-database rank ascending
            c = Integer.compare(a.best_sub_rank as int, b.best_sub_rank as int)
            if (c != 0) return c

            // 3. Tertiary: Database identifier ascending
            c = (a.origin_db as String).compareTo(b.origin_db as String)
            if (c != 0) return c

            // 4. Quaternary: Document ID ascending
            return Long.compare(a.id as long, b.id as long)
        }

        // Clamp to requested limit
        int finalLimit = Math.min(limit, fusedResults.size())
        List<Map> finalResults = fusedResults.subList(0, finalLimit)

        double fusionDurationMs = (System.nanoTime() - fusionStart) / 1_000_000.0
        double totalDurationMs = (System.nanoTime() - startNano) / 1_000_000.0

        return [
            dataset_name: activeDatasetName,
            set_name: activeDatasetName,
            results: finalResults,
            total_results: finalResults.size(),
            total_candidates: fusedResults.size(),
            database_count: uniqueDbs.size(),
            successful_databases: successfulCount,
            failed_databases: dbErrors,
            database_timings: dbTimings,
            fusion_duration_ms: fusionDurationMs,
            total_duration_ms: totalDurationMs
        ]
    }

    /** Backward compatibility alias for searchDataset. */
    static Map searchSet(
        String setName,
        String query,
        int limit = 20,
        List<String> extList = null,
        boolean noExt = false,
        String archiveName = null,
        int snippetTokenSize = 64
    ) {
        return searchDataset(setName, query, limit, extList, noExt, archiveName, snippetTokenSize)
    }
}
