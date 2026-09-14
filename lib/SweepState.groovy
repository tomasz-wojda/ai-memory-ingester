import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Persists incremental source offsets and detects destructive source changes.
 */
class SweepState {

    /**
     * Loads sweep state or returns an empty state.
     *
     * @param stateFile JSON state file
     * @return Mutable state map
     */
    static Map load(File stateFile) {
        if (!stateFile.exists() || stateFile.length() == 0L) {
            return [schema_version: 1, sources: [:]]
        }
        try {
            Map parsed = new JsonSlurper().parse(stateFile) as Map
            if (!(parsed.sources instanceof Map)) parsed.sources = [:]
            if (!parsed.schema_version) parsed.schema_version = 1
            return parsed
        } catch (Exception ignored) {
            return [schema_version: 1, sources: [:]]
        }
    }

    /**
     * Returns the next byte offset for a source.
     *
     * @param state Loaded state
     * @param sourcePath Workspace-relative source path
     * @return Stored byte offset or zero
     */
    static long offsetFor(Map state, String sourcePath) {
        Map record = (state.sources as Map)?.get(sourcePath) as Map
        return (record?.byte_offset ?: 0L) as long
    }

    /**
     * Records successful processing of a complete source prefix.
     *
     * @param state Loaded state
     * @param sourcePath Workspace-relative source path
     * @param source Source file
     * @param byteOffset Processed byte offset
     */
    static void record(Map state, String sourcePath, File source, long byteOffset = -1L) {
        if (!(state.sources instanceof Map)) state.sources = [:]
        long length = source.length()
        long processed = byteOffset < 0L ? length : Math.min(byteOffset, length)
        state.sources[sourcePath] = [
            byte_offset: processed,
            byte_length: length,
            prefix_hash: hashPrefix(source, processed),
            parser_version: Config.CHAT_PARSER_VERSION
        ]
    }

    /**
     * Atomically saves sweep state.
     *
     * @param stateFile Target JSON file
     * @param state State map
     */
    static void save(File stateFile, Map state) {
        stateFile.parentFile.mkdirs()
        File temporary = new File(stateFile.parentFile, "${stateFile.name}.tmp")
        temporary.setText(JsonOutput.prettyPrint(JsonOutput.toJson(state)) + '\n', 'UTF-8')
        Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Determines whether a source must be reparsed from byte zero.
     *
     * @param source Source file
     * @param record Stored source record
     * @return Whether truncation or prefix mutation was detected
     */
    static boolean needsFullRescan(File source, Map record) {
        if (!record) return false
        long offset = (record.byte_offset ?: 0L) as long
        long previousLength = (record.byte_length ?: offset) as long
        if (source.length() < offset || source.length() < previousLength) return true
        String expected = record.prefix_hash?.toString()
        return expected != null && expected != hashPrefix(source, offset)
    }

    private static String hashPrefix(File source, long byteCount) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        source.withInputStream { InputStream input ->
            byte[] buffer = new byte[8192]
            long remaining = byteCount
            while (remaining > 0L) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length as long, remaining))
                if (read < 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().collect { String.format('%02x', it & 0xff) }.join()
    }
}
