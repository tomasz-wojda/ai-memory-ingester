import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets

class ClaudeTranscriptAdapter {

    static List<File> discoverTranscriptFiles(File workspaceRoot, File homeDir = defaultHome()) {
        List<File> roots = discoverRoots(workspaceRoot, homeDir)
        List<File> files = []
        roots.each { File root ->
            if (!root.exists()) return
            root.eachFile(groovy.io.FileType.FILES) { File candidate ->
                if (candidate.name.endsWith('.jsonl') && !candidate.name.contains('orphaned')) {
                    files << candidate.canonicalFile
                }
            }
            File subagents = new File(root, 'subagents')
            if (subagents.exists()) {
                subagents.eachFileRecurse(groovy.io.FileType.FILES) { File candidate ->
                    if (candidate.name.endsWith('.jsonl')) files << candidate.canonicalFile
                }
            }
        }
        return files.unique { it.canonicalPath }.sort { it.canonicalPath }
    }

    static List<File> discoverRoots(File workspaceRoot, File homeDir = defaultHome()) {
        LinkedHashSet<File> roots = [] as LinkedHashSet<File>
        File claudeProjects = new File(homeDir, '.claude/projects')
        if (claudeProjects.exists()) {
            File projectDir = new File(claudeProjects, workspaceSlug(workspaceRoot))
            if (projectDir.exists()) roots << projectDir
        }
        return roots.toList().sort { it.canonicalPath }
    }

    static Map parseFile(File file, long startByteOffset = 0L) {
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Claude transcript not found: ${file}")
        }
        byte[] bytes = file.bytes
        String sourceIdentity = sourceIdentityFor(file)
        if (startByteOffset >= bytes.length) {
            return emptyResult(sourceIdentity, file, bytes.length)
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
        String detectedVersion = null
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
                    String recordType = record.type?.toString()
                    if (record.version) detectedVersion = record.version.toString()
                    if (isIgnorableRecord(record)) {
                        lineStartByte = lineEndByte + 1
                        lineNumber++
                        return
                    }
                    if (!isSupportedRecord(record)) {
                        unknownLines << [byte_start: lineStartByte, byte_end: lineEndByte, line: lineNumber, reason: unsupportedReason(record)]
                        lineStartByte = lineEndByte + 1
                        lineNumber++
                        return
                    }
                    if (recordType == 'user') {
                        flushTurn(turns, pendingUser, assistantParts.toString(), pendingMeta, assistantMeta, sourceIdentity, file, lineNumber)
                        pendingUser = extractUserText(record)
                        pendingMeta = metaForRecord(record, sourceIdentity, file, lineStartByte, lineNumber)
                        pendingStartByte = lineStartByte
                        assistantParts = new StringBuilder()
                        assistantMeta = [:]
                    } else if (recordType == 'assistant') {
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
            throw new IllegalStateException("Unknown Claude transcript format in ${file}: ${unknownLines.findAll { it.reason?.toString()?.startsWith('unknown_format') }.take(3)}")
        }

        return [
            format_version: Config.CLAUDE_TRANSCRIPT_FORMAT_V2,
            transcript_version: detectedVersion,
            source_identity: sourceIdentity,
            source_path: file.absolutePath,
            source_ide: 'claude',
            byte_offset_end: safeByteOffset,
            turns: turns,
            errors: errors,
            unknown_lines: unknownLines
        ]
    }

    static String sourceIdentityFor(File file) {
        File canonical = file.canonicalFile
        String sessionId = canonical.name.replace('.jsonl', '')
        String projectSlug = canonical.parentFile?.name ?: 'unknown-project'
        if (canonical.path.contains('/subagents/')) {
            String parentSession = canonical.parentFile?.parentFile?.name ?: sessionId
            return "claude-transcript:${projectSlug}/${parentSession}/subagent/${sessionId}".replace('\\', '/')
        }
        return "claude-transcript:${projectSlug}/${sessionId}".replace('\\', '/')
    }

    private static File defaultHome() {
        String home = System.getProperty('user.home')
        return home ? new File(home).canonicalFile : new File('.').canonicalFile
    }

    static String workspaceSlug(File workspaceRoot) {
        return workspaceRoot.canonicalPath.replace('\\', '/').replaceAll('[^A-Za-z0-9]+', '-')
    }

    private static boolean isIgnorableRecord(Map record) {
        String type = record.type?.toString()
        return type in [
            'attachment', 'queue-operation', 'atis-latch', 'last-prompt', 'system',
            'file-history-snapshot', 'file-history-delta', 'ai-title', 'mode', 'permission-mode',
            'cost-state'
        ]
    }

    private static boolean isSupportedRecord(Map record) {
        String type = record.type?.toString()
        if (!(type in ['user', 'assistant'])) return false
        if (type == 'user') return record.message instanceof Map
        if (type == 'assistant') return record.message instanceof Map || record.message == null
        return false
    }

    private static String unsupportedReason(Map record) {
        String type = record.type?.toString()
        if (type == 'user' || type == 'assistant') return null
        if (isIgnorableRecord(record)) return 'ignored_control_record'
        return "unknown_format:${record.keySet().take(4).join(',')}"
    }

    private static String extractUserText(Map record) {
        Map message = record.message as Map
        Object content = message?.content
        if (content instanceof String) return content.trim()
        if (content instanceof List) {
            return (content as List).collect { item ->
                if (item instanceof Map && item.type?.toString() == 'text') return item.text?.toString() ?: ''
                if (item instanceof Map && item.type?.toString() == 'tool_result') return item.content?.toString() ?: ''
                return item?.toString() ?: ''
            }.findAll { it }.join('\n').trim()
        }
        return content?.toString()?.trim() ?: ''
    }

    private static String extractAssistantText(Map record) {
        Map message = record.message as Map
        if (!message) return ''
        Object content = message.content
        if (content instanceof String) return content.trim()
        if (!(content instanceof List)) return ''
        return (content as List).collect { item ->
            if (item instanceof Map) {
                String type = item.type?.toString()
                if (type == 'text') return item.text?.toString() ?: ''
                if (type == 'thinking') return ''
            }
            return ''
        }.findAll { it }.join('\n').trim()
    }

    private static Map metaForRecord(Map record, String sourceIdentity, File file, long byteOffset, int lineNumber) {
        return [
            session_id: record.sessionId?.toString() ?: sourceIdentity.tokenize('/').last(),
            prompt_id: record.promptId?.toString() ?: record.uuid?.toString(),
            event_time: record.timestamp?.toString(),
            event_time_confidence: record.timestamp ? 'exact' : 'lost',
            source_byte_offset: byteOffset,
            source_start_line: lineNumber,
            source_end_line: lineNumber,
            source_identity: sourceIdentity,
            source_path: file.absolutePath
        ]
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

    private static Map emptyResult(String sourceIdentity, File file, long byteEnd) {
        return [
            format_version: Config.CLAUDE_TRANSCRIPT_FORMAT_V2,
            transcript_version: null,
            source_identity: sourceIdentity,
            source_path: file.absolutePath,
            source_ide: 'claude',
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
