import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Emits canonical memory files and maintains the turn-hash authority.
 */
class TurnEmitter {

    /**
     * Loads the tab-separated hash index.
     *
     * @param indexFile Hash index file
     * @return Hash-keyed index records
     */
    static Map<String, Map> loadHashIndex(File indexFile) {
        LinkedHashMap<String, Map> records = new LinkedHashMap<>()
        if (!indexFile.exists()) return records
        indexFile.eachLine('UTF-8') { String line ->
            if (!line.trim() || line.startsWith('turn_hash\t')) return
            List<String> parts = line.split('\t', -1) as List<String>
            if (parts.size() < 6) return
            String hash = parts[0]
            Map record = records.computeIfAbsent(hash) {
                [hash: hash, path: parts[1], sequence: parts[5].isLong() ? parts[5].toLong() : 0L, sources: []]
            }
            record.sources << [path: parts[2], startLine: parts[3], endLine: parts[4]]
        }
        return records
    }

    /**
     * Emits one canonical document unless its hash is already known.
     *
     * @param outputRoot Worklog-chat root
     * @param entry Classified entry
     * @param tier turns, artifacts, or dross
     * @param sequence Global sequence
     * @param hashIndex In-memory hash authority
     * @param spillPath Optional spill pointer
     * @param dryRun Whether filesystem writes are disabled
     * @return Emission result
     */
    static Map emit(
        File outputRoot,
        Map entry,
        String tier,
        long sequence,
        Map<String, Map> hashIndex,
        String spillPath = null,
        boolean dryRun = false
    ) {
        String hash = entry.turnHash?.toString() ?: TurnDocument.hash(entry)
        entry.turnHash = hash
        String sourceLocation = location(entry)
        if (hashIndex.containsKey(hash)) {
            recordDuplicate(outputRoot, hashIndex, hash, sourceLocation, dryRun)
            return [emitted: false, duplicate: true, path: hashIndex[hash].path, sequence: hashIndex[hash].sequence]
        }

        String relativePath = FacetPath.build(tier, entry, sequence)
        String content = TurnDocument.render(entry, sequence, spillPath)
        if (!dryRun) {
            File destination = new File(outputRoot, relativePath)
            destination.parentFile.mkdirs()
            destination.setText(content, 'UTF-8')
        }
        hashIndex[hash] = [
            hash: hash,
            path: relativePath,
            sequence: sequence,
            sources: [[path: entry.sourcePath?.toString() ?: '', startLine: entry.startLine ?: 0, endLine: entry.endLine ?: 0]]
        ]
        return [emitted: true, duplicate: false, path: relativePath, sequence: sequence, bytes: content.getBytes(StandardCharsets.UTF_8).length]
    }

    /**
     * Records an additional source location and updates canonical front matter.
     *
     * @param outputRoot Worklog-chat root
     * @param hashIndex In-memory hash authority
     * @param hash Existing turn hash
     * @param sourceLocation Additional source location
     * @param dryRun Whether filesystem writes are disabled
     */
    static void recordDuplicate(
        File outputRoot,
        Map<String, Map> hashIndex,
        String hash,
        String sourceLocation,
        boolean dryRun = false
    ) {
        Map record = hashIndex[hash]
        Map parsed = parseLocation(sourceLocation)
        boolean exists = (record.sources as List).any {
            it.path == parsed.path && it.startLine.toString() == parsed.startLine.toString() &&
                it.endLine.toString() == parsed.endLine.toString()
        }
        if (!exists) record.sources << parsed
        if (dryRun || exists) return

        File canonical = new File(outputRoot, record.path.toString())
        if (!canonical.exists()) return
        List<String> additional = (record.sources as List).drop(1).collect {
            "${it.path}:${it.startLine}-${it.endLine}"
        }
        String content = canonical.getText('UTF-8')
        String line = "also_seen_in: ${JsonOutput.toJson(additional)}"
        if (content ==~ /(?s).*^also_seen_in:.*$/) {
            content = content.replaceFirst(/(?m)^also_seen_in:.*$/, java.util.regex.Matcher.quoteReplacement(line))
        } else {
            int close = content.indexOf('\n---', 4)
            if (close >= 0) content = content.substring(0, close) + '\n' + line + content.substring(close)
        }
        canonical.setText(content, 'UTF-8')
    }

    /**
     * Atomically saves the hash index.
     *
     * @param indexFile Target index file
     * @param hashIndex In-memory records
     */
    static void saveHashIndex(File indexFile, Map<String, Map> hashIndex) {
        indexFile.parentFile.mkdirs()
        File temporary = new File(indexFile.parentFile, "${indexFile.name}.tmp")
        temporary.withWriter('UTF-8') { Writer writer ->
            writer.write('turn_hash\temitted_path\tsource_path\tstart_line\tend_line\tsequence\n')
            hashIndex.values().sort { a, b -> (a.sequence as long) <=> (b.sequence as long) }.each { Map record ->
                (record.sources as List).each { Map source ->
                    writer.write([
                        record.hash, record.path, source.path, source.startLine, source.endLine, record.sequence
                    ].collect { sanitizeTsv(it?.toString()) }.join('\t') + '\n')
                }
            }
        }
        Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private static String location(Map entry) {
        return "${entry.sourcePath ?: ''}:${entry.startLine ?: 0}-${entry.endLine ?: 0}"
    }

    private static Map parseLocation(String location) {
        def matcher = location =~ /^(.*):(\d+)-(\d+)$/
        if (!matcher.matches()) return [path: location, startLine: 0, endLine: 0]
        return [path: matcher.group(1), startLine: matcher.group(2), endLine: matcher.group(3)]
    }

    private static String sanitizeTsv(String value) {
        return (value ?: '').replaceAll(/[\t\r\n]+/, ' ')
    }
}
