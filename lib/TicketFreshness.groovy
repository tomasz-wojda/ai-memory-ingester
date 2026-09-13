import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TicketFreshness {
    static Map classify(String ticket, List<Map> chunks, File outputRoot) {
        List<Instant> sourceTimes = (chunks ?: []).collect { Map chunk ->
            parseInstant(chunk.latest_source_time?.toString()) ?: Instant.ofEpochMilli((chunk.source_mtime ?: 0L) as long)
        }.findAll { it }
        Instant authoritativeThrough = sourceTimes ? sourceTimes.max() : null
        List<Map> highImpact = turnEvidence(ticket, outputRoot).findAll {
            it.impact == 'high' &&
                !it.retrieval_meta &&
                it.explicit_user_ticket &&
                it.material_ticket_evidence &&
                it.timestamp_confidence in ['exact', 'local'] &&
                it.event_instant
        }
        List<Map> newer = authoritativeThrough
            ? highImpact.findAll { (it.event_instant as Instant).isAfter(authoritativeThrough) }
            : []
        newer.sort(TicketFreshness.&compareTurnEvidence)
        Instant evidenceThrough = highImpact.collect { it.event_instant as Instant }.findAll { it }.max()
        String freshness = authoritativeThrough == null
            ? 'undetermined'
            : (newer ? 'stale' : 'fresh')
        return [
            freshness: freshness,
            authoritative_through: authoritativeThrough?.toString(),
            newer_evidence_through: newer ? (newer[0].event_instant as Instant).toString() : null,
            evidence_through: evidenceThrough?.toString(),
            newer_evidence: newer.collect { Map item ->
                [
                    file_path: item.file_path,
                    event_time: item.event_time,
                    timestamp_confidence: item.timestamp_confidence,
                    sequence: item.sequence
                ]
            }
        ]
    }

    static List<Map> turnEvidence(String ticket, File outputRoot) {
        String facet = normalizeTicket(ticket)
        File ticketRoot = new File(outputRoot, "turns/${facet}")
        if (!ticketRoot.exists()) return []
        List<Map> items = []
        ticketRoot.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
            if (!file.name.endsWith('.md')) return
            String content = file.getText('UTF-8')
            Map metadata = frontMatter(content)
            Instant eventInstant = parseInstant(metadata.timestamp?.toString())
            items << [
                file_path: outputRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/').replaceFirst(/^turns\//, ''),
                file: file,
                content: content,
                event_time: eventInstant?.toString(),
                event_instant: eventInstant,
                timestamp_confidence: metadata.timestamp_confidence ?: 'unknown',
                impact: metadata.impact ?: 'none',
                retrieval_meta: isRetrievalMeta(content),
                explicit_user_ticket: hasExplicitUserTicket(content, ticket),
                material_ticket_evidence: hasMaterialTicketEvidence(content),
                source: metadata.source ?: '',
                sequence: sequenceOf(file.name)
            ]
        }
        items.sort(TicketFreshness.&compareTurnEvidence)
        return items
    }

    static Map frontMatter(String content) {
        Map metadata = [:]
        def matcher = (content ?: '') =~ /(?s)^---\s*\n(.*?)\n---/
        if (!matcher.find()) return metadata
        matcher.group(1).readLines().each { String line ->
            def field = line =~ /^([a-z_]+):\s*(.*)$/
            if (field.matches()) {
                metadata[field.group(1)] = field.group(2).trim().replaceAll(/^"|"$/, '')
            }
        }
        return metadata
    }

    static Instant parseInstant(String value) {
        if (!value) return null
        try {
            return Instant.parse(value)
        } catch (Exception ignored) {
        }
        try {
            String normalized = value.replace(' ', 'T').replaceFirst(/([+-]\d{2})(\d{2})$/, '$1:$2')
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant()
        } catch (Exception ignored) {
            return null
        }
    }

    static int compareTurnEvidence(Map left, Map right) {
        int leftConfidence = confidenceRank(left.timestamp_confidence?.toString())
        int rightConfidence = confidenceRank(right.timestamp_confidence?.toString())
        int byConfidence = rightConfidence <=> leftConfidence
        if (byConfidence != 0) return byConfidence
        Instant leftTime = left.event_instant as Instant
        Instant rightTime = right.event_instant as Instant
        if (leftTime != null || rightTime != null) {
            int byTime = (rightTime ?: Instant.EPOCH) <=> (leftTime ?: Instant.EPOCH)
            if (byTime != 0) return byTime
        }
        int bySequence = ((right.sequence ?: 0L) as long) <=> ((left.sequence ?: 0L) as long)
        if (bySequence != 0) return bySequence
        return (left.file_path ?: '').toString() <=> (right.file_path ?: '').toString()
    }

    static int confidenceRank(String confidence) {
        if (confidence in ['exact', 'local']) return 3
        if (confidence == 'date-only') return 2
        return 1
    }

    static String normalizeTicket(String ticket) {
        return (ticket ?: '').toLowerCase().replaceAll(/[^a-z0-9]/, '')
    }

    static boolean isRetrievalMeta(String content) {
        String body = (content ?: '').replaceFirst(/(?s)^---\s*\n.*?\n---\s*\n/, '')
        def userMatcher = body =~ /(?s)## User\s*(.*?)(?:\n## Assistant|\z)/
        String user = userMatcher.find() ? userMatcher.group(1) : body
        return user ==~ /(?is).*(?:top[- ]?20|snippet|retriev|worklog\s+(?:comparison|versus)|compar(?:e|ison).*worklog|memory.*stale|fix.*stale).*/
    }

    static boolean hasExplicitUserTicket(String content, String ticket) {
        String body = (content ?: '').replaceFirst(/(?s)^---\s*\n.*?\n---\s*\n/, '')
        def userMatcher = body =~ /(?s)## User\s*(.*?)(?:\n## Assistant|\z)/
        String user = userMatcher.find() ? userMatcher.group(1) : ''
        String hyphenated = (ticket ?: '').toUpperCase()
        String compact = normalizeTicket(ticket).toUpperCase()
        if (!hyphenated) return false
        return user.toUpperCase() ==~ /(?s).*(?:${java.util.regex.Pattern.quote(hyphenated)}|${java.util.regex.Pattern.quote(compact)}).*/
    }

    static boolean hasMaterialTicketEvidence(String content) {
        String body = (content ?: '').replaceFirst(/(?s)^---\s*\n.*?\n---\s*\n/, '')
        return body ==~ /(?is).*\b(?:deploy(?:ed|ment|ing)?|delivery|rollout|runtime|merged|build(?:\s*#?\d+)?|image\s+\d+(?:-\d+)?|sync(?:ed|hronized)?|healthy|ready|verified|block(?:ed|er)|pending|credential|secret|pull request|PR\s*#?\d+|implemented|metric)\b.*/
    }

    private static long sequenceOf(String name) {
        def matcher = (name ?: '') =~ /turn-(\d+)\.md$/
        return matcher.find() ? matcher.group(1).toLong() : 0L
    }
}
