import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class WorklogParser {
    private static final String SECTION_RULE = '=' * 80

    static Map parseFile(File source, String sourcePath = source.path, long targetBytes = Config.WORKLOG_CHUNK_TARGET_BYTES) {
        List<String> lines = source.readLines('UTF-8')
        String ticket = extractTicket(source, lines)
        List<Map> sections = splitSections(lines)
        List<Map> chunks = []
        sections.eachWithIndex { Map section, int sectionIndex ->
            splitLargeSection(section, targetBytes).eachWithIndex { Map part, int partIndex ->
                chunks << new LinkedHashMap(part) + [
                    ticket: ticket,
                    source_path: sourcePath,
                    source_order: sectionIndex * 1000 + partIndex,
                    category: classifySection(section.heading?.toString())
                ]
            }
        }
        return [
            source: sourcePath,
            ticket: ticket,
            source_date: extractSourceDate(source.name),
            latest_source_time: extractLatestRecordedTime(lines, source).toString(),
            structured: ticket != null && sections.size() > 1,
            chunks: chunks,
            line_count: lines.size()
        ]
    }

    static List<Map> splitSections(List<String> lines) {
        List<Integer> starts = []
        for (int index = 0; index + 2 < lines.size(); index++) {
            if (lines[index] == SECTION_RULE && lines[index + 2] == SECTION_RULE && lines[index + 1].trim()) {
                starts << index
            }
        }
        List<Map> sections = []
        int firstStart = starts ? starts[0] : lines.size()
        List<String> preamble = lines.subList(0, firstStart).findAll { it != SECTION_RULE }
        if (preamble.any { it.trim() }) {
            sections << [heading: 'TICKET SUMMARY', start_line: 1, end_line: Math.max(1, firstStart), lines: preamble]
        }
        starts.eachWithIndex { int start, int position ->
            int end = position + 1 < starts.size() ? starts[position + 1] - 1 : lines.size() - 1
            List<String> body = start + 3 <= end ? new ArrayList<>(lines.subList(start + 3, end + 1)) : []
            while (body && body[-1] == SECTION_RULE) body.remove(body.size() - 1)
            sections << [
                heading: lines[start + 1].trim(),
                start_line: start + 1,
                end_line: end + 1,
                lines: body
            ]
        }
        if (!sections && lines.any { it.trim() }) {
            sections << [heading: 'EVIDENCE', start_line: 1, end_line: lines.size(), lines: lines.findAll { it != SECTION_RULE }]
        }
        return sections
    }

    static String classifySection(String heading) {
        String value = (heading ?: '').toUpperCase()
        if (value =~ /RELATED TICKET/) return 'related-ticket'
        if (value =~ /DELIVERY|COMPLETED DELIVERY|VERDICT/) return 'delivery'
        if (value =~ /NEXT ACTION/) return 'next-action'
        if (value =~ /BLOCKER/) return 'blockers'
        if (value =~ /MANUAL ACTION/) return 'manual-actions'
        if (value =~ /ACTION LOG/) return 'action-log'
        if (value =~ /STATUS|CURRENT STATE/) return 'status'
        if (value =~ /IMPLEMENTATION PLAN|STAGE PLAN/) return 'plan'
        if (value =~ /CHECKLIST/) return 'checklist'
        if (value =~ /DECISION|RECOMMENDATION/) return 'decisions'
        if (value =~ /FINDING|ANALYSIS/) return 'findings'
        if (value =~ /SOLUTION|OPTION/) return 'solutions'
        if (value =~ /RISK/) return 'risks'
        if (value =~ /PROPOSED ACTION|ACTIONS/) return 'actions'
        if (value =~ /REPOSITOR|REFERENCE/) return 'repositories'
        if (value =~ /COMMENT/) return 'comments'
        if (value =~ /TIME/) return 'time'
        if (value =~ /SUMMARY|TICKET|WORKLOG/) return 'summary'
        return 'other'
    }

    static List<Map> splitLargeSection(Map section, long targetBytes = Config.WORKLOG_CHUNK_TARGET_BYTES) {
        List<String> lines = section.lines as List<String>
        if (lines.join('\n').getBytes(StandardCharsets.UTF_8).length <= targetBytes) return [section]
        List<Map> parts = []
        List<String> current = []
        int currentStart = section.start_line as int
        lines.eachWithIndex { String line, int index ->
            boolean boundary = line ==~ /^(?:\d+\.|F\d+\s+-|FINDING\s+\d+|OPTION\s+[A-Z0-9]+|20\d{2}-\d{2}-\d{2}.*).*/
            int bytes = (current + line).join('\n').getBytes(StandardCharsets.UTF_8).length
            if (current && boundary && bytes > targetBytes) {
                parts << [heading: section.heading, start_line: currentStart, end_line: (section.start_line as int) + index - 1, lines: current]
                current = []
                currentStart = (section.start_line as int) + index
            }
            current << line
        }
        if (current) {
            parts << [heading: section.heading, start_line: currentStart, end_line: section.end_line, lines: current]
        }
        return parts
    }

    static String extractTicket(File source, List<String> lines = null) {
        List<String> fileKeys = TicketKey.extract(source.name)
        if (fileKeys) return fileKeys[0]
        List<String> contentKeys = TicketKey.extract((lines ?: source.readLines('UTF-8')).take(80).join('\n'))
        return contentKeys ? contentKeys[0] : null
    }

    static String extractSourceDate(String name) {
        def matcher = name =~ /^(\d{4}-\d{2}-\d{2})/
        return matcher.find() ? matcher.group(1) : LocalDate.ofEpochDay(0).toString()
    }

    static Instant extractLatestRecordedTime(List<String> lines, File source) {
        List<Instant> timestamps = []
        (lines ?: []).each { String line ->
            boolean trustworthy = line ==~ /^\s*\d{4}-\d{2}-\d{2}.*/ ||
                line ==~ /(?i).*\b(?:UPDATED|CURRENT|STATUS|ACTION|DELIVERY|VERIFIED|COMPLETED|RESOLVED|BLOCKER|EXECUTED|MERGED|DEPLOYED)\b.*/
            if (!trustworthy) return
            def matcher = line =~ /\b\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:Z|[+-]\d{2}:?\d{2})?)?\b/
            while (matcher.find()) {
                Instant parsed = parseSourceTime(matcher.group())
                if (parsed) timestamps << parsed
            }
        }
        return timestamps ? timestamps.max() : Instant.ofEpochMilli(source.lastModified())
    }

    private static Instant parseSourceTime(String value) {
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().minusNanos(1)
            }
            String normalized = value.replace(' ', 'T').replaceFirst(/([+-]\d{2})(\d{2})$/, '$1:$2')
            if (normalized.endsWith('Z') || normalized ==~ /.*[+-]\d{2}:\d{2}$/) {
                return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            }
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        } catch (Exception ignored) {
            return null
        }
    }
}
