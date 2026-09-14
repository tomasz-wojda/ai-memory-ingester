import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds deterministic, tokenizer-safe paths for conversation memory documents.
 */
class FacetPath {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern('yyyyMMdd')
    private static final Set<String> MODES = ['research', 'innovate', 'plan', 'execute'] as Set<String>

    /**
     * Builds a relative emitted path for a classified entry.
     *
     * @param tier turns, artifacts, or dross
     * @param entry Classified entry
     * @param sequence Global sequence
     * @return Relative path beneath worklog-chat
     */
    static String build(String tier, Map entry, long sequence) {
        if (tier == 'dross') {
            return "dross/${sourceSlug(entry.sourcePath?.toString())}/${fileNameFor(tier, sequence)}"
        }
        String ticket = ticketFacet((entry.ticketKeys as List)?.find()?.toString())
        String date = dateFacet((entry.timestampRepair as Map)?.instant ?: (entry.timestampRepair as Map)?.timestamp)
        if (tier == 'artifacts') {
            return "artifacts/${ticket}/${date}/${fileNameFor(tier, sequence)}"
        }
        String mode = modeFacet(entry.rawMode?.toString())
        String impact = impactFacet(entry.impact?.toString())
        return "turns/${ticket}/${date}/${mode}/${impact}/${fileNameFor(tier, sequence)}"
    }

    /**
     * Converts arbitrary text into a lowercase ASCII-alphanumeric token.
     *
     * @param value Source value
     * @return Sanitized token
     */
    static String sanitizeToken(String value) {
        String normalized = java.text.Normalizer.normalize(value ?: '', java.text.Normalizer.Form.NFD)
            .replaceAll(/\p{M}/, '')
            .toLowerCase(Locale.ROOT)
            .replaceAll(/[^a-z0-9]/, '')
        return normalized
    }

    /**
     * Builds a ticket facet.
     *
     * @param ticket Ticket key
     * @return Ticket facet
     */
    static String ticketFacet(String ticket) {
        return TicketKey.facet(ticket)
    }

    /**
     * Builds a date facet.
     *
     * @param timestamp Instant, ISO timestamp, or null
     * @return Date facet
     */
    static String dateFacet(Object timestamp) {
        if (timestamp == null) return 'dunknown'
        try {
            Instant instant = timestamp instanceof Instant ? timestamp as Instant : Instant.parse(timestamp.toString())
            return 'd' + DATE_FORMAT.withZone(ZoneId.of('Europe/Warsaw')).format(instant)
        } catch (Exception ignored) {
            return 'dunknown'
        }
    }

    /**
     * Builds a mode facet.
     *
     * @param mode Source mode
     * @return Mode facet
     */
    static String modeFacet(String mode) {
        String token = sanitizeToken(mode)
        return MODES.contains(token) ? token : 'nomode'
    }

    /**
     * Builds an impact facet.
     *
     * @param impact Impact value
     * @return Impact facet
     */
    static String impactFacet(String impact) {
        String token = sanitizeToken(impact)
        return "impact-${token in ['high', 'low', 'none'] ? token : 'none'}"
    }

    /**
     * Builds the emitted filename.
     *
     * @param tier Document tier
     * @param sequence Global sequence
     * @return Emitted filename
     */
    static String fileNameFor(String tier, long sequence) {
        String prefix = tier == 'artifacts' ? 'artifact' : 'turn'
        return String.format('%s-%06d.md', prefix, sequence)
    }

    private static String sourceSlug(String path) {
        String token = (path ?: 'unknown')
            .replace('\\', '/')
            .replaceAll(/\/(?:prompt\.log(?:\.(?:old|new))?|PR\.log)$/, '')
            .tokenize('/')
            .takeRight(3)
            .join('-')
        return sanitizeToken(token) ?: 'unknown'
    }
}
