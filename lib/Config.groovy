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
    static final File DATA_DIR = new File('data').canonicalFile

    /** SQLite database file path. */
    static final File DB_PATH = new File(DATA_DIR, 'memory.db')

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
