import groovy.json.JsonOutput
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Defines conversation turn identity and renders canonical Markdown documents.
 */
class TurnDocument {

    /**
     * Renders a turn or artifact document with fixed-order YAML front matter.
     *
     * @param entry Classified entry
     * @param sequence Global sequence
     * @param spillPath Optional relative spill path
     * @param alsoSeenIn Additional source locations
     * @return Canonical Markdown
     */
    static String render(Map entry, long sequence, String spillPath = null, List<String> alsoSeenIn = []) {
        Map repair = (entry.timestampRepair ?: [:]) as Map
        List<String> tickets = ((entry.ticketKeys ?: []) as List).collect { it.toString() }
        String timestamp = repair.timestamp?.toString() ?: 'unknown'
        String date = timestamp == 'unknown' ? 'unknown' : timestamp.take(10)
        String source = entry.sourcePath?.toString() ?: 'unknown'
        String session = "${sourceSlug(source)}:${date}"
        String turnHash = entry.turnHash?.toString() ?: hash(entry)
        String assistant = entry.assistant?.toString() ?: ''
        if (spillPath) assistant = abstractOf(assistant ?: entry.rawBody?.toString())

        List<String> lines = [
            '---',
            "timestamp: ${yaml(timestamp)}",
            "timestamp_confidence: ${yaml(repair.confidence?.toString() ?: 'lost')}",
            "mode: ${yaml(FacetPath.modeFacet(entry.rawMode?.toString()))}",
            "tickets: ${JsonOutput.toJson(tickets)}",
            "session: ${yaml(session)}",
            "source: ${yaml(source.replace('\\', '/'))}",
            "source_lines: ${yaml("${entry.startLine ?: 0}-${entry.endLine ?: 0}")}",
            "seq: ${sequence}",
            "impact: ${yaml(entry.impact?.toString() ?: 'none')}",
            "turn_hash: ${yaml(turnHash)}"
        ]
        if (entry.journalObservationId) lines << "journal_observation_id: ${yaml(entry.journalObservationId.toString())}"
        if (entry.journalLogicalTurnKey) lines << "journal_logical_turn_key: ${yaml(entry.journalLogicalTurnKey.toString())}"
        if (entry.journalSequence) lines << "journal_sequence: ${entry.journalSequence}"
        if (entry.journalSourceIde) lines << "journal_source_ide: ${yaml(entry.journalSourceIde.toString())}"
        if (entry.journalSourceFidelity) lines << "journal_source_fidelity: ${yaml(entry.journalSourceFidelity.toString())}"
        if (spillPath) lines << "spill: ${yaml(spillPath.replace('\\', '/'))}"
        if (alsoSeenIn) lines << "also_seen_in: ${JsonOutput.toJson(alsoSeenIn)}"
        lines.addAll(['---', '', '## User', '', entry.user?.toString() ?: '', '', '## Assistant', '', assistant])

        Map sections = (entry.sections ?: [:]) as Map
        if (sections) {
            lines.addAll(['', '## Details', ''])
            sections.each { Object label, Object value ->
                lines.addAll(["### ${label}", '', value?.toString() ?: '', ''])
            }
        }
        return lines.join('\n').trim() + '\n'
    }

    /**
     * Normalizes semantic turn content for stable hashing.
     *
     * @param entry Parsed entry or source string
     * @return Normalized text
     */
    static String normalizeForHash(Object entry) {
        String text
        if (entry instanceof Map) {
            text = "${entry.user ?: ''}\n${entry.assistant ?: ''}"
        } else {
            text = entry?.toString() ?: ''
        }
        return text
            .replaceAll(/(?im)^\s*(?:USER(?: PROMPTS?)?|ASSISTANT(?: OUTPUT)?|PROMPT|RESPONSE|TIMESTAMP)\s*:\s*/, '')
            .replaceAll(/\[?\d{4}-\d{2}-\d{2}(?:[ T]\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:?\d{2})?)?\]?/, '')
            .toLowerCase(Locale.ROOT)
            .replaceAll(/\s+/, ' ')
            .trim()
    }

    /**
     * Computes the SHA-256 turn identity.
     *
     * @param entry Parsed entry or text
     * @return Lowercase hexadecimal hash
     */
    static String hash(Object entry) {
        byte[] digest = MessageDigest.getInstance('SHA-256')
            .digest(normalizeForHash(entry).getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format('%02x', it & 0xff) }.join()
    }

    /**
     * Produces a bounded non-empty summary for a spilled body.
     *
     * @param body Full body
     * @param maxChars Maximum characters
     * @return Abstract text
     */
    static String abstractOf(String body, int maxChars = 1200) {
        String compact = (body ?: '').replaceAll(/\s+/, ' ').trim()
        if (!compact) return 'Body spilled to the referenced artifact.'
        return compact.length() <= maxChars ? compact : compact.take(maxChars).trim() + '…'
    }

    private static String yaml(String value) {
        return JsonOutput.toJson(value ?: '')
    }

    private static String sourceSlug(String path) {
        String normalized = path.replace('\\', '/')
        String base = normalized.tokenize('/').takeRight(3).join('-')
        return FacetPath.sanitizeToken(base) ?: 'unknown'
    }
}
