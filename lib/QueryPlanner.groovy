import java.util.regex.Pattern

/**
 * Builds the strict and the relaxed form of a free-text query.
 * The strict form is the query as typed (FTS5 ANDs bare terms).
 * The relaxed form drops stop words and ORs the remaining words, with prefix and Polish ł variants.
 * A query with an explicit operator, phrase, parenthesis, column filter or prefix term is never rewritten.
 */
class QueryPlanner {

    static final int MAX_TERMS = 40
    static final int MIN_PREFIX_LETTERS = 5
    static final int MIN_PREFIX_KEEP = 4
    static final int PREFIX_CUT = 3

    private static final Pattern WORD = ~/^[\p{L}\p{N}_]+$/
    private static final Pattern EDGE_PUNCTUATION = ~/^[\p{P}&&[^_]]+|[\p{P}&&[^_]]+$/
    private static final Pattern POLISH_LETTER = ~/[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]/
    private static final List<String> OPERATORS = ['AND', 'OR', 'NOT', 'NEAR']

    /**
     * @param raw Query terms without the ticket key (already split by the caller)
     * @return Map with raw, strict, relaxed, explicit, terms, stop_words_removed, language_guess
     */
    static Map plan(String raw) {
        String text = (raw ?: '').trim()
        boolean explicit = isExplicit(text)
        List<String> tokens = tokensOf(text)
        List<String> removed = []
        List<String> kept = []
        if (!explicit) {
            tokens.each { String token ->
                String cleaned = token.replaceAll(EDGE_PUNCTUATION, '')
                if (!cleaned) return
                if (isStopToken(cleaned)) {
                    removed << cleaned
                } else {
                    kept << cleaned
                }
            }
            if (kept.isEmpty()) {
                kept = tokens.collect { String token -> token.replaceAll(EDGE_PUNCTUATION, '') }.findAll { it }
                removed = []
            }
        }
        String language = languageGuess(tokens)
        List<String> relaxedTerms = explicit ? [] : relaxedTerms(kept, language == 'pl')
        return [
            raw: text,
            strict: text,
            relaxed: relaxedTerms ? relaxedTerms.join(' OR ') : null,
            explicit: explicit,
            terms: kept,
            stop_words_removed: removed,
            language_guess: language
        ]
    }

    /**
     * @param text Query terms
     * @return true when the query holds an operator, a phrase, parentheses, a column filter or a typed prefix term
     */
    static boolean isExplicit(String text) {
        if (!text) return false
        if (text.contains('"') || text.contains('(') || text.contains(')')) return true
        return text.tokenize(' ').any { String token ->
            token in OPERATORS || token.endsWith('*') || token ==~ /^(file_path|file_name|content|source_archive|extension):.*/
        }
    }

    private static List<String> tokensOf(String text) {
        return text ? text.tokenize(' \t\r\n') : []
    }

    private static boolean isStopToken(String cleaned) {
        if (cleaned.length() >= 2 && cleaned == cleaned.toUpperCase(Locale.ROOT) && cleaned =~ /\p{L}/) return false
        if (cleaned =~ /\d/ || cleaned.contains('_')) return false
        return StopWords.isStopWord(cleaned)
    }

    private static List<String> relaxedTerms(List<String> words, boolean polish) {
        LinkedHashSet<String> terms = new LinkedHashSet<String>()
        Set<String> seen = [] as Set<String>
        for (String word : words) {
            String key = TextFold.ascii(word)
            if (!seen.add(key)) continue
            if (!(word ==~ WORD)) {
                terms << quote(word)
            } else {
                String lowered = word.toLowerCase(Locale.ROOT)
                terms << lowered
                if (isLetters(lowered) && lowered.length() >= MIN_PREFIX_LETTERS) {
                    terms << prefixOf(lowered)
                }
                if (polish && isLetters(lowered) && !(lowered ==~ /.*ł.*/)) {
                    TextFold.polishLVariants(key).each { String variant ->
                        terms << variant
                        if (variant.length() >= MIN_PREFIX_LETTERS) terms << prefixOf(variant)
                    }
                }
            }
            if (terms.size() >= MAX_TERMS) break
        }
        return terms.take(MAX_TERMS).toList()
    }

    private static String prefixOf(String word) {
        int keep = Math.max(MIN_PREFIX_KEEP, word.length() - PREFIX_CUT)
        return word.substring(0, Math.min(keep, word.length())) + '*'
    }

    private static boolean isLetters(String word) {
        return word ==~ /^\p{L}+$/
    }

    private static String quote(String word) {
        return '"' + word.replace('"', '""') + '"'
    }

    private static String languageGuess(List<String> tokens) {
        if (tokens.any { String token -> POLISH_LETTER.matcher(token).find() }) return 'pl'
        int polish = tokens.count { String token -> StopWords.isPolish(token) && !StopWords.isEnglish(token) } as int
        int english = tokens.count { String token -> StopWords.isEnglish(token) && !StopWords.isPolish(token) } as int
        return polish > english ? 'pl' : 'en'
    }
}
