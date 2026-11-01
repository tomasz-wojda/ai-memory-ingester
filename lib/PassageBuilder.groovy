import java.security.MessageDigest

/**
 * Splits a memory document into passages for re-ranking and late interaction.
 * Structure first (turn sections, state headings), then windows of WINDOW_CHARS with a stride of STRIDE_CHARS.
 */
class PassageBuilder {

    static final int WINDOW_CHARS = 900
    static final int STRIDE_CHARS = 675
    static final int MAX_PER_DOCUMENT = 3
    static final int MAX_PASSAGE_CHARS = 3900

    /**
     * @param doc Map with origin_db, source_archive, file_path, content
     * @return Identifier of the document as used in labels and signals
     */
    static String docId(Map doc) {
        return "${doc.origin_db}:${doc.source_archive}:${doc.file_path}"
    }

    /**
     * @param doc Map with origin_db, source_archive, file_path, content
     * @return All passages of the document, in text order
     */
    static List<Map> split(Map doc) {
        String content = doc.content?.toString() ?: ''
        Map meta = TicketFreshness.frontMatter(content)
        String body = content.replaceFirst(/(?s)^---\s*\n.*?\n---\s*\n/, '')
        List<Map> passages = []
        sections(doc.source_archive?.toString(), body).each { Map section ->
            String text = section.text as String
            if (!text.trim()) return
            windows(text).each { Map window ->
                int start = (section.start as int) + (window.start as int)
                int end = (section.start as int) + (window.end as int)
                String piece = (window.text as String)
                if (!piece.trim()) return
                String shown = (prefix(doc, meta, section.name as String) + piece).take(MAX_PASSAGE_CHARS)
                passages << [
                    id: passageId(doc, section.name as String, start, end),
                    doc_id: docId(doc),
                    section: section.name,
                    start: start,
                    end: end,
                    tokens: (shown.length() + 3).intdiv(4),
                    text: shown
                ]
            }
        }
        return passages
    }

    /**
     * Keeps the passages that share the most query words with the query.
     *
     * @param passages Passages of one document
     * @param queryWords Content words of the query
     * @param max How many to keep
     * @return Up to max passages, best first; the first passage if none share a word
     */
    static List<Map> select(List<Map> passages, List<String> queryWords, int max = MAX_PER_DOCUMENT) {
        if (passages.size() <= max) return passages
        Set<String> wanted = queryWords.collect { String word -> TextFold.ascii(word) }.findAll { it } as Set<String>
        List<Map> scored = passages.collect { Map passage ->
            Set<String> found = (TextFold.ascii(passage.text as String) =~ /[a-z0-9_]+/).collect { it.toString() } as Set<String>
            [passage: passage, score: wanted.count { found.contains(it) } as int]
        }
        List<Map> best = scored.findAll { (it.score as int) > 0 }
            .sort { a, b -> (b.score as int) <=> (a.score as int) ?: (a.passage.start as int) <=> (b.passage.start as int) }
            .take(max)
        if (best.isEmpty()) return passages.take(1)
        return best.collect { it.passage as Map }.sort { (it.start as int) }
    }

    /**
     * @return The first 16 hex digits of the SHA-1 of the passage key (the service computes the same value)
     */
    static String passageId(Map doc, String section, int start, int end) {
        String key = [doc.origin_db, doc.source_archive, doc.file_path, section, start, end].join('|')
        byte[] digest = MessageDigest.getInstance('SHA-1').digest(key.getBytes('UTF-8'))
        return digest.collect { String.format('%02x', it & 0xff) }.join().take(16)
    }

    /**
     * @return SHA-256 of the text, as hex
     */
    static String contentHash(String text) {
        byte[] digest = MessageDigest.getInstance('SHA-256').digest((text ?: '').getBytes('UTF-8'))
        return digest.collect { String.format('%02x', it & 0xff) }.join()
    }

    private static List<Map> sections(String archive, String body) {
        if (archive == 'chat:turns') {
            List<Map> found = []
            def matcher = body =~ /(?m)^## (User|Assistant|Details)\s*$/
            List<Map> heads = []
            while (matcher.find()) heads << [name: matcher.group(1).toLowerCase(), at: matcher.end()]
            heads.eachWithIndex { Map head, int index ->
                int from = head.at as int
                int to = index + 1 < heads.size() ? body.lastIndexOf('## ', (heads[index + 1].at as int)) : body.length()
                if (to < from) to = body.length()
                found << [name: head.name, start: from, text: body.substring(from, to)]
            }
            return found ?: [[name: 'turn', start: 0, text: body]]
        }
        if (archive == 'worklog:state') {
            List<Map> found = []
            def matcher = body =~ /(?m)^## (.+)$/
            List<Map> heads = []
            while (matcher.find()) heads << [name: matcher.group(1).trim().toLowerCase().replaceAll(/[^a-z0-9]+/, '-'), at: matcher.end(), begin: matcher.start()]
            if (heads.isEmpty()) return [[name: 'state', start: 0, text: body]]
            if ((heads[0].begin as int) > 0) found << [name: 'state', start: 0, text: body.substring(0, heads[0].begin as int)]
            heads.eachWithIndex { Map head, int index ->
                int to = index + 1 < heads.size() ? heads[index + 1].begin as int : body.length()
                found << [name: "state-${head.name}".toString(), start: head.at as int, text: body.substring(head.at as int, to)]
            }
            return found
        }
        return [[name: 'chunk', start: 0, text: body]]
    }

    private static List<Map> windows(String text) {
        if (text.length() <= WINDOW_CHARS) return [[start: 0, end: text.length(), text: text]]
        List<Map> result = []
        int start = 0
        while (start < text.length()) {
            int end = Math.min(text.length(), start + WINDOW_CHARS)
            result << [start: start, end: end, text: text.substring(start, end)]
            if (end == text.length()) break
            start += STRIDE_CHARS
        }
        return result
    }

    private static String prefix(Map doc, Map meta, String section) {
        List<String> parts = []
        String path = doc.file_path?.toString() ?: ''
        String ticket = path.tokenize('/').find { it ==~ /[a-z]+[0-9]+/ }
        if (ticket) parts << "ticket=${ticket}".toString()
        String stamp = meta.timestamp?.toString()
        if (stamp && stamp.length() >= 10) parts << "date=${stamp.substring(0, 10)}".toString()
        if (meta.mode) parts << "mode=${meta.mode}".toString()
        parts << "section=${section}".toString()
        return parts.join(' ') + '\n'
    }
}
