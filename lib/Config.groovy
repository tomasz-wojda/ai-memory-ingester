/**
 * Config.groovy
 * 
 * Central configuration for the Archive Memory Context Engine.
 * Defines archive source paths, database location, extension classification
 * mappings, encoding fallback chain, and size thresholds.
 * 
 * Usage: loaded via classpath by all scripts (`groovy -cp lib <script>.groovy`).
 */
class Config {

    static final String CONVERSATION_DB_NAME = 'convo.db'
    static final String ARTIFACTS_DB_NAME = 'artifacts.db'
    static final String DROSS_DB_NAME = 'dross.db'
    static final String WORKLOGS_DB_NAME = 'worklogs.db'
    static final String WORKLOG_CHAT_DATASET = 'worklog-chat'
    static final String WORKLOG_CHAT_TURNS_DATASET = 'worklog-chat-turns'
    static final String WORKLOG_CHAT_ALL_DATASET = 'worklog-chat-all'
    static final String WORKLOG_CHAT_AUTHORITATIVE_DATASET = 'worklog-chat-authoritative'
    static final String WORKLOG_STATE_ARCHIVE = 'worklog:state'
    static final String WORKLOG_CHUNKS_ARCHIVE = 'worklog:chunks'
    static final String WORKLOG_EVIDENCE_ARCHIVE = 'worklog:evidence'
    static final long CHAT_SPILL_BYTES = 8192L
    static final long WORKLOG_CHUNK_TARGET_BYTES = 12288L
    static final int RESUME_BUDGET_BYTES = 22000
    static final int TICKET_CONTEXT_BUDGET_BYTES = 22000
    static final int RESUME_RECENT_TURNS = 12
    static final String WORKLOG_CHAT_ROOT_NAME = 'worklog-chat'
    static final String CHAT_HASH_INDEX_NAME = 'turn-hashes.tsv'
    static final String CHAT_SWEEP_STATE_NAME = 'ingest-state.json'
    static final String WORKLOG_SWEEP_STATE_NAME = 'worklog-state.json'
    static final int WORKLOG_PARSER_VERSION = 2
    static final String CHAT_RESUME_DIGEST_NAME = 'RESUME.md'
    static final List<String> WORKLOG_CATEGORIES = [
        'summary', 'related-ticket', 'comments', 'repositories', 'findings', 'solutions',
        'decisions', 'risks', 'blockers', 'actions', 'plan', 'checklist', 'action-log',
        'manual-actions', 'status', 'delivery', 'next-action', 'time', 'other'
    ].asImmutable()
    static final List<String> WORKLOG_AUTHORITY_VALUES = [
        'state', 'authoritative', 'historical', 'evidence'
    ].asImmutable()
    static final List<String> TICKET_FRESHNESS_VALUES = [
        'fresh', 'stale', 'undetermined'
    ].asImmutable()
    static final Set<String> DROSS_ALLOWLIST = ([
        '', 'pushed', 'what now?', 'check now', 'go next', 'yes', 'ok',
        'mode: research', 'mode: innovate', 'mode: plan', 'mode: execute'
    ] as Set<String>).asImmutable()
    static final Set<String> HIGH_IMPACT_LABELS = ([
        'FILES CHANGED', 'REMOTE CHANGES', 'SEMANTIC COMMIT TITLE', 'WRITE GATE'
    ] as Set<String>).asImmutable()
    static final Set<String> LOW_IMPACT_LABELS = ([
        'FINDINGS', 'FINDING', 'VERIFICATION', 'VALIDATION', 'ROOT CAUSE',
        'RESULT', 'CONCLUSION', 'RECOMMENDATION', 'EXECUTION', 'DECISION'
    ] as Set<String>).asImmutable()

    // -----------------------------------------------------------------------
    // Paths
    // -----------------------------------------------------------------------

    /** Directory containing source ZIP/RAR archives, resolved relative to script CWD. */
    static final File ARCHIVE_DIR = {
        if (System.getenv('ARCHIVE_DIR')) return new File(System.getenv('ARCHIVE_DIR')).canonicalFile
        File archives = new File('..', 'archives')
        if (archives.exists()) return archives.canonicalFile
        File parent = new File('..').canonicalFile
        File found = parent.listFiles()?.find { it.isDirectory() && it.name.matches('(?i).*(archive|data|source|bscs).*') }
        return (found ?: archives).canonicalFile
    }()

    /** Output directory for generated data (SQLite DB, reports). */
    static File DATA_DIR = {
        String envPath = System.getenv('MEMORY_DATA_DIR') ?: System.getenv('DATABASE_DIR')
        if (envPath && !envPath.trim().isEmpty()) {
            return new File(envPath.trim().replaceAll("^['\"]+|['\"]+\$", '')).canonicalFile
        }
        return new File('data').canonicalFile
    }()

    /** Dataset registry JSON configuration file path. */
    static File DATASETS_FILE = new File(DATA_DIR, 'datasets.json').canonicalFile

    /** Backward compatibility pointer for datasets configuration file. */
    static File SETS_FILE = DATASETS_FILE

    /** Fallback default SQLite database file path. */
    static File defaultDbPath = new File(DATA_DIR, 'memory.db').canonicalFile

    /** Base SQLite database file path (can be overridden via explicit --db flag). */
    static File DB_PATH = defaultDbPath

    /**
     * Sets the custom root data directory and re-points all derived file paths.
     *
     * @param customPath Path to the target directory
     */
    static void setDataDir(String customPath) {
        if (customPath && !customPath.trim().isEmpty()) {
            String clean = customPath.trim().replaceAll("^['\"]+|['\"]+\$", '')
            DATA_DIR = new File(clean).canonicalFile
            DATASETS_FILE = new File(DATA_DIR, 'datasets.json').canonicalFile
            SETS_FILE = DATASETS_FILE
            defaultDbPath = new File(DATA_DIR, 'memory.db').canonicalFile
            DB_PATH = defaultDbPath
        }
    }

    /**
     * Resolves the workspace root from an explicit value, environment, or repository location.
     *
     * @param explicitRoot Optional explicit workspace root
     * @param repositoryRoot Repository root used for fallback resolution
     * @return Canonical workspace root
     */
    static File workspaceRoot(String explicitRoot = null, File repositoryRoot = new File('.')) {
        String requested = explicitRoot ?: System.getenv('MEMORY_WORKSPACE_ROOT')
        if (requested && !requested.trim().isEmpty()) {
            return new File(requested.trim().replaceAll("^['\"]+|['\"]+\$", '')).canonicalFile
        }
        File repository = repositoryRoot.canonicalFile
        File reposDirectory = repository.parentFile
        return (reposDirectory?.parentFile ?: repository).canonicalFile
    }

    /**
     * Determines whether a given string is an explicit filesystem path.
     *
     * @param path Path or identifier string
     * @return true if path represents an explicit filesystem path
     */
    static boolean isExplicitPath(String path) {
        if (!path) return false
        String clean = path.trim().replaceAll("^['\"]+|['\"]+\$", '')
        return clean.contains('/') || clean.contains('\\') || clean.contains(':')
    }

    /**
     * Validates that a database identifier does not contain path traversal elements.
     * Throws IllegalArgumentException if invalid.
     *
     * @param name Database name or identifier to validate
     * @return Cleaned database identifier string
     */
    static String validateDatabaseIdentifier(String name) {
        if (!name || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Database identifier cannot be empty")
        }
        String clean = name.trim().replaceAll("^['\"]+|['\"]+\$", '')
        if (clean.contains('..') || clean.contains('/') || clean.contains('\\') || clean.contains(':')) {
            throw new IllegalArgumentException("Invalid database identifier '${name}': path traversal and directory separators are forbidden")
        }
        return clean
    }

    /**
     * Resolves a database file by name, relative path, or explicit filesystem path.
     * Supports .db, .sqlite, and .sqlite3 extensions.
     *
     * Precedence:
     * 1. If explicit path (contains '/', '\', or ':'): resolves directly on disk as canonical File.
     * 2. If simple identifier: resolves strictly inside DATA_DIR with path safety validation.
     *
     * @param nameOrPath Database name or path identifier
     * @return Canonical File reference
     */
    static File resolveDatabase(String nameOrPath) {
        if (!nameOrPath || nameOrPath.trim().isEmpty()) {
            return DB_PATH
        }
        String clean = nameOrPath.trim().replaceAll("^['\"]+|['\"]+\$", '')

        // Explicit direct filesystem path handling
        if (isExplicitPath(clean)) {
            File directFile = new File(clean).canonicalFile
            if (directFile.exists() && directFile.isFile()) {
                return directFile
            }
            if (!clean.contains('.')) {
                File withDb = new File(clean + '.db').canonicalFile
                if (withDb.exists() && withDb.isFile()) return withDb
                File withSqlite = new File(clean + '.sqlite').canonicalFile
                if (withSqlite.exists() && withSqlite.isFile()) return withSqlite
                return withDb
            }
            return directFile
        }

        // Simple identifier scoped strictly to active DATA_DIR
        String validId = validateDatabaseIdentifier(clean)
        ensureDataDir()

        // Check if exact file exists in DATA_DIR
        File inData = new File(DATA_DIR, validId).canonicalFile
        if (inData.exists() && inData.isFile()) {
            verifyPathSafety(inData)
            return inData
        }

        // If no extension, try appending .db and .sqlite
        if (!validId.contains('.')) {
            File withDb = new File(DATA_DIR, validId + '.db').canonicalFile
            if (withDb.exists() && withDb.isFile()) {
                verifyPathSafety(withDb)
                return withDb
            }
            File withSqlite = new File(DATA_DIR, validId + '.sqlite').canonicalFile
            if (withSqlite.exists() && withSqlite.isFile()) {
                verifyPathSafety(withSqlite)
                return withSqlite
            }
            verifyPathSafety(withDb)
            return withDb
        }

        verifyPathSafety(inData)
        return inData
    }

    /**
     * Verifies that a resolved file resides strictly within the data/ directory.
     *
     * @param file File to verify
     */
    static void verifyPathSafety(File file) {
        String dataDirPath = DATA_DIR.canonicalPath
        String filePath = file.canonicalPath
        if (!filePath.startsWith(dataDirPath)) {
            throw new SecurityException("Security violation: Database path '${filePath}' escapes data directory '${dataDirPath}'")
        }
    }

    /**
     * Verifies that a database name does not escape the data/ directory.
     *
     * @param name Database name or identifier
     */
    static void verifyPathSafety(String name) {
        String clean = validateDatabaseIdentifier(name)
        File f = new File(DATA_DIR, clean).canonicalFile
        verifyPathSafety(f)
    }

    /**
     * Discovers all SQLite database files in the data/ directory.
     * Supported extensions: .db, .sqlite, .sqlite3
     */
    static List<File> listDatabases() {
        ensureDataDir()
        List<File> dbs = []
        DATA_DIR.eachFile { f ->
            String name = f.name.toLowerCase()
            if (f.isFile() && (name.endsWith('.db') || name.endsWith('.sqlite') || name.endsWith('.sqlite3')) && !name.contains('-wal') && !name.contains('-shm') && !name.contains('-journal')) {
                dbs << f
            }
        }
        return dbs.sort { a, b -> a.name.toLowerCase() <=> b.name.toLowerCase() }
    }

    /** Path to 7-Zip executable for RAR extraction. */
    static final String SEVEN_ZIP = 'C:\\Program Files\\7-Zip\\7z.exe'

    // -----------------------------------------------------------------------
    // Thresholds
    // -----------------------------------------------------------------------

    /** Maximum file size in bytes to index. Files exceeding this are truncated. */
    static final long MAX_FILE_SIZE = 1_048_576L  // 1 MB

    /** Number of documents per transaction batch during ingestion. */
    static final int BATCH_SIZE = 500

    // -----------------------------------------------------------------------
    // Encoding fallback chain
    // -----------------------------------------------------------------------

    /** Ordered list of charsets to attempt when reading text content. */
    static final List<String> ENCODINGS = ['UTF-8', 'ISO-8859-1']

    // -----------------------------------------------------------------------
    // Extension classification sets
    // -----------------------------------------------------------------------

    /**
     * Extensions that can be read as plain text directly from a stream.
     * Grouped by category for readability; flattened into a single Set.
     */
    static final Set<String> TEXT_EXTENSIONS = ([
        // Source code
        '.java', '.groovy', '.cpp', '.c', '.h', '.hpp', '.cs', '.py',
        '.pl', '.pm', '.ksh', '.sh', '.bat', '.cmd', '.js', '.css',
        '.jsp', '.jspf', '.pcpp', '.pc', '.esql', '.idl', '.cc',
        '.tcl', '.awk', '.cgi', '.bsh', '.cbl',
        // SQL and data
        '.sql', '.ctl', '.csv', '.dat',
        // Markup and configuration
        '.xml', '.html', '.htm', '.xsl', '.xsd', '.dtd', '.xhtml',
        '.tld', '.properties', '.conf', '.cfg', '.ini', '.pro', '.mf',
        '.classpath', '.project', '.prefs', '.launch', '.component',
        '.jsf', '.policy', '.config', '.profile', '.stb',
        // Documentation (text-based)
        '.txt', '.rtf', '.md', '.tex', '.log',
        // Build files
        '.mak', '.mk', '.make', '.in', '.dsp', '.dsw', '.sln',
        '.mki', '.defs',
        // Misc text
        '.apt', '.tst', '.txtjet', '.rdr', '.udr', '.drr',
        '.inc', '.rc', '.def', '.dep', '.err', '.out',
        '.msg', '.cat', '.ext', '.apl', '.qrp',
    ] as Set<String>).asImmutable()

    /**
     * Extensions that are compiled binaries or non-textual media — always skip.
     */
    static final Set<String> BINARY_EXTENSIONS = ([
        // Compiled code
        '.class', '.o', '.obj', '.dll', '.so', '.exe', '.jar', '.war',
        '.ear', '.a', '.lib', '.pch', '.idb', '.pdb', '.ncb', '.ilk',
        '.sbr', '.exp',
        // Images and media
        '.gif', '.jpg', '.jpeg', '.png', '.bmp', '.ico', '.svg',
        '.ttf', '.wav', '.swf', '.au',
        // Archives (nested — skip to avoid recursive extraction)
        '.zip', '.gz', '.tar', '.bz2', '.rar',
    ] as Set<String>).asImmutable()

    /**
     * Extensions requiring specialised library extraction (Apache POI, PDFBox).
     * Stubbed for now — returns a placeholder during extraction.
     */
    static final Set<String> DOCUMENT_EXTENSIONS = ([
        '.doc', '.docx', '.pdf', '.xls', '.xlsx', '.ppt', '.pptx',
        '.xlsm', '.vsd', '.odp',
    ] as Set<String>).asImmutable()

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Ensures the data directory exists.
     * Called at startup by scripts that write output.
     */
    static void ensureDataDir() {
        if (!DATA_DIR.exists()) {
            DATA_DIR.mkdirs()
        }
    }
}
