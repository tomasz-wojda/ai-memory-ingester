import java.util.regex.Matcher
import java.util.regex.Pattern

class TicketCoverage {

    static final Pattern TOKEN = Pattern.compile('[a-z0-9][a-z0-9_#./-]{2,}')
    static final int HEAD_CHARS = 1050
    static final double RECENCY_FACTOR = 0.5d
    static final String SIDECAR_NAME = 'term-df.tsv'
    static final String CHAT_TURNS = 'chat:turns'

    /**
     * Tells whether a query holds only generic words, so that it names no topic.
     *
     * @param terms Query terms without the ticket token
     * @return True when every word is in Config.GENERIC_QUERY_WORDS
     */
    static boolean genericTerms(String terms) {
        List<String> words = (terms ?: '').toLowerCase().findAll(~/[a-z0-9]+/) as List<String>
        return !words.isEmpty() && words.every { Config.GENERIC_QUERY_WORDS.contains(it) }
    }

    /**
     * The evidence text of a turn: the first HEAD_CHARS characters after the front matter, lowercased.
     *
     * @param content Full turn document
     * @return Head text
     */
    static String headText(String content) {
        String text = content ?: ''
        int marker = text.indexOf('\n---')
        String body = marker >= 0 ? text.substring(Math.min(text.length(), marker + 5)) : text
        return body.take(HEAD_CHARS).toLowerCase()
    }

    /**
     * Tells whether a token identifies something: it has a digit, 7 or more characters, or one of _ # . / -.
     *
     * @param token Lowercase token
     * @return True for an informative token
     */
    static boolean informative(String token) {
        if (token.length() >= 7) return true
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i)
            if (Character.isDigit(c) || '_#./-'.indexOf((int) c) >= 0) return true
        }
        return false
    }

    /**
     * All tokens of a text.
     *
     * @param text Lowercase text
     * @return Token set
     */
    static Set<String> tokens(String text) {
        Set<String> found = new LinkedHashSet<String>()
        Matcher matcher = TOKEN.matcher(text ?: '')
        while (matcher.find()) found << matcher.group()
        return found
    }

    /**
     * Document frequency of every token over the head text of all chat turns of a database. The result is kept in a
     * sidecar file next to the database and rebuilt when the stamp (count and highest id) of the archive changes.
     *
     * @param conversationDb Path of convo.db
     * @return Map with the keys documents (count) and df (token to count)
     */
    static Map<String, Object> documentFrequency(File conversationDb) {
        File sidecar = new File(conversationDb.parentFile, SIDECAR_NAME)
        MemoryEngine engine = new MemoryEngine(conversationDb.absolutePath)
        try {
            String stamp = engine.archiveStamp(CHAT_TURNS)
            int documents = Integer.parseInt(stamp.substring(0, stamp.indexOf(':')))
            Map<String, Integer> cached = readSidecar(sidecar, stamp)
            if (cached != null) return [documents: documents, df: cached]
            Map<String, Integer> df = new HashMap<String, Integer>()
            engine.eachDocument(CHAT_TURNS) { Map document ->
                tokens(headText(document.content?.toString())).each { String token -> df.merge(token, 1, Integer.&sum) }
            }
            writeSidecar(sidecar, stamp, df)
            return [documents: documents, df: df]
        } finally {
            engine.close()
        }
    }

    private static Map<String, Integer> readSidecar(File sidecar, String stamp) {
        if (!sidecar.exists()) return null
        Map<String, Integer> df = new HashMap<String, Integer>()
        boolean first = true
        boolean valid = false
        sidecar.eachLine('UTF-8') { String line ->
            if (first) {
                first = false
                valid = line == "#stamp\t${stamp}".toString()
                return
            }
            if (!valid) return
            int tab = line.lastIndexOf('\t')
            if (tab > 0) df.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1)))
        }
        return valid ? df : null
    }

    private static void writeSidecar(File sidecar, String stamp, Map<String, Integer> df) {
        try {
            File temporary = new File(sidecar.parentFile, "${SIDECAR_NAME}.${System.nanoTime()}.tmp")
            temporary.withWriter('UTF-8') { Writer writer ->
                writer.write("#stamp\t${stamp}\n")
                df.each { String token, Integer count -> writer.write("${token}\t${count}\n") }
            }
            if (!temporary.renameTo(sidecar)) temporary.delete()
        } catch (Exception ignored) {
            // A read-only data folder only costs the rebuild on the next query.
        }
    }

    /**
     * Greedy choice of turns: each step takes the turn that adds the most idf of informative tokens not yet covered,
     * weighted up to 1.5 for the newest turn. The loop ends when no turn adds anything.
     *
     * @param turns Candidate turns (maps with content and sequence)
     * @param documents Number of chat turns in the database
     * @param df Document frequency per token
     * @param count Number of turns to choose
     * @return Chosen turns in choice order
     */
    static List<Map> select(List<Map> turns, int documents, Map<String, Integer> df, int count) {
        if (!turns) return []
        List<Set<String>> covered = turns.collect { Map turn ->
            tokens(headText(turn.content?.toString())).findAll { informative(it) } as Set<String>
        }
        long low = turns.collect { (it.sequence ?: 0L) as long }.min()
        long high = turns.collect { (it.sequence ?: 0L) as long }.max()
        double span = Math.max(1L, high - low)
        Set<String> seen = new HashSet<String>()
        Set<Integer> chosen = new LinkedHashSet<Integer>()
        while (chosen.size() < count) {
            int best = -1
            double bestScore = 0.0d
            for (int i = 0; i < turns.size(); i++) {
                if (chosen.contains(i)) continue
                double gain = 0.0d
                for (String token : covered[i]) {
                    if (!seen.contains(token)) gain += Math.log(documents / (double) Math.max(1, df.get(token) ?: 1))
                }
                gain *= 1.0d + RECENCY_FACTOR * (((turns[i].sequence ?: 0L) as long) - low) / span
                if (gain > bestScore) {
                    bestScore = gain
                    best = i
                }
            }
            if (best < 0) break
            chosen << best
            seen.addAll(covered[best])
        }
        return chosen.collect { int index -> turns[index] }
    }
}
