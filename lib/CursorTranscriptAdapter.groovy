import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.regex.Pattern

class CursorTranscriptAdapter {

    static List<File> discoverTranscriptFiles(File workspaceRoot, File homeDir = defaultHome()) {
        List<File> roots = discoverRoots(workspaceRoot, homeDir)
        List<File> files = []
        roots.each { File root ->
            if (!root.exists()) return
            root.eachFileRecurse(groovy.io.FileType.FILES) { File candidate ->
                if (candidate.name.endsWith('.jsonl') && candidate.parentFile?.name == candidate.name.replace('.jsonl', '')) {
                    files << candidate.canonicalFile
                }
            }
        }
        return files.unique { it.canonicalPath }.sort { it.canonicalPath }
    }

    static List<File> discoverRoots(File workspaceRoot, File homeDir = defaultHome()) {
        LinkedHashSet<File> roots = [] as LinkedHashSet<File>
        File cursorProjects = new File(homeDir, '.cursor/projects')
        if (cursorProjects.exists()) {
            File projectDir = new File(cursorProjects, workspaceSlug(workspaceRoot, false))
            File transcripts = new File(projectDir, 'agent-transcripts')
            if (transcripts.exists()) roots << transcripts
        }
        File workspaceCursor = new File(workspaceRoot, '.cursor/projects')
        if (workspaceCursor.exists()) {
            File projectDir = new File(workspaceCursor, workspaceSlug(workspaceRoot, false))
            File transcripts = new File(projectDir, 'agent-transcripts')
            if (transcripts.exists()) roots << transcripts
        }
        return roots.toList().sort { it.canonicalPath }
    }

    static Map parseFile(File file, long startByteOffset = 0L) {
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Cursor transcript not found: ${file}")
        }
        byte[] bytes = file.bytes
        String sourceIdentity = sourceIdentityFor(file)
        if (startByteOffset >= bytes.length) {
            return emptyResult(sourceIdentity, file, bytes.length, Config.CURSOR_TRANSCRIPT_FORMAT_V1)
        }

        String text = new String(bytes, startByteOffset as int, bytes.length - (startByteOffset as int), StandardCharsets.UTF_8)
        JsonSlurper slurper = new JsonSlurper()
        List<Map> turns = []
        List<Map> errors = []
        List<Map> unknownLines = []
        long lineStartByte = startByteOffset
        int lineNumber = countLines(bytes, startByteOffset) + 1
        String pendingUser = null
        Map pendingMeta = [:]
        StringBuilder assistantParts = new StringBuilder()
        Map assistantMeta = [:]
        long pendingStartByte = -1L

        text.eachLine { String line ->
            long lineEndByte = lineStartByte + line.getBytes(StandardCharsets.UTF_8).length
            if (line.trim()) {
                try {
                    Object parsed = slurper.parseText(line)
                    if (!(parsed instanceof Map)) {
                        unknownLines << [byte_start: lineStartByte, byte_end: lineEndByte, line: lineNumber, reason: 'non_object_json']
                        lineStartByte = lineEndByte + 1
                        lineNumber++
                        return
                    }
                    Map record = parsed as Map
                    if (!isSupportedRecord(record)) {
                        unknownLines << [byte_start: lineStartByte, byte_end: lineEndByte, line: lineNumber, reason: unsupportedReason(record)]
                        lineStartByte = lineEndByte + 1
                        lineNumber++
                        return
                    }
                    String role = record.role?.toString()
                    if (role == 'user') {
                        flushTurn(turns, pendingUser, assistantParts.toString(), pendingMeta, assistantMeta, sourceIdentity, file, lineNumber)
                        pendingUser = extractUserText(record)
                        pendingMeta = metaForRecord(record, sourceIdentity, file, lineStartByte, lineNumber)
                        pendingStartByte = lineStartByte
                        assistantParts = new StringBuilder()
                        assistantMeta = [:]
                    } else if (role == 'assistant') {
                        String chunk = extractAssistantText(record)
                        if (chunk) {
                            if (assistantParts.length() > 0) assistantParts.append('\n')
                            assistantParts.append(chunk)
                        }
                        if (!assistantMeta && record.timestamp) assistantMeta = [event_time: record.timestamp]
                    }
                } catch (Exception ex) {
                    errors << [byte_start: lineStartByte, byte_end: lineEndByte, line: lineNumber, reason: ex.message?.toString()]
                }
            }
            lineStartByte = lineEndByte + 1
            lineNumber++
        }
        long safeByteOffset = bytes.length as long
        if (pendingUser && assistantParts.length() == 0) {
            safeByteOffset = pendingStartByte >= 0L ? pendingStartByte : startByteOffset
        } else {
            flushTurn(turns, pendingUser, assistantParts.toString(), pendingMeta, assistantMeta, sourceIdentity, file, lineNumber - 1)
        }

        if (unknownLines.any { it.reason?.toString()?.startsWith('unknown_format') }) {
            throw new IllegalStateException("Unknown Cursor transcript format in ${file}: ${unknownLines.findAll { it.reason?.toString()?.startsWith('unknown_format') }.take(3)}")
        }

        return [
            format_version: Config.CURSOR_TRANSCRIPT_FORMAT_V1,
            source_identity: sourceIdentity,
            source_path: file.absolutePath,
            source_ide: 'cursor',
            byte_offset_end: safeByteOffset,
            turns: turns,
            errors: errors,
            unknown_lines: unknownLines
        ]
    }

    static String sourceIdentityFor(File file) {
        File canonical = file.canonicalFile
        File sessionDir = canonical.parentFile
        String sessionId = sessionDir?.name ?: canonical.name.replace('.jsonl', '')
        String projectSlug = sessionDir?.parentFile?.parentFile?.name ?: 'unknown-project'
        return "cursor-transcript:${projectSlug}/${sessionId}".replace('\\', '/')
    }

    private static File defaultHome() {
        String home = System.getProperty('user.home')
        return home ? new File(home).canonicalFile : new File('.').canonicalFile
    }

    static String workspaceSlug(File workspaceRoot, boolean keepLeadingSeparator) {
        String path = workspaceRoot.canonicalPath.replace('\\', '/')
        String normalized = keepLeadingSeparator ? path : path.replaceFirst('^/+', '')
        return normalized.replaceAll('[^A-Za-z0-9]+', '-')
    }

    private static boolean isSupportedRecord(Map record) {
        return record.containsKey('role') && record.message instanceof Map
    }

    private static String unsupportedReason(Map record) {
        if (record.containsKey('role') && record.message instanceof Map) return null
        if (record.type || record.operation) return 'ignored_control_record'
        return "unknown_format:${record.keySet().take(3).join(',')}"
    }

    private static String extractUserText(Map record) {
        Map message = record.message as Map
        List content = message.content instanceof List ? message.content as List : []
        String text = content.collect { item ->
            if (item instanceof Map && item.type?.toString() == 'text') return item.text?.toString() ?: ''
            return ''
        }.findAll { it }.join('\n')
        return normalizeUserText(text)
    }

    private static String extractAssistantText(Map record) {
        Map message = record.message as Map
        List content = message.content instanceof List ? message.content as List : []
        return content.collect { item ->
            if (item instanceof Map && item.type?.toString() == 'text') return item.text?.toString() ?: ''
            return ''
        }.findAll { it }.join('\n').trim()
    }

    private static String normalizeUserText(String text) {
        if (!text) return ''
        String cleaned = text
            .replaceFirst(/(?s)^\s*<timestamp>.*?<\/timestamp>\s*/, '')
            .replaceFirst(/(?s)^\s*<user_query>\s*/, '')
            .replaceFirst(/(?s)\s*<\/user_query>\s*$/, '')
            .trim()
        return cleaned
    }

    private static Map metaForRecord(Map record, String sourceIdentity, File file, long byteOffset, int lineNumber) {
        String timestamp = extractTimestamp(record)
        String sessionId = record.sessionId?.toString() ?: sourceIdentity.tokenize('/').last()
        return [
            session_id: sessionId,
            prompt_id: record.promptId?.toString(),
            event_time: timestamp,
            event_time_confidence: timestamp ? 'exact' : 'lost',
            source_byte_offset: byteOffset,
            source_start_line: lineNumber,
            source_end_line: lineNumber,
            source_identity: sourceIdentity,
            source_path: file.absolutePath
        ]
    }

    private static String extractTimestamp(Map record) {
        Map message = record.message as Map
        List content = message.content instanceof List ? message.content as List : []
        String text = content.collect { it instanceof Map ? it.text?.toString() : '' }.find { it } ?: ''
        def matcher = text =~ /<timestamp>(.*?)<\/timestamp>/
        if (matcher.find()) return matcher.group(1)?.trim()
        return record.timestamp?.toString()
    }

    private static void flushTurn(
        List<Map> turns,
        String userText,
        String assistantText,
        Map userMeta,
        Map assistantMeta,
        String sourceIdentity,
        File file,
        int endLine
    ) {
        if (!userText && !assistantText) return
        Map meta = new LinkedHashMap(userMeta ?: [:])
        if (endLine > 0) meta.source_end_line = endLine
        String mode = detectMode(userText, assistantText)
        List<String> tickets = TicketKey.extract("${userText}\n${assistantText}")
        String rawPayload = [
            userText ? "USER:\n${userText}" : '',
            assistantText ? "ASSISTANT:\n${assistantText}" : ''
        ].findAll { it }.join('\n\n')
        turns << [
            user_text: userText ?: '',
            assistant_text: assistantText ?: '',
            outcome_summary: assistantText?.take(4000),
            mode: mode,
            tickets: tickets,
            session_id: meta.session_id,
            prompt_id: meta.prompt_id,
            event_time: meta.event_time ?: assistantMeta.event_time,
            event_time_confidence: meta.event_time_confidence ?: 'lost',
            source_byte_offset: meta.source_byte_offset,
            source_start_line: meta.source_start_line,
            source_end_line: meta.source_end_line,
            source_identity: sourceIdentity,
            source_path: file.absolutePath,
            raw_payload: rawPayload,
            logical_turn_key: meta.prompt_id ?: "${sourceIdentity}#${meta.source_start_line}-${meta.source_end_line}"
        ]
    }

    private static String detectMode(String userText, String assistantText) {
        String haystack = "${userText}\n${assistantText}"
        def matcher = haystack =~ /(?im)\*\*\[MODE:\s*([A-Z]+)\]\*\*|^MODE:\s*([A-Z]+)/
        if (matcher.find()) {
            String mode = (matcher.group(1) ?: matcher.group(2))?.toLowerCase(Locale.ROOT)
            if (FacetPath.modeFacet(mode) != 'nomode') return FacetPath.modeFacet(mode)
        }
        return null
    }

    private static Map emptyResult(String sourceIdentity, File file, long byteEnd, String formatVersion) {
        return [
            format_version: formatVersion,
            source_identity: sourceIdentity,
            source_path: file.absolutePath,
            source_ide: 'cursor',
            byte_offset_end: byteEnd,
            turns: [],
            errors: [],
            unknown_lines: []
        ]
    }

    private static int countLines(byte[] bytes, long offset) {
        int count = 0
        for (long index = 0L; index < offset && index < bytes.length; index++) {
            if (bytes[(int) index] == ((byte) '\n')) count++
        }
        return count
    }
}
