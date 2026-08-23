/**
 * SetRegistry.groovy
 *
 * Abstract Database Set Registry and Lifecycle Manager for the Archive Memory Context Engine.
 * Manages logical grouping of independent SQLite databases into topical sets, provides atomic JSON
 * persistence (data/sets.json) with file locking, validates database path safety, and handles
 * active default set routing with self-healing fallback.
 *
 * Role in Project:
 * Core configuration and state management module for Phase 3 federated search. Loaded via classpath
 * by CLI runners and query engines (`groovy -cp lib ...`).
 */

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SetRegistry {

    private static final Object LOCK = new Object()
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /**
     * Validates that a set identifier is alphanumeric with dashes/underscores.
     *
     * @param name Set name string to validate
     * @return Cleaned set name string
     */
    static String validateSetName(String name) {
        if (!name || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Set name cannot be empty")
        }
        String clean = name.trim().replaceAll("^['\"]+|['\"]+\$", '')
        if (!clean.matches("^[a-zA-Z0-9_-]+\$")) {
            throw new IllegalArgumentException("Invalid set name '${name}': only alphanumeric characters, underscores, and dashes are permitted")
        }
        return clean
    }

    /**
     * Normalizes a database name into a canonical database filename inside data/.
     *
     * @param dbName Database name or identifier
     * @return Normalized database filename (e.g., 'feynman.db')
     */
    static String normalizeDbName(String dbName) {
        String clean = Config.validateDatabaseIdentifier(dbName)
        if (!clean.contains('.')) {
            return clean + ".db"
        }
        return clean
    }

    /**
     * Reads and parses data/sets.json. If missing or corrupted, returns self-healed fallback.
     *
     * @return Map representing the sets registry structure
     */
    static Map loadRegistry() {
        synchronized (LOCK) {
            Config.ensureDataDir()
            File file = Config.SETS_FILE

            if (!file.exists() || !file.isFile() || file.length() == 0) {
                return selfHeal()
            }

            try {
                // External call: JsonSlurper parses JSON text into Groovy Map
                def parsed = new JsonSlurper().parse(file)
                if (parsed instanceof Map && parsed.sets instanceof Map) {
                    // Ensure active_set pointer is valid
                    String active = parsed.active_set as String
                    if (!active || !parsed.sets.containsKey(active)) {
                        String firstKey = parsed.sets.keySet().find { it != null } ?: 'default'
                        parsed.active_set = firstKey
                    }
                    return parsed as Map
                }
                return selfHeal()
            } catch (Exception e) {
                // JSON malformed or interrupted read — self heal in-memory
                return selfHeal()
            }
        }
    }

    /**
     * Atomically persists the registry structure to data/sets.json using write-flush-rename.
     *
     * @param registry Map containing schema_version, active_set, and sets dictionary
     */
    static void saveRegistry(Map registry) {
        synchronized (LOCK) {
            Config.ensureDataDir()
            File targetFile = Config.SETS_FILE
            File tempFile = new File(Config.DATA_DIR, "sets.json.tmp").canonicalFile

            // Ensure schema metadata
            registry.schema_version = registry.schema_version ?: 1
            registry.active_set = registry.active_set ?: "default"

            // Format JSON with 2-space pretty printing
            String jsonText = JsonOutput.prettyPrint(JsonOutput.toJson(registry))

            // Write to temporary file with file-level channel locking
            tempFile.withOutputStream { fos ->
                fos.write(jsonText.getBytes("UTF-8"))
                fos.flush()
            }

            // Atomic move/rename replacing existing sets.json
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }
    }

    /**
     * Constructs a resilient in-memory default registry from discovered databases in data/.
     *
     * @return Self-healed registry Map
     */
    static Map selfHeal() {
        List<File> discovered = Config.listDatabases()
        List<String> dbNames = discovered.collect { it.name }
        if (dbNames.isEmpty()) {
            dbNames = ["memory.db"]
        }

        Map fallback = [
            schema_version: 1,
            active_set: "default",
            sets: [
                default: [
                    created_at: LocalDateTime.now().format(ISO_FORMATTER),
                    description: "Default repository database set",
                    databases: dbNames
                ]
            ]
        ]
        return fallback
    }

    /**
     * Returns the name of the currently active default set.
     *
     * @return Active set name string
     */
    static String getActiveSet() {
        Map reg = loadRegistry()
        return (reg.active_set as String) ?: "default"
    }

    /**
     * Updates the active default set pointer.
     *
     * @param setName Name of the set to make active
     */
    static void setActiveSet(String setName) {
        String clean = validateSetName(setName)
        Map reg = loadRegistry()
        if (!reg.sets.containsKey(clean)) {
            throw new IllegalArgumentException("Cannot set active: Database set '${clean}' does not exist")
        }
        reg.active_set = clean
        saveRegistry(reg)
    }

    /**
     * Creates a new database set definition.
     *
     * @param setName Name of the new set
     * @param databases Initial list of member database names or identifiers
     * @param description Optional human-readable description
     */
    static void createSet(String setName, List<String> databases = [], String description = "") {
        String clean = validateSetName(setName)
        Map reg = loadRegistry()

        if (reg.sets.containsKey(clean)) {
            throw new IllegalArgumentException("Database set '${clean}' already exists")
        }

        List<String> normDbs = []
        if (databases) {
            databases.each { db ->
                String n = normalizeDbName(db)
                if (!normDbs.contains(n)) {
                    normDbs << n
                }
            }
        }

        reg.sets[clean] = [
            created_at: LocalDateTime.now().format(ISO_FORMATTER),
            description: description ?: "User-defined database set",
            databases: normDbs
        ]

        // If this is the only set, make it active
        if (reg.sets.size() == 1) {
            reg.active_set = clean
        }

        saveRegistry(reg)
    }

    /**
     * Deletes a database set definition from data/sets.json.
     * Physical database files are NEVER deleted.
     *
     * @param setName Name of the set to delete
     */
    static void deleteSet(String setName) {
        String clean = validateSetName(setName)
        Map reg = loadRegistry()

        if (!reg.sets.containsKey(clean)) {
            throw new IllegalArgumentException("Database set '${clean}' does not exist")
        }

        reg.sets.remove(clean)

        // If the active set was deleted, select another available set or recreate default
        if (reg.active_set == clean) {
            if (!reg.sets.isEmpty()) {
                reg.active_set = reg.sets.keySet().iterator().next()
            } else {
                Map healed = selfHeal()
                reg.active_set = healed.active_set
                reg.sets = healed.sets
            }
        }

        saveRegistry(reg)
    }

    /**
     * Renames an existing database set definition and updates active pointer if needed.
     *
     * @param oldName Existing set name
     * @param newName New set name
     */
    static void renameSet(String oldName, String newName) {
        String cleanOld = validateSetName(oldName)
        String cleanNew = validateSetName(newName)
        Map reg = loadRegistry()

        if (!reg.sets.containsKey(cleanOld)) {
            throw new IllegalArgumentException("Database set '${cleanOld}' does not exist")
        }
        if (reg.sets.containsKey(cleanNew)) {
            throw new IllegalArgumentException("Target set name '${cleanNew}' already exists")
        }

        Map setObj = reg.sets.remove(cleanOld) as Map
        reg.sets[cleanNew] = setObj

        if (reg.active_set == cleanOld) {
            reg.active_set = cleanNew
        }

        saveRegistry(reg)
    }

    /**
     * Adds a database identifier to an existing set (idempotent).
     *
     * @param setName Target set name
     * @param dbName Database name to add
     */
    static void addDatabaseToSet(String setName, String dbName) {
        String cleanSet = validateSetName(setName)
        String cleanDb = normalizeDbName(dbName)
        Map reg = loadRegistry()

        if (!reg.sets.containsKey(cleanSet)) {
            // Auto-create set if it doesn't exist
            createSet(cleanSet, [cleanDb])
            return
        }

        Map setObj = reg.sets[cleanSet] as Map
        List<String> dbs = (setObj.databases ?: []) as List<String>
        if (!dbs.contains(cleanDb)) {
            dbs << cleanDb
            setObj.databases = dbs
            saveRegistry(reg)
        }
    }

    /**
     * Removes a database identifier from an existing set.
     * Physical database file is preserved.
     *
     * @param setName Target set name
     * @param dbName Database name to remove
     */
    static void removeDatabaseFromSet(String setName, String dbName) {
        String cleanSet = validateSetName(setName)
        String cleanDb = normalizeDbName(dbName)
        Map reg = loadRegistry()

        if (!reg.sets.containsKey(cleanSet)) {
            throw new IllegalArgumentException("Database set '${cleanSet}' does not exist")
        }

        Map setObj = reg.sets[cleanSet] as Map
        List<String> dbs = (setObj.databases ?: []) as List<String>
        dbs.remove(cleanDb)
        setObj.databases = dbs
        saveRegistry(reg)
    }

    /**
     * Returns detailed metadata for all defined sets including document counts and physical sizes.
     *
     * @return List of Map items for each set
     */
    static List<Map> listSets() {
        Map reg = loadRegistry()
        String active = reg.active_set as String
        List<Map> results = []

        reg.sets.each { String name, Object details ->
            Map setObj = details as Map
            List<String> dbs = (setObj.databases ?: []) as List<String>
            long totalDocs = 0
            long totalBytes = 0
            List<String> memberStatusList = []

            dbs.each { String dbName ->
                File dbFile = new File(Config.DATA_DIR, dbName)
                if (dbFile.exists() && dbFile.isFile()) {
                    totalBytes += dbFile.length()
                    memberStatusList << dbName
                } else {
                    memberStatusList << "${dbName} (MISSING)"
                }
            }

            results << [
                name: name,
                is_active: (name == active),
                database_count: dbs.size(),
                total_bytes: totalBytes,
                databases: dbs,
                members_formatted: memberStatusList.join(', '),
                created_at: setObj.created_at ?: "",
                description: setObj.description ?: ""
            ]
        }

        return results.sort { a, b ->
            if (a.is_active != b.is_active) return b.is_active <=> a.is_active
            return a.name.toLowerCase() <=> b.name.toLowerCase()
        }
    }

    /**
     * Resolves the list of physical database File handles belonging to a set.
     *
     * @param setName Target set name (or null for active default set)
     * @return List of File objects for all existing member databases
     */
    static List<File> getDatabasesForSet(String setName = null) {
        String target = setName ? validateSetName(setName) : getActiveSet()
        Map reg = loadRegistry()

        if (!reg.sets.containsKey(target)) {
            throw new IllegalArgumentException("Database set '${target}' does not exist")
        }

        Map setObj = reg.sets[target] as Map
        List<String> dbs = (setObj.databases ?: []) as List<String>
        List<File> files = []

        dbs.each { String dbName ->
            try {
                File f = Config.resolveDatabase(dbName)
                if (f.exists() && f.isFile()) {
                    files << f
                }
            } catch (Exception ignored) {
                // Ignore missing or unreadable databases during resolution
            }
        }

        return files
    }
}
