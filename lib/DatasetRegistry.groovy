/**
 * DatasetRegistry.groovy
 *
 * Abstract Dataset Registry and Lifecycle Manager for the Archive Memory Context Engine.
 * Manages logical grouping of independent SQLite databases into topical datasets, provides atomic JSON
 * persistence (data/datasets.json) with file locking, validates database path safety, and handles
 * active default dataset routing with self-healing fallback.
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

class DatasetRegistry {

    private static final Object LOCK = new Object()
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /**
     * Validates that a dataset identifier is alphanumeric with dashes/underscores.
     *
     * @param name Dataset name string to validate
     * @return Cleaned dataset name string
     */
    static String validateDatasetName(String name) {
        if (!name || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Dataset name cannot be empty")
        }
        String clean = name.trim().replaceAll("^['\"]+|['\"]+\$", '')
        if (!clean.matches("^[a-zA-Z0-9_-]+\$")) {
            throw new IllegalArgumentException("Invalid dataset name '${name}': only alphanumeric characters, underscores, and dashes are permitted")
        }
        return clean
    }

    /** Backward compatibility alias for validateDatasetName. */
    static String validateSetName(String name) {
        return validateDatasetName(name)
    }

    /**
     * Normalizes a database name or explicit path into a stored identifier.
     * If explicit path: returns canonical path string.
     * If simple name: validates identifier and appends .db if missing extension.
     *
     * @param dbName Database name, path, or identifier
     * @return Normalized database identifier or canonical path string
     */
    static String normalizeDbName(String dbName) {
        if (!dbName || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("Database identifier cannot be empty")
        }
        String clean = dbName.trim().replaceAll("^['\"]+|['\"]+\$", '')
        if (Config.isExplicitPath(clean)) {
            File directFile = Config.resolveDatabase(clean)
            return directFile.canonicalPath
        }
        String validId = Config.validateDatabaseIdentifier(clean)
        if (!validId.contains('.')) {
            return validId + ".db"
        }
        return validId
    }

    /**
     * Reads and parses data/datasets.json. If missing or corrupted, returns self-healed fallback.
     *
     * @return Map representing the datasets registry structure
     */
    static Map loadRegistry() {
        synchronized (LOCK) {
            Config.ensureDataDir()
            File file = Config.DATASETS_FILE

            // Backward compatibility fallback to sets.json if datasets.json doesn't exist
            if (!file.exists()) {
                File legacyFile = new File(Config.DATA_DIR, 'sets.json')
                if (legacyFile.exists() && legacyFile.isFile() && legacyFile.length() > 0) {
                    file = legacyFile
                }
            }

            if (!file.exists() || !file.isFile() || file.length() == 0) {
                return selfHeal()
            }

            try {
                def parsed = new JsonSlurper().parse(file)
                if (parsed instanceof Map) {
                    Map datasetsMap = (parsed.datasets instanceof Map) ? (parsed.datasets as Map) :
                                      ((parsed.sets instanceof Map) ? (parsed.sets as Map) : null)
                    if (datasetsMap != null) {
                        String active = (parsed.active_dataset ?: parsed.active_set) as String
                        if (!active || !datasetsMap.containsKey(active)) {
                            String firstKey = datasetsMap.keySet().find { it != null } ?: 'default'
                            active = firstKey
                        }
                        return [
                            schema_version: parsed.schema_version ?: 1,
                            active_dataset: active,
                            datasets: datasetsMap
                        ]
                    }
                }
                return selfHeal()
            } catch (Exception e) {
                return selfHeal()
            }
        }
    }

    /**
     * Atomically persists the registry structure to data/datasets.json using write-flush-rename.
     *
     * @param registry Map containing schema_version, active_dataset, and datasets dictionary
     */
    static void saveRegistry(Map registry) {
        synchronized (LOCK) {
            Config.ensureDataDir()
            File targetFile = Config.DATASETS_FILE
            File tempFile = new File(Config.DATA_DIR, "datasets.json.tmp." + UUID.randomUUID().toString())
            File lockFile = new File(Config.DATA_DIR, "datasets.lock")

            RandomAccessFile raf = null
            FileChannel channel = null
            FileLock lock = null

            try {
                raf = new RandomAccessFile(lockFile, "rw")
                channel = raf.getChannel()
                lock = channel.lock()

                Map cleanData = [
                    schema_version: registry.schema_version ?: 1,
                    active_dataset: registry.active_dataset ?: 'default',
                    datasets: registry.datasets ?: [:]
                ]

                String jsonContent = JsonOutput.prettyPrint(JsonOutput.toJson(cleanData))
                tempFile.withWriter('UTF-8') { writer ->
                    writer.write(jsonContent)
                    writer.flush()
                }

                Files.move(
                    tempFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } finally {
                if (lock != null && lock.isValid()) {
                    try { lock.release() } catch (Exception ignored) {}
                }
                if (channel != null && channel.isOpen()) {
                    try { channel.close() } catch (Exception ignored) {}
                }
                if (raf != null) {
                    try { raf.close() } catch (Exception ignored) {}
                }
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    /**
     * Creates an in-memory fallback dataset registry by discovering all databases in data/.
     *
     * @return Fresh self-healed registry Map
     */
    static Map selfHeal() {
        List<File> discoveredDbs = Config.listDatabases()
        List<String> dbNames = discoveredDbs.collect { it.name }
        if (dbNames.isEmpty()) {
            dbNames = [Config.DB_PATH.name]
        }

        Map fallback = [
            schema_version: 1,
            active_dataset: "default",
            datasets: [
                "default": [
                    created_at: LocalDateTime.now().format(ISO_FORMATTER),
                    description: "Default repository dataset",
                    databases: dbNames
                ]
            ]
        ]
        try {
            saveRegistry(fallback)
        } catch (Exception ignored) {}
        return fallback
    }

    /**
     * Retrieves the currently active dataset name.
     *
     * @return String identifier of active dataset
     */
    static String getActiveDataset() {
        Map reg = loadRegistry()
        return (reg.active_dataset ?: 'default').toString()
    }

    /** Backward compatibility alias for getActiveDataset. */
    static String getActiveSet() {
        return getActiveDataset()
    }

    /**
     * Sets the active dataset pointer.
     *
     * @param datasetName Dataset name to make active
     */
    static void setActiveDataset(String datasetName) {
        String cleanName = validateDatasetName(datasetName)
        synchronized (LOCK) {
            Map reg = loadRegistry()
            Map datasets = (reg.datasets as Map) ?: [:]
            if (!datasets.containsKey(cleanName)) {
                throw new IllegalArgumentException("Dataset '${cleanName}' does not exist in registry")
            }
            reg.active_dataset = cleanName
            saveRegistry(reg)
        }
    }

    /** Backward compatibility alias for setActiveDataset. */
    static void setActiveSet(String setName) {
        setActiveDataset(setName)
    }

    /**
     * Creates a new dataset with an optional initial list of databases.
     *
     * @param datasetName Name of the new dataset
     * @param databases Initial database filenames or identifiers
     * @param description Optional human-readable description
     */
    static void createDataset(String datasetName, List<String> databases = [], String description = null) {
        String cleanName = validateDatasetName(datasetName)
        synchronized (LOCK) {
            Map reg = loadRegistry()
            Map datasets = (reg.datasets as Map) ?: [:]

            if (datasets.containsKey(cleanName)) {
                throw new IllegalArgumentException("Dataset '${cleanName}' already exists")
            }

            List<String> validDbs = []
            databases?.each { db ->
                if (db) {
                    String norm = normalizeDbName(db)
                    if (!Config.isExplicitPath(db)) {
                        Config.verifyPathSafety(norm)
                    }
                    if (!validDbs.contains(norm)) validDbs << norm
                }
            }

            datasets[cleanName] = [
                created_at: LocalDateTime.now().format(ISO_FORMATTER),
                description: description ?: "User-defined dataset",
                databases: validDbs
            ]

            reg.datasets = datasets
            saveRegistry(reg)
        }
    }

    /** Backward compatibility alias for createDataset. */
    static void createSet(String setName, List<String> databases = [], String description = null) {
        createDataset(setName, databases, description)
    }

    /**
     * Deletes a dataset definition. Preserves physical database files on disk.
     *
     * @param datasetName Name of the dataset to delete
     */
    static void deleteDataset(String datasetName) {
        String cleanName = validateDatasetName(datasetName)
        if (cleanName.equalsIgnoreCase('default')) {
            throw new IllegalArgumentException("Cannot delete default dataset 'default'")
        }

        synchronized (LOCK) {
            Map reg = loadRegistry()
            Map datasets = (reg.datasets as Map) ?: [:]

            if (!datasets.containsKey(cleanName)) {
                throw new IllegalArgumentException("Dataset '${cleanName}' does not exist")
            }

            datasets.remove(cleanName)
            if (reg.active_dataset == cleanName) {
                reg.active_dataset = "default"
            }

            reg.datasets = datasets
            saveRegistry(reg)
        }
    }

    /** Backward compatibility alias for deleteDataset. */
    static void deleteSet(String setName) {
        deleteDataset(setName)
    }

    /**
     * Renames an existing dataset.
     *
     * @param oldName Existing dataset name
     * @param newName New dataset name
     */
    static void renameDataset(String oldName, String newName) {
        String cleanOld = validateDatasetName(oldName)
        String cleanNew = validateDatasetName(newName)

        if (cleanOld.equalsIgnoreCase('default')) {
            throw new IllegalArgumentException("Cannot rename the default dataset 'default'")
        }

        synchronized (LOCK) {
            Map reg = loadRegistry()
            Map datasets = (reg.datasets as Map) ?: [:]

            if (!datasets.containsKey(cleanOld)) {
                throw new IllegalArgumentException("Source dataset '${cleanOld}' does not exist")
            }
            if (datasets.containsKey(cleanNew)) {
                throw new IllegalArgumentException("Target dataset '${cleanNew}' already exists")
            }

            Map data = datasets.remove(cleanOld) as Map
            datasets[cleanNew] = data

            if (reg.active_dataset == cleanOld) {
                reg.active_dataset = cleanNew
            }

            reg.datasets = datasets
            saveRegistry(reg)
        }
    }

    /** Backward compatibility alias for renameDataset. */
    static void renameSet(String oldName, String newName) {
        renameDataset(oldName, newName)
    }

    /**
     * Adds a database identifier to a dataset.
     *
     * @param datasetName Target dataset name
     * @param dbName Database name or identifier to add
     */
    static void addDatabaseToDataset(String datasetName, String dbName) {
        String cleanSet = validateDatasetName(datasetName)
        String normDb = normalizeDbName(dbName)
        if (!Config.isExplicitPath(dbName)) {
            Config.verifyPathSafety(normDb)
        }

        synchronized (LOCK) {
            Map reg = loadRegistry()
            Map datasets = (reg.datasets as Map) ?: [:]

            if (!datasets.containsKey(cleanSet)) {
                datasets[cleanSet] = [
                    created_at: LocalDateTime.now().format(ISO_FORMATTER),
                    description: "Auto-created dataset",
                    databases: []
                ]
            }

            Map setEntry = datasets[cleanSet] as Map
            List<String> dbs = (setEntry.databases as List<String>) ?: []
            if (!dbs.contains(normDb)) {
                dbs << normDb
            }
            setEntry.databases = dbs
            datasets[cleanSet] = setEntry
            reg.datasets = datasets
            saveRegistry(reg)
        }
    }

    /** Backward compatibility alias for addDatabaseToDataset. */
    static void addDatabaseToSet(String setName, String dbName) {
        addDatabaseToDataset(setName, dbName)
    }

    /**
     * Removes a database identifier from a dataset.
     *
     * @param datasetName Target dataset name
     * @param dbName Database name to remove
     */
    static void removeDatabaseFromDataset(String datasetName, String dbName) {
        String cleanSet = validateDatasetName(datasetName)
        String normDb = normalizeDbName(dbName)

        synchronized (LOCK) {
            Map reg = loadRegistry()
            Map datasets = (reg.datasets as Map) ?: [:]

            if (!datasets.containsKey(cleanSet)) {
                throw new IllegalArgumentException("Dataset '${cleanSet}' does not exist")
            }

            Map setEntry = datasets[cleanSet] as Map
            List<String> dbs = (setEntry.databases as List<String>) ?: []
            dbs.remove(normDb)
            setEntry.databases = dbs
            datasets[cleanSet] = setEntry
            reg.datasets = datasets
            saveRegistry(reg)
        }
    }

    /** Backward compatibility alias for removeDatabaseFromDataset. */
    static void removeDatabaseFromSet(String setName, String dbName) {
        removeDatabaseFromDataset(setName, dbName)
    }

    /**
     * Resolves all member database Files belonging to the specified dataset.
     *
     * @param datasetName Dataset name to resolve (null defaults to active dataset)
     * @return List of existing File references for member databases
     */
    static List<File> getDatabasesForDataset(String datasetName = null) {
        Map reg = loadRegistry()
        String targetName = datasetName ? validateDatasetName(datasetName) : (reg.active_dataset ?: 'default')
        Map datasets = (reg.datasets as Map) ?: [:]

        Map setEntry = datasets[targetName] as Map
        if (setEntry == null) {
            File directDb = Config.resolveDatabase(targetName)
            if (directDb.exists()) {
                return [directDb]
            }
            return []
        }

        List<String> dbNames = (setEntry.databases as List<String>) ?: []
        List<File> files = []
        dbNames.each { String name ->
            File dbFile = Config.resolveDatabase(name)
            if (dbFile.exists()) {
                files << dbFile
            }
        }
        return files
    }

    /** Backward compatibility alias for getDatabasesForDataset. */
    static List<File> getDatabasesForSet(String setName = null) {
        return getDatabasesForDataset(setName)
    }

    /**
     * Lists all registered datasets with metadata summary.
     *
     * @return List of Maps containing dataset details
     */
    static List<Map> listDatasets() {
        Map reg = loadRegistry()
        String active = (reg.active_dataset ?: 'default').toString()
        Map datasets = (reg.datasets as Map) ?: [:]

        List<Map> summary = []
        datasets.each { String name, Map data ->
            List<String> dbNames = (data.databases as List<String>) ?: []
            long totalBytes = 0
            List<String> existingMembers = []

            dbNames.each { String dbName ->
                File f = Config.resolveDatabase(dbName)
                if (f.exists()) {
                    totalBytes += f.length()
                    existingMembers << dbName
                } else {
                    existingMembers << "${dbName} (missing)"
                }
            }

            summary << [
                name: name,
                is_active: (name == active),
                database_count: dbNames.size(),
                total_bytes: totalBytes,
                created_at: data.created_at ?: '-',
                description: data.description ?: '',
                members_formatted: existingMembers.join(', ')
            ]
        }
        return summary.sort { a, b ->
            if (a.name == 'default') return -1
            if (b.name == 'default') return 1
            return a.name.compareTo(b.name)
        }
    }

    /** Backward compatibility alias for listDatasets. */
    static List<Map> listSets() {
        return listDatasets()
    }
}
