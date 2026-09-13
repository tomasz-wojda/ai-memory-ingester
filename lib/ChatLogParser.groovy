import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Parses the observed prompt-log dialects into ordered conversation entries.
 */
class ChatLogParser {

    private static final Pattern EQUALS_SEPARATOR = ~/^={40,}\s*$/
    private static final Pattern DATE_BANNER = ~/^(?:={3,}|-{3,})\s*\d{4}-\d{2}-\d{2}.*(?:={3,}|-{3,})\s*$/
    private static final Pattern FENCE_OPEN = ~/^--- (?:START )?PROMPT LOG ENTRY(?: (?!END).*?)?---\s*$/
    private static final Pattern FENCE_CLOSE = ~/^--- END PROMPT LOG ENTRY ---\s*$/
    private static final Pattern FIELD = ~/(?i)^([A-Z][A-Z0-9 _\/&().-]{1,60}):\s*(.*)$/
    private static final Set<String> STRUCTURAL_LABELS = [
        'TIMESTAMP', 'DATE', 'USER', 'ASSISTANT', 'USER PROMPT', 'USER PROMPTS',
        'ASSISTANT OUTPUT', 'ASSISTANT RESPONSE', 'PROMPT', 'RESPONSE',
        'RESPONSE SUMMARY', 'INPUT', 'OUTPUT', 'USER QUERY', 'USER CAUGHT ERROR', 'MODE'
    ] as Set<String>

    /**
     * Parses a source file from an optional byte offset.
     *
     * @param source Source log file
     * @param sourcePath Workspace-relative source path
     * @param startOffset Byte offset from which parsing begins
     * @return Ordered parsed entry maps
     */
    static List<Map> parseFile(File source, String sourcePath = null, long startOffset = 0L) {
        byte[] bytes = source.bytes
        int safeOffset = (int) Math.max(0L, Math.min(startOffset, bytes.length as long))
        String prefix = new String(bytes, 0, safeOffset, StandardCharsets.UTF_8)
        String text = new String(bytes, safeOffset, bytes.length - safeOffset, StandardCharsets.UTF_8)
        int initialLine = prefix.count('\n') + 1
        String relative = sourcePath ?: source.path

        List<Map> regions = splitRegions(text, safeOffset, initialLine)
        regions = resolveFenceImbalance(regions)
        List<Map> entries = []
        regions.each { Map region ->
            Map entry = parseRegion(region, relative)
            if (entry != null) entries << entry
        }
        return entries
    }

    /**
     * Splits source text on separators, fences, timestamp starts, and bracketed role pairs.
     *
     * @param text Source text
     * @param baseOffset Initial byte offset
     * @param initialLine Initial source line
     * @return Raw ordered regions
     */
    static List<Map> splitRegions(String text, long baseOffset = 0L, int initialLine = 1) {
        List<Map> regions = []
        List<String> current = []
        long regionOffset = baseOffset
        long runningOffset = baseOffset
        int regionLine = initialLine
        boolean fenced = false

        Closure flush = { int endLine ->
            if (!current.isEmpty() && current.any { it.trim() }) {
                regions << [
                    text: current.join('\n').trim(),
                    byteOffset: regionOffset,
                    startLine: regionLine,
                    endLine: Math.max(regionLine, endLine),
                    fenced: fenced
                ]
            }
            current = []
            fenced = false
        }

        List<String> lines = text.split('\n', -1) as List<String>
        lines.eachWithIndex { String line, int index ->
            int currentLine = initialLine + index
            long lineOffset = runningOffset
            boolean separator = EQUALS_SEPARATOR.matcher(line).matches()
            boolean opener = FENCE_OPEN.matcher(line).matches()
            boolean closer = FENCE_CLOSE.matcher(line).matches()
            boolean dateBanner = isDateBanner(line)
            boolean bracketStart = isBracketTimestamp(line)
            boolean bareDateHeader = isBareDateHeader(line)
            boolean promptOutputHeader = isPromptOutputHeader(line)
            boolean timestampField = line ==~ /(?i)^(?:TIMESTAMP|DATE):\s*.+$/
            Map role = roleForLine(line)

            if (opener) {
                flush.call(currentLine - 1)
                regionOffset = lineOffset
                regionLine = currentLine
                fenced = true
                current << line
            } else if (closer) {
                if (current.isEmpty()) {
                    regionOffset = lineOffset
                    regionLine = currentLine
                }
                current << line
                flush.call(currentLine)
            } else if (dateBanner) {
                flush.call(currentLine - 1)
                regionOffset = lineOffset
                regionLine = currentLine
                current << line
            } else if (bracketStart && role?.side != 'assistant') {
                flush.call(currentLine - 1)
                regionOffset = lineOffset
                regionLine = currentLine
                current << line
            } else if (promptOutputHeader) {
                if (current.isEmpty()) {
                    regionOffset = lineOffset
                    regionLine = currentLine
                }
                current << line
            } else if (bareDateHeader) {
                flush.call(currentLine - 1)
                regionOffset = lineOffset
                regionLine = currentLine
                current << line
            } else if (timestampField && !current.isEmpty() && !isPendingFenceHeader(current)) {
                flush.call(currentLine - 1)
                regionOffset = lineOffset
                regionLine = currentLine
                current << line
            } else if (separator) {
                String nextLine = nextNonBlank(lines, index + 1)
                String previousLine = previousNonBlank(current)
                if (containsRoleContent(current) &&
                    !isPromptOutputHeader(nextLine) &&
                    !isPromptInputHeader(previousLine) &&
                    !isPromptOutputHeader(previousLine)) {
                    flush.call(currentLine - 1)
                }
            } else if (role?.side == 'user' && containsAssistantRole(current)) {
                flush.call(currentLine - 1)
                regionOffset = lineOffset
                regionLine = currentLine
                current << line
            } else {
                if (current.isEmpty()) {
                    regionOffset = lineOffset
                    regionLine = currentLine
                }
                current << line
            }

            runningOffset += line.getBytes(StandardCharsets.UTF_8).length + 1L
        }
        flush.call(initialLine + lines.size() - 1)
        return regions
    }

    /**
     * Applies deterministic recovery markers to unbalanced fenced regions.
     *
     * @param regions Raw regions
     * @return Regions with fence balance metadata
     */
    static List<Map> resolveFenceImbalance(List<Map> regions) {
        regions.each { Map region ->
            String text = region.text?.toString() ?: ''
            int opens = text.readLines().count { FENCE_OPEN.matcher(it).matches() }
            int closes = text.readLines().count { FENCE_CLOSE.matcher(it).matches() }
            region.fenceImbalance = opens > closes ? 'unmatched_opener' : (closes > opens ? 'unmatched_closer' : null)
        }
        return regions
    }

    /**
     * Classifies a raw region using the approved six-dialect precedence.
     *
     * @param region Raw region text or map
     * @return Dialect name
     */
    static String classifyRegion(Object region) {
        String text = region instanceof Map ? region.text?.toString() : region?.toString()
        if (!text) return 'Artifact'
        Map<String, List<String>> fields = collectFields(text)
        boolean hasUser = fields['USER']?.any { it?.trim() }
        boolean fenced = text.readLines().any { FENCE_OPEN.matcher(it).matches() }
        boolean labelled = text ==~ /(?ims).*(?:^|\n)\s*(?:USER PROMPTS?|INPUT):.*/ ||
            text ==~ /(?ims).*(?:^|\n)\s*(?:ASSISTANT OUTPUT|OUTPUT):.*/
        boolean blockHeader = text.readLines().any {
            isDateBanner(it) || isBareDateHeader(it) || it ==~ /(?i)^(?:TIMESTAMP|DATE):\s*.+$/
        }
        boolean ticket = text ==~ /(?s)^\[[^\]]+\]\s+[A-Z][A-Z0-9]+-\d+.*$/ &&
            fields['USER'] && fields['ASSISTANT']
        boolean pair = isBracketTimestamp(text.readLines().find { it.trim() } ?: '') && hasUser
        if (fenced && hasUser) return 'Fenced'
        if (labelled && hasUser) return 'Labelled'
        if (blockHeader && hasUser) return 'Block'
        if (ticket) return 'Ticket'
        if (pair) return 'Pair'
        if (hasUser) return 'Block'
        return 'Artifact'
    }

    /**
     * Extracts non-structural uppercase labelled sections in encounter order.
     *
     * @param text Region text
     * @return Ordered section map
     */
    static Map<String, String> extractLabelledSections(String text) {
        LinkedHashMap<String, List<String>> collected = new LinkedHashMap<>()
        String active = null
        text?.readLines()?.each { String line ->
            if (roleForLine(line) != null || isPromptInputHeader(line) || isPromptOutputHeader(line)) {
                active = null
                return
            }
            def matcher = FIELD.matcher(line.trim())
            if (matcher.matches()) {
                String label = matcher.group(1).trim().toUpperCase(Locale.ROOT)
                active = STRUCTURAL_LABELS.contains(label) ? null : label
                if (active != null) {
                    collected.computeIfAbsent(active) { [] }
                    if (matcher.group(2)) collected[active] << matcher.group(2)
                }
            } else if (active != null) {
                collected[active] << line
            }
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>()
        collected.each { String label, List<String> lines ->
            String value = trimBlankLines(lines).join('\n').trim()
            result[label] = value
        }
        return result
    }

    /**
     * Extracts unique ticket keys in source order.
     *
     * @param text Source text
     * @return Ticket keys
     */
    static List<String> extractTicketKeys(String text) {
        List<String> keys = []
        def matcher = (text ?: '') =~ /\b([A-Z][A-Z0-9]{1,9}-\d{1,8})\b/
        matcher.each { match ->
            String key = match[1].toString().toUpperCase()
            if (!keys.contains(key)) keys << key
        }
        return keys
    }

    private static Map parseRegion(Map region, String sourcePath) {
        String text = region.text?.toString()?.trim()
        if (!text) return null
        String dialect = classifyRegion(region)
        Map<String, List<String>> fields = collectFields(text)
        String headerTimestamp = bracketTimestamp(text) ?: dateHeaderTimestamp(text)
        String rawTimestamp = first(fields['TIMESTAMP']) ?: first(fields['DATE']) ?: headerTimestamp
        String rawMode = first(fields['MODE']) ?: modeFromText(text)
        String user = join(fields['USER'])
        String assistant = join(fields['ASSISTANT'])
        Map<String, String> sections = extractLabelledSections(text)
        List<String> tickets = extractTicketKeys(([user, assistant, sections.values().join('\n')].findAll().join('\n')))

        if (dialect == 'Artifact') {
            user = ''
            assistant = text
        }
        if (!user && !assistant) return null

        return [
            sourcePath: sourcePath,
            byteOffset: region.byteOffset as long,
            startLine: region.startLine as int,
            endLine: region.endLine as int,
            dialect: dialect,
            rawTimestamp: rawTimestamp,
            rawMode: rawMode,
            user: user ?: '',
            assistant: assistant ?: '',
            sections: sections,
            ticketKeys: tickets,
            rawBody: text,
            fenceImbalance: region.fenceImbalance
        ]
    }

    private static Map<String, List<String>> collectFields(String text) {
        LinkedHashMap<String, List<String>> fields = new LinkedHashMap<>()
        String active = null
        text.readLines().each { String line ->
            Map role = roleForLine(line)
            if (role != null) {
                active = role.side == 'user' ? 'USER' : 'ASSISTANT'
                fields.computeIfAbsent(active) { [] }
                if (role.content) fields[active] << role.content
                return
            }
            if (isPromptInputHeader(line)) {
                active = 'USER'
                fields.computeIfAbsent(active) { [] }
                return
            }
            if (isPromptOutputHeader(line)) {
                active = 'ASSISTANT'
                fields.computeIfAbsent(active) { [] }
                return
            }
            def matcher = FIELD.matcher(line.trim())
            if (matcher.matches()) {
                active = matcher.group(1).trim().toUpperCase(Locale.ROOT)
                fields.computeIfAbsent(active) { [] }
                if (matcher.group(2)) fields[active] << matcher.group(2)
            } else if (active != null && !FENCE_OPEN.matcher(line).matches() && !FENCE_CLOSE.matcher(line).matches()) {
                fields[active] << line
            }
        }
        return fields
    }

    private static String bracketTimestamp(String text) {
        def matcher = text =~ /(?m)^\[([^\]]+)\]/
        return matcher.find() ? matcher.group(1).trim() : null
    }

    private static String dateHeaderTimestamp(String text) {
        String line = text.readLines().find { isDateBanner(it) || isBareDateHeader(it) || FENCE_OPEN.matcher(it).matches() }
        if (!line) return null
        def dateMatcher = line =~ /(\d{4}-\d{2}-\d{2}(?:[T ][0-9]{1,2}(?::[0-9$(){}:+-]{2,})?(?:\s*(?:Z|[+-]\d{2}:?\d{2}|[A-Z]{2,5}))?)?)/
        return dateMatcher.find() ? dateMatcher.group(1).trim() : null
    }

    private static boolean isDateBanner(String line) {
        String value = line?.trim() ?: ''
        return value ==~ /^={3,}\s*\d{4}-\d{2}-\d{2}.*={3,}$/ ||
            value ==~ /^-{3,}\s*\d{4}-\d{2}-\d{2}.*-{3,}$/
    }

    private static boolean isBracketTimestamp(String line) {
        return (line?.trim() ?: '') ==~ /^\[[^\]]*\d{4}[^\]]*\].*$/
    }

    private static boolean isBareDateHeader(String line) {
        String value = line?.trim() ?: ''
        return value ==~ /(?i)^\d{4}-\d{2}-\d{2}(?:T|\s).*(?:\([^)]*\)|\[\s*MODE:|-\s*(?:MODE|PROMPT INPUT|PROMPT OUTPUT)).*$/
    }

    private static boolean isPromptInputHeader(String line) {
        return (line?.trim() ?: '') ==~ /(?i)^\d{4}-\d{2}-\d{2}(?:T|\s).*\bPROMPT INPUT\b.*$/
    }

    private static boolean isPromptOutputHeader(String line) {
        return (line?.trim() ?: '') ==~ /(?i)^\d{4}-\d{2}-\d{2}(?:T|\s).*\bPROMPT OUTPUT\b.*$/
    }

    private static Map roleForLine(String line) {
        String value = line?.trim() ?: ''
        if (!value) return null
        if (isBracketTimestamp(value)) {
            value = value.replaceFirst(/^\[[^\]]+\]\s*/, '')
        }
        if (isPromptInputHeader(value)) return [side: 'user', content: '']
        if (isPromptOutputHeader(value)) return [side: 'assistant', content: '']

        def user = value =~ /(?i)^(USER(?:\s+PROMPTS?)?(?:\s+\d+)?|PROMPT|INPUT|USER QUERY|USER CAUGHT ERROR)(?:\s*\([^)]*\))?(?:\s*-\s*[^:]+)?\s*:\s*(.*)$/
        if (user.matches()) return [side: 'user', content: user.group(2)]

        def assistant = value =~ /(?i)^(ASSISTANT(?:\s+(?:OUTPUT|RESPONSE))?|RESPONSE(?:\s+SUMMARY)?|OUTPUT)(?:\s*\([^)]*\))?(?:\s*-\s*[^:]+)?\s*:\s*(.*)$/
        if (assistant.matches()) return [side: 'assistant', content: assistant.group(2)]
        return null
    }

    private static boolean containsUserRole(List<String> lines) {
        return lines.any { String line -> roleForLine(line)?.side == 'user' || isPromptInputHeader(line) }
    }

    private static boolean containsAssistantRole(List<String> lines) {
        return lines.any { String line -> roleForLine(line)?.side == 'assistant' || isPromptOutputHeader(line) }
    }

    private static boolean containsRoleContent(List<String> lines) {
        boolean activeRole = false
        for (String line : lines) {
            Map role = roleForLine(line)
            if (role != null) {
                activeRole = true
                if (role.content?.toString()?.trim()) return true
            } else if (activeRole && line?.trim() &&
                !FENCE_OPEN.matcher(line).matches() &&
                !FENCE_CLOSE.matcher(line).matches() &&
                !isDateBanner(line) &&
                !(line ==~ /(?i)^(?:TIMESTAMP|DATE|MODE):\s*.*$/)) {
                return true
            }
        }
        return false
    }

    private static boolean isPendingFenceHeader(List<String> lines) {
        return lines.any { FENCE_OPEN.matcher(it).matches() } &&
            !containsUserRole(lines) && !containsAssistantRole(lines)
    }

    private static String nextNonBlank(List<String> lines, int startIndex) {
        for (int index = startIndex; index < lines.size(); index++) {
            if (lines[index]?.trim()) return lines[index]
        }
        return null
    }

    private static String previousNonBlank(List<String> lines) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            if (lines[index]?.trim()) return lines[index]
        }
        return null
    }

    private static String modeFromText(String text) {
        def matcher = text =~ /(?im)\b(RESEARCH|INNOVATE|PLAN|EXECUTE)\s+MODE\b|\bMODE:\s*(RESEARCH|INNOVATE|PLAN|EXECUTE)\b/
        if (!matcher.find()) return null
        return (matcher.group(1) ?: matcher.group(2))?.toUpperCase()
    }

    private static String first(List<String> values) {
        return values?.find { it?.trim() }?.trim()
    }

    private static String join(List<String> values) {
        return trimBlankLines(values ?: []).join('\n').trim()
    }

    private static String firstNonEmpty(List<String> values) {
        return values.find { it?.trim() }?.trim()
    }

    private static List<String> trimBlankLines(List<String> lines) {
        List<String> copy = new ArrayList<>(lines ?: [])
        while (copy && !copy.first().trim()) copy.remove(0)
        while (copy && !copy.last().trim()) copy.remove(copy.size() - 1)
        return copy
    }
}
