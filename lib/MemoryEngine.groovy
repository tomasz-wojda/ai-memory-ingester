// Dependency: org.xerial:sqlite-jdbc:3.45.1.0 (resolved via @Grab in run.groovy)
import groovy.sql.Sql
import java.sql.Connection
import java.sql.DriverManager
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/**
 * MemoryEngine.groovy
 *
 * High-performance SQLite FTS5 full-text search engine for the Archive Memory Context system.
 * Manages an optimized schema:
 *   - `documents` metadata and text storage
 *   - `documents_fts` FTS5 index with custom code tokenizer (separators=._/) and prefix indexes (2 3 4)
 *   - `ingestion_manifest` archive tracking and provenance
 *
 * Configured with 2GB memory-mapped I/O (mmap), 256MB page cache, RAM temp store, WAL journal,
 * post-ingestion b-tree compaction (optimize), and in-place transparent compression / decompression.
 *
 * Usage: loaded via classpath by scripts (`groovy -cp lib <script>.groovy`).
 */
class MemoryEngine {

    /** Groovy SQL wrapper for executing queries. */
    private Sql sql

    /** Tracks whether a batch transaction is currently open. */
    private boolean inBatch = false

    /** Counter for documents inserted within the current batch. */
    private int batchCount = 0

    /**
     * Creates a new MemoryEngine connected to the specified SQLite database.
     * Applies high-performance PRAGMAs and initialises schema.
     *
     * @param dbPath Absolute or relative path to the SQLite database file
     */
    MemoryEngine(String dbPath) {
        // Load driver and create connection directly, bypassing DriverManager
        // to avoid classloader visibility issues
        def driver = Thread.currentThread().contextClassLoader
            .loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
        def conn = driver.connect("jdbc:sqlite:${dbPath}", new java.util.Properties())
        this.sql = new Sql(conn)

        // -------------------------------------------------------------------
        // High-Performance PRAGMA Tuning
        // -------------------------------------------------------------------
        sql.execute('PRAGMA journal_mode=WAL')
        sql.execute('PRAGMA synchronous=NORMAL')
        sql.execute('PRAGMA mmap_size=2147483648')  // 2 GB memory-mapped I/O
        sql.execute('PRAGMA cache_size=-262144')   // 256 MB RAM cache
        sql.execute('PRAGMA temp_store=MEMORY')    // Temporary FTS tables in RAM

        initSchema()
    }

    // -----------------------------------------------------------------------
    // Schema initialisation
    // -----------------------------------------------------------------------

    /**
     * Creates the documents table, FTS5 virtual table with code tokenizer and prefix indexes,
     * synchronization triggers, and the ingestion manifest table.
     */
    private void initSchema() {
        // Main metadata and content storage table
        sql.execute('''
            CREATE TABLE IF NOT EXISTS documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_archive TEXT NOT NULL,
                file_path TEXT NOT NULL,
                file_name TEXT NOT NULL,
                extension TEXT NOT NULL,
                size_bytes INTEGER DEFAULT 0,
                content TEXT,
                is_compressed INTEGER DEFAULT 0,
                indexed_at TEXT DEFAULT (datetime('now'))
            )
        ''')

        // Ensure is_compressed column exists for existing databases
        boolean hasCompressedCol = false
        sql.eachRow('PRAGMA table_info(documents)') { row ->
            if (row.name == 'is_compressed') hasCompressedCol = true
        }
        if (!hasCompressedCol) {
            sql.execute('ALTER TABLE documents ADD COLUMN is_compressed INTEGER DEFAULT 0')
        }

        // Ingestion provenance and manifest tracking table
        sql.execute('''
            CREATE TABLE IF NOT EXISTS ingestion_manifest (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_archive TEXT UNIQUE NOT NULL,
                archive_size_bytes INTEGER DEFAULT 0,
                ingested_documents INTEGER DEFAULT 0,
                total_text_bytes INTEGER DEFAULT 0,
                origin_path TEXT,
                origin_hash TEXT,
                content_hash TEXT,
                ingested_at TEXT DEFAULT (datetime('now'))
            )
        ''')

        // Ensure origin_path, origin_hash, content_hash exist for existing databases
        Set<String> manifestCols = [] as Set
        sql.eachRow('PRAGMA table_info(ingestion_manifest)') { row ->
            manifestCols << row.name.toString()
        }
        if (!manifestCols.contains('origin_path')) sql.execute('ALTER TABLE ingestion_manifest ADD COLUMN origin_path TEXT')
        if (!manifestCols.contains('origin_hash')) sql.execute('ALTER TABLE ingestion_manifest ADD COLUMN origin_hash TEXT')
        if (!manifestCols.contains('content_hash')) sql.execute('ALTER TABLE ingestion_manifest ADD COLUMN content_hash TEXT')

        // Optimized contentless FTS5 virtual table:
        // - content='': prevents duplicate text storage, storing only the token inverted index
        // - contentless_delete=1: enables rowid deletions
        // - unicode61 tokenizer with diacritic removal
        // - prefix index on 2, 3, and 4 character prefixes for instant wildcard matching
        sql.execute('''
            CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
                file_path,
                file_name,
                content,
                content='',
                contentless_delete=1,
                tokenize='unicode61 remove_diacritics 2',
                prefix='2 3 4'
            )
        ''')

        // Synchronisation trigger: auto-populate FTS5 on INSERT
        sql.execute('''
            CREATE TRIGGER IF NOT EXISTS documents_ai AFTER INSERT ON documents BEGIN
                INSERT INTO documents_fts(rowid, file_path, file_name, content)
                VALUES (new.id, new.file_path, new.file_name, new.content);
            END
        ''')

        // Synchronisation trigger: remove from FTS5 on DELETE
        sql.execute('''
            CREATE TRIGGER IF NOT EXISTS documents_ad AFTER DELETE ON documents BEGIN
                DELETE FROM documents_fts WHERE rowid = old.id;
            END
        ''')

        // Synchronisation trigger: update FTS5 on UPDATE
        sql.execute('''
            CREATE TRIGGER IF NOT EXISTS documents_au AFTER UPDATE ON documents BEGIN
                DELETE FROM documents_fts WHERE rowid = old.id;
                INSERT INTO documents_fts(rowid, file_path, file_name, content)
                VALUES (new.id, new.file_path, new.file_name, new.content);
            END
        ''')

        // Index on source_archive for efficient filtering
        sql.execute('''
            CREATE INDEX IF NOT EXISTS idx_documents_archive
            ON documents(source_archive)
        ''')

        // Index on extension for type-based queries
        sql.execute('''
            CREATE INDEX IF NOT EXISTS idx_documents_extension
            ON documents(extension)
        ''')
    }

    // -----------------------------------------------------------------------
    // FTS5 Optimization & Compaction
    // -----------------------------------------------------------------------

    /**
     * Executes FTS5 b-tree index compaction to merge all fragmented segments
     * into a single balanced tree structure for peak query performance.
     */
    void optimizeIndex() {
        sql.execute("INSERT INTO documents_fts(documents_fts) VALUES('optimize')")
    }

    // -----------------------------------------------------------------------
    // Manifest Management
    // -----------------------------------------------------------------------

    /**
     * Records or updates ingestion manifest metadata for an archive.
     *
     * @param sourceArchive    Archive filename (e.g., "cms_R1.zip")
     * @param archiveSizeBytes Size of archive file in bytes
     * @param documentCount    Total indexed document count
     * @param totalTextBytes   Total extracted text in bytes
     * @param originPath       Original directory path on disk
     * @param originHash       Fast origin path fingerprint (xxHash64 / SHA-256)
     * @param contentHash      Merkle root content hash for deduplication
     */
    void recordManifest(String sourceArchive, long archiveSizeBytes, int documentCount, long totalTextBytes, String originPath = null, String originHash = null, String contentHash = null) {
        sql.execute('''
            INSERT INTO ingestion_manifest (source_archive, archive_size_bytes, ingested_documents, total_text_bytes, origin_path, origin_hash, content_hash, ingested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
            ON CONFLICT(source_archive) DO UPDATE SET
                archive_size_bytes=excluded.archive_size_bytes,
                ingested_documents=excluded.ingested_documents,
                total_text_bytes=excluded.total_text_bytes,
                origin_path=COALESCE(excluded.origin_path, ingestion_manifest.origin_path),
                origin_hash=COALESCE(excluded.origin_hash, ingestion_manifest.origin_hash),
                content_hash=COALESCE(excluded.content_hash, ingestion_manifest.content_hash),
                ingested_at=datetime('now')
        ''', [sourceArchive, archiveSizeBytes, documentCount, totalTextBytes, originPath, originHash, contentHash])
    }

    /**
     * Retrieves all recorded ingestion manifest entries along with live compression state.
     *
     * @return List of Maps with manifest records and compression_state
     */
    List<Map> getManifest() {
        List<Map> results = []
        sql.eachRow('''
            SELECT m.id, m.source_archive, m.archive_size_bytes, m.ingested_documents,
                   m.total_text_bytes, m.ingested_at,
                   COALESCE(COUNT(d.id), 0) AS live_documents,
                   COALESCE(SUM(CASE WHEN d.is_compressed = 1 THEN 1 ELSE 0 END), 0) AS compressed_documents
            FROM ingestion_manifest m
            LEFT JOIN documents d ON d.source_archive = m.source_archive
            GROUP BY m.source_archive
            ORDER BY m.ingested_at DESC
        ''') { row ->
            int total = (row.live_documents ?: 0) as int
            int comp = (row.compressed_documents ?: 0) as int
            String state = "uncompressed"
            if (total > 0) {
                if (comp == total) {
                    state = "compressed"
                } else if (comp > 0) {
                    state = "hybrid (${comp}/${total})"
                }
            }
            results << [
                id:                   row.id,
                source_archive:       row.source_archive,
                archive_size_bytes:   row.archive_size_bytes,
                ingested_documents:   row.ingested_documents,
                total_text_bytes:     row.total_text_bytes,
                ingested_at:          row.ingested_at,
                live_documents:       total,
                compressed_documents: comp,
                compression_state:    state
            ]
        }
        return results
    }

    /**
     * Checks if a given archive has already been recorded in the ingestion manifest.
     *
     * @param sourceArchive Archive filename (e.g. "cms_R1.zip")
     * @return True if archive exists in manifest with at least 1 document
     */
    boolean isArchiveIngested(String sourceArchive) {
        def row = sql.firstRow('SELECT ingested_documents FROM ingestion_manifest WHERE source_archive = ?', [sourceArchive])
        return row != null && (row.ingested_documents ?: 0) > 0
    }

    /**
     * Purges all documents and the manifest record for a given archive.
     * SQLite triggers automatically clean up associated FTS5 index rows.
     *
     * @param sourceArchive Archive filename to purge
     * @return Number of documents deleted
     */
    int purgeArchive(String sourceArchive) {
        int count = sql.executeUpdate('DELETE FROM documents WHERE source_archive = ?', [sourceArchive])
        sql.execute('DELETE FROM ingestion_manifest WHERE source_archive = ?', [sourceArchive])
        return count
    }
    // Batch operations
    // -----------------------------------------------------------------------

    /**
     * Begins a batch transaction for bulk inserts.
     * Disables synchronous writes for peak ingestion speed.
     */
    void beginBatch() {
        if (!inBatch) {
            sql.execute('PRAGMA synchronous=OFF')
            sql.execute('BEGIN TRANSACTION')
            inBatch = true
            batchCount = 0
        }
    }

    /**
     * Commits and closes the current batch transaction.
     * Restores normal synchronous write mode.
     */
    void endBatch() {
        if (inBatch) {
            sql.execute('COMMIT')
            sql.execute('PRAGMA synchronous=NORMAL')
            inBatch = false
            batchCount = 0
        }
    }

    /**
     * Commits the current batch and starts a new one.
     * Called internally when BATCH_SIZE is reached.
     */
    private void rotateBatch() {
        sql.execute('COMMIT')
        sql.execute('BEGIN TRANSACTION')
        batchCount = 0
    }

    // -----------------------------------------------------------------------
    // Insert
    // -----------------------------------------------------------------------

    /**
     * Inserts a single document into the database.
     * If a batch is active, auto-rotates at Config.BATCH_SIZE.
     *
     * @param sourceArchive Name of the source archive (e.g., "cms_R1.zip")
     * @param filePath      Full path within the archive
     * @param fileName      Filename only
     * @param extension     Lowercase extension with dot
     * @param sizeBytes     Uncompressed file size in bytes
     * @param content       Extracted text content
     */
    void insertDocument(String sourceArchive, String filePath, String fileName,
                        String extension, long sizeBytes, String content) {
        sql.executeInsert('''
            INSERT INTO documents (source_archive, file_path, file_name, extension, size_bytes, content)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', [sourceArchive, filePath, fileName, extension, sizeBytes, content])

        if (inBatch) {
            batchCount++
            if (batchCount >= Config.BATCH_SIZE) {
                rotateBatch()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Normalizes search queries containing code punctuation (e.g. dots, slashes)
     * so they execute cleanly without requiring explicit manual phrase quoting.
     *
     * @param rawQuery User search input
     * @return Formatted FTS5 match expression
     */
    static String normalizeQuery(String rawQuery) {
        if (!rawQuery) return rawQuery
        String q = rawQuery.trim()
        if (q.startsWith('"') && q.endsWith('"')) return q
        if (q.contains(':') || q.contains(' AND ') || q.contains(' OR ') || q.contains(' NOT ') || q.contains(' NEAR(')) {
            return q
        }
        if (q.contains('.') || q.contains('/') || q.contains('\\')) {
            return '"' + q.replaceAll('"', '""') + '"'
        }
        return q
    }

    /**
     * Executes a full-text search query using SQLite FTS5 MATCH syntax,
     * optionally filtered by file extensions and/or source archive.
     * Returns ranked results with dynamic decompressed content snippets.
     *
     * @param query       FTS5 match expression
     * @param limit       Maximum number of results (default: 20)
     * @param snippetSize Maximum token length for the excerpt window (default: 64)
     * @param extensions  Optional list of file extensions to filter by (e.g. ['.pdf', '.java'])
     * @param archive     Optional archive filename to filter by (e.g. 'cms_R1.zip')
     * @return List of Maps with keys: id, source_archive, file_path,
     *         file_name, extension, snippet, rank
     */
    List<Map> search(String query, int limit = 20, int snippetSize = 64, List<String> extensions = null, String archive = null) {
        String matchExpr = normalizeQuery(query)
        List<Map> results = []

        StringBuilder sqlBuilder = new StringBuilder("""
            SELECT d.id, d.source_archive, d.file_path, d.file_name, d.extension,
                   d.content, d.is_compressed,
                   rank
            FROM documents_fts f
            JOIN documents d ON d.id = f.rowid
            WHERE documents_fts MATCH ?
        """)

        List<Object> params = [matchExpr]

        if (extensions != null && !extensions.isEmpty()) {
            List<String> cleanExts = extensions.collect { ext ->
                if (ext == null) return ''
                String e = ext.trim().replaceAll("^['\"]+|['\"]+\$", '').toLowerCase()
                if (e.isEmpty() || e == 'none' || e == 'empty' || e == 'noext') {
                    return ''
                }
                return e.startsWith('.') ? e : '.' + e
            }.unique()

            if (!cleanExts.isEmpty()) {
                String placeholders = cleanExts.collect { '?' }.join(', ')
                sqlBuilder.append(" AND LOWER(d.extension) IN (${placeholders})")
                params.addAll(cleanExts)
            }
        }

        if (archive != null && !archive.trim().isEmpty()) {
            String cleanArchive = archive.trim()
            sqlBuilder.append(" AND (d.source_archive = ? OR d.source_archive LIKE ?)")
            params.add(cleanArchive)
            params.add("%${cleanArchive}%")
        }

        sqlBuilder.append(" ORDER BY rank LIMIT ?")
        params.add(limit)

        sql.eachRow(sqlBuilder.toString(), params) { row ->
            String text = (row.is_compressed == 1)
                ? decompressText(row.content)
                : (row.content != null ? row.content.toString() : '')

            String snip = generateSnippet(text, query, snippetSize)

            results << [
                id:             row.id,
                source_archive: row.source_archive,
                file_path:      row.file_path,
                file_name:      row.file_name,
                extension:      row.extension,
                snippet:        snip,
                rank:           row.rank
            ]
        }
        return results
    }

    /**
     * Generates a context snippet with term highlighting (>>>term<<<) from raw or decompressed text.
     *
     * @param content     Document text
     * @param rawQuery    Search terms
     * @param snippetSize Approximate token window size
     * @return Formatted snippet string
     */
    static String generateSnippet(String content, String rawQuery, int snippetSize = 64) {
        if (!content) return ''
        List<String> terms = rawQuery.replaceAll('[():"]', ' ')
            .split('\\s+')
            .findAll { t -> !t.isEmpty() && !['AND', 'OR', 'NOT'].contains(t) && !t.contains(':') }
            .collect { t -> t.replaceAll('\\*', '').toLowerCase() }
            .findAll { t -> t.length() >= 2 }

        if (terms.isEmpty()) {
            String initial = content.take(snippetSize * 4).replaceAll('\\s+', ' ').trim()
            return content.length() > initial.length() ? initial + '...' : initial
        }

        String lowerContent = content.toLowerCase()
        int matchIdx = -1
        for (String term : terms) {
            int idx = lowerContent.indexOf(term)
            if (idx >= 0 && (matchIdx == -1 || idx < matchIdx)) {
                matchIdx = idx
            }
        }

        if (matchIdx == -1) {
            String initial = content.take(snippetSize * 4).replaceAll('\\s+', ' ').trim()
            return content.length() > initial.length() ? initial + '...' : initial
        }

        int startIdx = Math.max(0, matchIdx - (snippetSize * 2))
        int endIdx = Math.min(content.length(), matchIdx + (snippetSize * 4))

        String snippet = content.substring(startIdx, endIdx).replaceAll('\\s+', ' ').trim()
        if (startIdx > 0) snippet = "..." + snippet
        if (endIdx < content.length()) snippet = snippet + "..."

        for (String term : terms) {
            snippet = snippet.replaceAll("(?i)(${java.util.regex.Pattern.quote(term)})", '>>>$1<<<')
        }
        return snippet
    }

    // -----------------------------------------------------------------------
    // Retrieval
    // -----------------------------------------------------------------------

    /**
     * Retrieves the full content of a document by its ID.
     * Transparently decompresses content if stored in compressed mode.
     *
     * @param id Document ID
     * @return Map with all document fields, or null if not found
     */
    Map getDocument(int id) {
        def row = sql.firstRow('''
            SELECT id, source_archive, file_path, file_name, extension,
                   size_bytes, content, is_compressed, indexed_at
            FROM documents WHERE id = ?
        ''', [id])
        if (!row) return null

        String contentText = (row.is_compressed == 1)
            ? decompressText(row.content)
            : (row.content != null ? row.content.toString() : '')

        return [
            id:             row.id,
            source_archive: row.source_archive,
            file_path:      row.file_path,
            file_name:      row.file_name,
            extension:      row.extension,
            size_bytes:     row.size_bytes,
            content:        contentText,
            is_compressed:  row.is_compressed ?: 0,
            indexed_at:     row.indexed_at
        ]
    }

    /**
     * Lists documents matching a file path pattern (SQL LIKE).
     *
     * @param pattern SQL LIKE pattern (e.g., "%Address%")
     * @param limit   Maximum results
     * @return List of Maps with id, file_path, file_name, extension, size_bytes
     */
    List<Map> listFiles(String pattern, int limit = 50) {
        List<Map> results = []
        sql.eachRow('''
            SELECT id, source_archive, file_path, file_name, extension, size_bytes
            FROM documents
            WHERE file_path LIKE ?
            ORDER BY file_path
            LIMIT ?
        ''', [pattern, limit]) { row ->
            results << [
                id:             row.id,
                source_archive: row.source_archive,
                file_path:      row.file_path,
                file_name:      row.file_name,
                extension:      row.extension,
                size_bytes:     row.size_bytes
            ]
        }
        return results
    }

    /**
     * Lists all documents with a specific extension.
     *
     * @param extension Extension with dot (e.g., ".sql")
     * @param limit     Maximum results
     * @return List of Maps with id, file_path, file_name, size_bytes
     */
    List<Map> listByExtension(String extension, int limit = 50) {
        List<Map> results = []
        sql.eachRow('''
            SELECT id, source_archive, file_path, file_name, size_bytes
            FROM documents
            WHERE extension = ?
            ORDER BY file_path
            LIMIT ?
        ''', [extension, limit]) { row ->
            results << [
                id:             row.id,
                source_archive: row.source_archive,
                file_path:      row.file_path,
                file_name:      row.file_name,
                size_bytes:     row.size_bytes
            ]
        }
        return results
    }

    /**
     * Lists all recorded archive entries in the database along with their live document counts,
     * total text size, archive size, and compression statistics.
     *
     * @return List of Maps with archive metadata and compression state
     */
    List<Map> listArchives() {
        List<Map> results = []
        sql.eachRow('''
            SELECT m.source_archive, m.archive_size_bytes, m.ingested_documents,
                   m.total_text_bytes, m.origin_path, m.origin_hash, m.content_hash, m.ingested_at,
                   COALESCE(COUNT(d.id), 0) AS live_documents,
                   COALESCE(SUM(CASE WHEN d.is_compressed = 1 THEN 1 ELSE 0 END), 0) AS compressed_documents,
                   COALESCE(SUM(d.size_bytes), 0) AS live_text_bytes,
                   COALESCE(SUM(LENGTH(d.content)), 0) AS stored_bytes
            FROM ingestion_manifest m
            LEFT JOIN documents d ON d.source_archive = m.source_archive
            GROUP BY m.source_archive
            ORDER BY m.ingested_at DESC
        ''') { row ->
            results << [
                source_archive:       row.source_archive,
                archive_size_bytes:   row.archive_size_bytes,
                ingested_documents:   row.ingested_documents,
                total_text_bytes:     row.total_text_bytes,
                origin_path:          row.origin_path,
                origin_hash:          row.origin_hash,
                content_hash:         row.content_hash,
                ingested_at:          row.ingested_at,
                live_documents:       row.live_documents,
                compressed_documents: row.compressed_documents,
                live_text_bytes:      row.live_text_bytes,
                stored_bytes:         row.stored_bytes
            ]
        }
        return results
    }

    /**
     * Renames an archive across all stored documents and manifest tracking.
     *
     * @param oldName Existing archive name
     * @param newName New archive name
     * @return Number of documents updated
     */
    int renameArchive(String oldName, String newName) {
        int updated = 0
        sql.withTransaction {
            updated = sql.executeUpdate('UPDATE documents SET source_archive = ? WHERE source_archive = ?', [newName, oldName])
            sql.executeUpdate('UPDATE ingestion_manifest SET source_archive = ? WHERE source_archive = ?', [newName, oldName])
        }
        return updated
    }

    /**
     * Egests (purges) an archive and all its associated documents, FTS5 indexes, and manifest metadata.
     *
     * @param archiveName Archive name to egest
     * @return Map with archive name and deleted document count
     */
    Map egestArchive(String archiveName) {
        int deletedDocs = 0
        sql.withTransaction {
            deletedDocs = sql.executeUpdate('DELETE FROM documents WHERE source_archive = ?', [archiveName])
            sql.executeUpdate('DELETE FROM ingestion_manifest WHERE source_archive = ?', [archiveName])
        }
        optimizeIndex()
        return [
            archive:           archiveName,
            deleted_documents: deletedDocs
        ]
    }

    /**
     * Appends text content into an existing document or creates a new document if it does not exist.
     * Newly appended content is immediately indexed into FTS5 for sub-millisecond query availability.
     *
     * @param sourceArchive Archive or stream identifier
     * @param filePath      Relative file path or stream name
     * @param fileName      Base file name
     * @param extension     File extension (e.g., ".log")
     * @param textChunk     Text content to append
     * @return The document row ID
     */
    long appendDocument(String sourceArchive, String filePath, String fileName, String extension, String textChunk) {
        if (textChunk == null) return -1
        long docId = -1
        sql.withTransaction {
            def existing = sql.firstRow('SELECT id, content, size_bytes, is_compressed FROM documents WHERE source_archive = ? AND file_path = ?', [sourceArchive, filePath])
            if (existing) {
                docId = existing.id as long
                String oldContent = (existing.is_compressed == 1) ? decompressText(existing.content) : (existing.content != null ? existing.content.toString() : '')
                String newContent = oldContent + (oldContent.isEmpty() || oldContent.endsWith('\n') ? '' : '\n') + textChunk
                byte[] rawBytes = newContent.getBytes(StandardCharsets.UTF_8)
                sql.executeUpdate('UPDATE documents SET content = ?, size_bytes = ?, is_compressed = 0 WHERE id = ?', [newContent, rawBytes.length, docId])
            } else {
                byte[] rawBytes = textChunk.getBytes(StandardCharsets.UTF_8)
                def res = sql.executeInsert('''
                    INSERT INTO documents (source_archive, file_path, file_name, extension, size_bytes, content, is_compressed)
                    VALUES (?, ?, ?, ?, ?, ?, 0)
                ''', [sourceArchive, filePath, fileName, extension, rawBytes.length, textChunk])
                docId = res[0][0] as long
            }

            // Upsert manifest
            sql.execute('''
                INSERT INTO ingestion_manifest (source_archive, archive_size_bytes, ingested_documents, total_text_bytes, ingested_at)
                VALUES (?, 0, 1, ?, datetime('now'))
                ON CONFLICT(source_archive) DO UPDATE SET
                    ingested_documents = (SELECT COUNT(*) FROM documents WHERE source_archive = ?),
                    total_text_bytes = (SELECT COALESCE(SUM(size_bytes), 0) FROM documents WHERE source_archive = ?),
                    ingested_at = datetime('now')
            ''', [sourceArchive, textChunk.length(), sourceArchive, sourceArchive])
        }
        return docId
    }

    /**
     * Lists all documents originating from a specific archive.
     *
     * @param archiveName Archive name or pattern (e.g. "cms_R1.zip" or "cms_R1")
     * @param limit       Maximum results to return (default: 50)
     * @return List of Maps with id, source_archive, file_path, file_name, extension, size_bytes, is_compressed
     */
    List<Map> listByArchive(String archiveName, int limit = 50) {
        List<Map> results = []
        sql.eachRow('''
            SELECT id, source_archive, file_path, file_name, extension, size_bytes, is_compressed
            FROM documents
            WHERE source_archive = ? OR source_archive LIKE ?
            ORDER BY file_path
            LIMIT ?
        ''', [archiveName, "%${archiveName}%", limit]) { row ->
            results << [
                id:             row.id,
                source_archive: row.source_archive,
                file_path:      row.file_path,
                file_name:      row.file_name,
                extension:      row.extension,
                size_bytes:     row.size_bytes,
                is_compressed:  row.is_compressed ?: 0
            ]
        }
        return results
    }

    // -----------------------------------------------------------------------
    // In-Place Compression / Decompression Routines
    // -----------------------------------------------------------------------

    /**
     * Compresses a UTF-8 string into a zlib byte array.
     *
     * @param text Raw text string to compress
     * @return Compressed byte array, or null if text is null
     */
    static byte[] compressText(String text) {
        if (text == null) return null
        byte[] input = text.getBytes(StandardCharsets.UTF_8)
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, (int)(input.length / 2)))
        Deflater deflater = new Deflater(Deflater.BEST_SPEED)
        DeflaterOutputStream dos = new DeflaterOutputStream(bos, deflater)
        dos.write(input)
        dos.finish()
        dos.close()
        return bos.toByteArray()
    }

    /**
     * Decompresses a zlib byte array back to a UTF-8 string.
     *
     * @param content Compressed byte array or raw string object
     * @return Decompressed UTF-8 string
     */
    static String decompressText(Object content) {
        if (content == null) return null
        if (content instanceof byte[]) {
            byte[] bytes = (byte[]) content
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes)
            InflaterInputStream iis = new InflaterInputStream(bis)
            ByteArrayOutputStream bos = new ByteArrayOutputStream()
            byte[] buffer = new byte[8192]
            int read
            while ((read = iis.read(buffer)) != -1) {
                bos.write(buffer, 0, read)
            }
            iis.close()
            return new String(bos.toByteArray(), StandardCharsets.UTF_8)
        }
        return content.toString()
    }

    /**
     * Compresses all uncompressed documents (or documents within a specific archive) in-place and runs VACUUM.
     *
     * @param targetArchive    Optional archive filename to target (null for all archives)
     * @param progressCallback Optional closure receiving (processedCount, totalCount)
     * @return Map with compression metrics
     */
    Map compressDatabase(String targetArchive = null, Closure progressCallback = null) {
        long startNanos = System.nanoTime()
        long initialSizeBytes = Config.DB_PATH.exists() ? Config.DB_PATH.length() : 0

        // Temporarily disable update trigger to prevent FTS5 re-indexing during storage compression
        sql.execute('DROP TRIGGER IF EXISTS documents_au')

        List<Map> rows = []
        if (targetArchive) {
            sql.eachRow('''
                SELECT id, content FROM documents
                WHERE (is_compressed = 0 OR is_compressed IS NULL)
                  AND (source_archive = ? OR source_archive LIKE ?)
            ''', [targetArchive, "%${targetArchive}%"]) { row ->
                rows << [id: row.id, content: row.content]
            }
        } else {
            sql.eachRow('SELECT id, content FROM documents WHERE is_compressed = 0 OR is_compressed IS NULL') { row ->
                rows << [id: row.id, content: row.content]
            }
        }

        int total = rows.size()
        int processed = 0

        if (total > 0) {
            sql.withBatch(500, 'UPDATE documents SET content = ?, is_compressed = 1 WHERE id = ?') { ps ->
                rows.each { r ->
                    byte[] compressed = compressText(r.content as String)
                    ps.addBatch([compressed, r.id])
                    processed++
                    if (progressCallback && (processed % 500 == 0 || processed == total)) {
                        progressCallback.call(processed, total)
                    }
                }
            }
        }

        // Restore update trigger
        sql.execute('''
            CREATE TRIGGER IF NOT EXISTS documents_au AFTER UPDATE ON documents BEGIN
                DELETE FROM documents_fts WHERE rowid = old.id;
                INSERT INTO documents_fts(rowid, file_path, file_name, content)
                VALUES (new.id, new.file_path, new.file_name, new.content);
            END
        ''')

        // Compact storage on disk
        sql.execute('VACUUM')

        long finalSizeBytes = Config.DB_PATH.exists() ? Config.DB_PATH.length() : 0
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        return [
            processed_count:    processed,
            initial_size_bytes: initialSizeBytes,
            final_size_bytes:   finalSizeBytes,
            saved_bytes:        Math.max(0, initialSizeBytes - finalSizeBytes),
            elapsed_ms:         elapsedMs
        ]
    }

    /**
     * Decompresses all compressed documents (or documents within a specific archive) in-place and runs VACUUM.
     *
     * @param targetArchive    Optional archive filename to target (null for all archives)
     * @param progressCallback Optional closure receiving (processedCount, totalCount)
     * @return Map with decompression metrics
     */
    Map decompressDatabase(String targetArchive = null, Closure progressCallback = null) {
        long startNanos = System.nanoTime()
        long initialSizeBytes = Config.DB_PATH.exists() ? Config.DB_PATH.length() : 0

        sql.execute('DROP TRIGGER IF EXISTS documents_au')

        List<Map> rows = []
        if (targetArchive) {
            sql.eachRow('''
                SELECT id, content FROM documents
                WHERE is_compressed = 1
                  AND (source_archive = ? OR source_archive LIKE ?)
            ''', [targetArchive, "%${targetArchive}%"]) { row ->
                rows << [id: row.id, content: row.content]
            }
        } else {
            sql.eachRow('SELECT id, content FROM documents WHERE is_compressed = 1') { row ->
                rows << [id: row.id, content: row.content]
            }
        }

        int total = rows.size()
        int processed = 0

        if (total > 0) {
            sql.withBatch(500, 'UPDATE documents SET content = ?, is_compressed = 0 WHERE id = ?') { ps ->
                rows.each { r ->
                    String decompressed = decompressText(r.content)
                    ps.addBatch([decompressed, r.id])
                    processed++
                    if (progressCallback && (processed % 500 == 0 || processed == total)) {
                        progressCallback.call(processed, total)
                    }
                }
            }
        }

        sql.execute('''
            CREATE TRIGGER IF NOT EXISTS documents_au AFTER UPDATE ON documents BEGIN
                DELETE FROM documents_fts WHERE rowid = old.id;
                INSERT INTO documents_fts(rowid, file_path, file_name, content)
                VALUES (new.id, new.file_path, new.file_name, new.content);
            END
        ''')

        sql.execute('VACUUM')

        long finalSizeBytes = Config.DB_PATH.exists() ? Config.DB_PATH.length() : 0
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        return [
            processed_count:    processed,
            initial_size_bytes: initialSizeBytes,
            final_size_bytes:   finalSizeBytes,
            saved_bytes:        Math.max(0, initialSizeBytes - finalSizeBytes),
            elapsed_ms:         elapsedMs
        ]
    }

    // -----------------------------------------------------------------------
    // Statistics
    // -----------------------------------------------------------------------

    /**
     * Returns aggregate statistics about the indexed database.
     *
     * @return Map with keys: total_documents, total_size_bytes, compressed_documents,
     *         by_archive (Map), by_extension (Map), manifest (List)
     */
    Map getStats() {
        Map stats = [:]

        // Total counts
        def totals = sql.firstRow('SELECT COUNT(*) AS cnt, COALESCE(SUM(size_bytes),0) AS total_size, COALESCE(SUM(CASE WHEN is_compressed = 1 THEN 1 ELSE 0 END), 0) AS compressed_cnt FROM documents')
        stats.total_documents = totals.cnt
        stats.total_size_bytes = totals.total_size
        stats.compressed_documents = totals.compressed_cnt

        // Ingestion manifest
        stats.manifest = getManifest()

        // Counts by archive
        Map byArchive = [:]
        sql.eachRow('SELECT source_archive, COUNT(*) AS cnt FROM documents GROUP BY source_archive ORDER BY cnt DESC') { row ->
            byArchive[row.source_archive] = row.cnt
        }
        stats.by_archive = byArchive

        // Counts by extension (top 20)
        Map byExt = [:]
        sql.eachRow('SELECT extension, COUNT(*) AS cnt FROM documents GROUP BY extension ORDER BY cnt DESC LIMIT 20') { row ->
            byExt[row.extension] = row.cnt
        }
        stats.by_extension = byExt

        return stats
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Closes the database connection. Commits any open batch first.
     */
    void close() {
        if (inBatch) {
            endBatch()
        }
        sql?.close()
    }
}

