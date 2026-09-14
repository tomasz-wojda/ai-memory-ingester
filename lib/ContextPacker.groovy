import java.nio.charset.StandardCharsets

class ContextPacker {

    static Map pack(List<Map> candidates, int budgetBytes = Config.TICKET_CONTEXT_BUDGET_BYTES, boolean completeRows = true) {
        int used = 0
        List<Map> selected = []
        int omitted = 0
        candidates.each { Map candidate ->
            if (candidate.excluded_reason) {
                omitted++
                return
            }
            String content = completeRows
                ? fullContent(candidate)
                : (candidate.content?.toString() ?: candidate.snippet?.toString() ?: '')
            if (!content) {
                omitted++
                return
            }
            String packed = renderItem(candidate, content)
            int bytes = packed.getBytes(StandardCharsets.UTF_8).length
            if (bytes == 0) {
                omitted++
                return
            }
            if (used + bytes > budgetBytes) {
                omitted++
                return
            }
            selected << new LinkedHashMap(candidate) + [
                content: content,
                packed_content: packed,
                content_bytes: bytes
            ]
            used += bytes
        }
        return [
            items: selected,
            content_bytes: used,
            budget_bytes: budgetBytes,
            omitted_count: omitted,
            complete_rows: completeRows
        ]
    }

    static String renderAssembly(Map assembly) {
        StringBuilder builder = new StringBuilder()
        (assembly.items as List<Map> ?: []).each { Map item ->
            builder.append(item.packed_content ?: renderItem(item, fullContent(item))).append('\n\n')
        }
        return builder.toString().trim()
    }

    static String renderItem(Map candidate, String content) {
        List<String> header = [
            "---",
            "provenance: ${candidate.origin_db ?: 'unknown'}:${candidate.source_archive ?: 'unknown'}:${candidate.file_path ?: candidate.file_name ?: ''}",
            "pool: ${candidate.pool ?: 'unknown'}",
            "authority: ${candidate.authority ?: 'historical'}",
            "state: ${candidate.state ?: 'historical'}",
            "freshness: ${candidate.freshness ?: 'undetermined'}",
            "signals: ${(candidate.signals ?: [:]).collect { k, v -> "${k}=${v}" }.join(', ')}",
            "fused_score: ${candidate.fused_score ?: candidate.rrf_score ?: ''}",
            "---"
        ]
        return (header + ['', content ?: '']).join('\n')
    }

    static String fullContent(Map candidate) {
        String content = candidate.content?.toString()
        if (content) return content
        return candidate.snippet?.toString() ?: ''
    }
}
