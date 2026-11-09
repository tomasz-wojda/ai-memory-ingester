class TopicRoute {

    static final Set<String> NO_TICKET_FOLDERS = ['noticket', 'sha256', 'utf8', ''].toSet().asImmutable()
    static final int WINDOW = 10
    static final Set<String> STOP_WORDS = ('the and for are was with from that this have has not but you your what how why when where which who ' +
        'jak co czy dla się jest oraz przez nie lub ale tak już jako ten ta to na do w z o u po przy aby tego były była było').tokenize(' ').toSet().asImmutable()

    /**
     * The content words of a query: lowercase words of 3 or more characters that are not stop words.
     *
     * @param terms Query terms
     * @return Words in order
     */
    static List<String> queryWords(String terms) {
        List<String> words = []
        (terms ?: '').toLowerCase().findAll(~/[\p{L}\p{N}_\-]+/).each { String word ->
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) words << word
        }
        return words
    }

    /**
     * The share of the query words that occur in at least one of the given texts.
     *
     * @param terms Query terms
     * @param heads Lowercase evidence texts of the first result rows
     * @return Share from 0 to 1 (0 when the query has no content word or there is no text)
     */
    static double coveredShare(String terms, List<String> heads) {
        List<String> words = queryWords(terms)
        if (words.isEmpty() || heads.isEmpty()) return 0.0d
        int found = words.count { String word -> heads.any { it.contains(word) } }
        return found / (double) words.size()
    }

    /**
     * The ticket folder that holds most of the first chat rows of a result list.
     *
     * @param rows Result rows in rank order (maps with source_archive and file_path)
     * @param window Number of chat rows to look at
     * @return Map with ticket (folder name) and share (rows in the window), or null when no row has a ticket folder
     */
    static Map dominant(List<Map> rows, int window = WINDOW) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>()
        int seen = 0
        for (Map row : rows) {
            if (row.source_archive?.toString() != TicketCoverage.CHAT_TURNS) continue
            if (seen++ >= window) break
            List<String> parts = (row.file_path?.toString() ?: '').tokenize('/') as List<String>
            String folder = parts ? parts[0] : ''
            if (NO_TICKET_FOLDERS.contains(folder)) continue
            counts.merge(folder, 1, Integer.&sum)
        }
        if (counts.isEmpty()) return null
        Map.Entry<String, Integer> best = null
        counts.each { String folder, Integer count ->
            if (best == null || count > best.value) best = new AbstractMap.SimpleEntry<String, Integer>(folder, count)
        }
        return [ticket: best.key, share: best.value]
    }
}
