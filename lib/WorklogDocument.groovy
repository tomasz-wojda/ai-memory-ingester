import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class WorklogDocument {
    static String renderChunk(Map chunk, File source, boolean current = false) {
        String content = (chunk.lines as List<String>).join('\n').trim()
        String hash = hash(content)
        return [
            '---',
            "ticket: \"${escape(chunk.ticket)}\"",
            "source: \"${escape(chunk.source_path)}\"",
            "source_lines: \"${chunk.start_line}-${chunk.end_line}\"",
            "source_order: ${chunk.source_order}",
            "source_modified: \"${new Date(source.lastModified()).toInstant()}\"",
            "category: \"${chunk.category}\"",
            "heading: \"${escape(chunk.heading)}\"",
            "authority: \"${authorityOf(chunk.category?.toString())}\"",
            "state: \"${currentStateOf(chunk.category?.toString(), current)}\"",
            "content_hash: \"${hash}\"",
            '---',
            '',
            "## ${chunk.heading}",
            '',
            content,
            ''
        ].join('\n')
    }

    static String renderEvidence(File source, String sourcePath, String ticket) {
        String content = source.getText('UTF-8')
        return [
            '---',
            "ticket: \"${escape(ticket ?: 'NOTICKET')}\"",
            "source: \"${escape(sourcePath)}\"",
            "source_lines: \"1-${source.readLines('UTF-8').size()}\"",
            'source_order: 0',
            "source_modified: \"${new Date(source.lastModified()).toInstant()}\"",
            'category: "evidence"',
            'heading: "EVIDENCE"',
            'authority: "evidence"',
            'state: "undetermined"',
            "content_hash: \"${hash(content)}\"",
            '---',
            '',
            '## Evidence',
            '',
            content.trim(),
            ''
        ].join('\n')
    }

    static String hash(String content) {
        return MessageDigest.getInstance('SHA-256')
            .digest((content ?: '').getBytes(StandardCharsets.UTF_8))
            .encodeHex()
            .toString()
    }

    static String categorySlug(String value) {
        String slug = (value ?: 'other').toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-|-$/, '')
        return slug ?: 'other'
    }

    static String authorityOf(String category) {
        return category in ['summary', 'decisions', 'status', 'delivery', 'next-action'] ? 'authoritative' : 'historical'
    }

    static String currentStateOf(String category, boolean current) {
        return current && category in ['summary', 'decisions', 'status', 'delivery', 'next-action'] ? 'current' : 'historical'
    }

    private static String escape(Object value) {
        return (value ?: '').toString().replace('\\', '\\\\').replace('"', '\\"').replace('\n', ' ')
    }
}
