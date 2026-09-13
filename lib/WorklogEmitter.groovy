import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WorklogEmitter {
    static Map loadState(File stateFile) {
        if (!stateFile.exists()) return [schema_version: 1, sources: [:], capsules: [:]]
        Map state = new JsonSlurper().parse(stateFile) as Map
        state.sources = state.sources ?: [:]
        state.capsules = state.capsules ?: [:]
        return state
    }

    static Map stageSource(File outputRoot, Map parsed, File source, boolean evidence = false, boolean dryRun = false) {
        String ticketFacet = (parsed.ticket ?: 'NOTICKET').toLowerCase().replaceAll(/[^a-z0-9]/, '')
        List<String> outputs = []
        if (evidence) {
            String date = parsed.source_date?.toString()?.replace('-', '') ?: 'unknown'
            String slug = source.name.toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-|-$/, '')
            String relative = "artifacts/${ticketFacet}/d${date}/worklog-evidence-${slug}.md"
            outputs << relative
            if (!dryRun) writeAtomic(new File(outputRoot, relative), WorklogDocument.renderEvidence(source, parsed.source, parsed.ticket))
        } else {
            Map<String, Integer> latestByCategory = [:]
            (parsed.chunks as List<Map>).eachWithIndex { Map chunk, int index ->
                latestByCategory[chunk.category.toString()] = index
            }
            (parsed.chunks as List<Map>).eachWithIndex { Map chunk, int index ->
                String headingSlug = WorklogDocument.categorySlug(chunk.heading?.toString())
                String relative = "worklogs/${ticketFacet}/${chunk.category}/${String.format('%04d', index + 1)}-${headingSlug}.md"
                outputs << relative
                if (!dryRun) {
                    boolean current = latestByCategory[chunk.category.toString()] == index
                    writeAtomic(new File(outputRoot, relative), WorklogDocument.renderChunk(chunk, source, current))
                }
            }
        }
        return [outputs: outputs, ticket: parsed.ticket, evidence: evidence]
    }

    static Map replaceSource(File outputRoot, Map state, String sourcePath, Map staged, String sourceHash, File source, boolean dryRun = false) {
        Map previous = (state.sources[sourcePath] ?: [:]) as Map
        List<String> priorPaths = (previous.outputs ?: []) as List<String>
        List<String> nextPaths = staged.outputs as List<String>
        List<String> deleted = priorPaths.findAll { !nextPaths.contains(it) }
        if (!dryRun) {
            removeStaleOutputs(outputRoot, deleted)
            state.sources[sourcePath] = [
                source_hash: sourceHash,
                source_size: source.length(),
                source_modified: source.lastModified(),
                parser_version: Config.WORKLOG_PARSER_VERSION,
                ticket: staged.ticket,
                evidence: staged.evidence,
                outputs: nextPaths
            ]
        }
        return [emitted: nextPaths.size(), replaced: priorPaths.intersect(nextPaths).size(), deleted: deleted.size()]
    }

    static void removeStaleOutputs(File outputRoot, List<String> paths) {
        paths.each { String path ->
            File target = new File(outputRoot, path)
            if (target.exists()) target.delete()
        }
    }

    static File writeCapsule(File outputRoot, String ticket, String content, boolean dryRun = false) {
        String facet = ticket.toLowerCase().replaceAll(/[^a-z0-9]/, '')
        File target = new File(outputRoot, "ticket-state/${facet}.md")
        if (!dryRun) writeAtomic(target, content)
        return target
    }

    static void saveState(File stateFile, Map state) {
        stateFile.parentFile.mkdirs()
        writeAtomic(stateFile, JsonOutput.prettyPrint(JsonOutput.toJson(state)) + '\n')
    }

    private static void writeAtomic(File target, String content) {
        target.parentFile.mkdirs()
        File temporary = new File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temporary.setText(content, 'UTF-8')
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
