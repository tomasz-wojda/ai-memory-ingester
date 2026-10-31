/**
 * English and Polish stop words for the relaxed query form.
 * Matching uses the ASCII-folded form, so Polish words match with and without diacritics.
 */
class StopWords {

    private static final List<String> ENGLISH = [
        'a', 'about', 'above', 'after', 'again', 'all', 'also', 'am', 'an', 'and', 'any', 'are', 'as', 'at',
        'be', 'because', 'been', 'before', 'being', 'below', 'between', 'both', 'but', 'by', 'can', 'could',
        'did', 'do', 'does', 'doing', 'done', 'down', 'during', 'each', 'few', 'for', 'from', 'further',
        'had', 'has', 'have', 'having', 'he', 'her', 'here', 'hers', 'him', 'his', 'how', 'i', 'if', 'in',
        'into', 'is', 'it', 'its', 'just', 'me', 'more', 'most', 'my', 'no', 'nor', 'not', 'now', 'of', 'off',
        'on', 'once', 'only', 'or', 'other', 'our', 'ours', 'out', 'over', 'own', 'same', 'she', 'should',
        'so', 'some', 'such', 'than', 'that', 'the', 'their', 'theirs', 'them', 'then', 'there', 'these',
        'they', 'this', 'those', 'through', 'to', 'too', 'under', 'until', 'up', 'us', 'very', 'was', 'we',
        'were', 'what', 'when', 'where', 'which', 'while', 'who', 'whom', 'why', 'will', 'with', 'would',
        'you', 'your', 'yours', 'tell', 'show', 'find', 'give', 'please', 'anything', 'something'
    ]

    private static final List<String> POLISH = [
        'a', 'aby', 'albo', 'ale', 'bardzo', 'bez', 'bo', 'by', 'byc', 'byl', 'byla', 'bylo', 'byly',
        'co', 'czy', 'czyli', 'dla', 'dlaczego', 'do', 'gdy', 'gdzie', 'i', 'ich', 'ile', 'im', 'ja',
        'jak', 'jaka', 'jakie', 'jaki', 'jako', 'je', 'jego', 'jej', 'jest', 'jestem', 'jestesmy', 'jeszcze',
        'jesli', 'juz', 'kiedy', 'ktora', 'ktore', 'ktory', 'ktorzy', 'lub', 'mam', 'mamy', 'mi', 'mnie',
        'moj', 'moja', 'moje', 'my', 'na', 'nam', 'nas', 'nasz', 'nasza', 'nasze', 'nie', 'o',
        'od', 'on', 'ona', 'one', 'oni', 'ono', 'po', 'poniewaz', 'przed', 'przez', 'przy', 'sa',
        'sie', 'tak', 'takze', 'tam', 'te', 'tego', 'ten', 'to', 'ta', 'tu', 'ty', 'tylko', 'tym', 'u',
        'w', 'wam', 'was', 'wiec', 'wszystko', 'wy', 'z', 'za', 'ze', 'zeby', 'zrobilismy', 'powiedz',
        'pokaz', 'znajdz', 'prosze', 'cos', 'czegos', 'ustalilismy', 'zdecydowalismy', 'rozwiazalismy',
        'dotyczaca', 'dotyczacy', 'dotyczace', 'dotyczacej'
    ]

    private static final Set<String> FOLDED_ENGLISH = fold(ENGLISH)
    private static final Set<String> FOLDED_POLISH = fold(POLISH)

    private static Set<String> fold(List<String> words) {
        Set<String> folded = [] as LinkedHashSet<String>
        words.each { String word -> folded << TextFold.ascii(word) }
        return folded.asImmutable()
    }

    /**
     * @param word Any word, any case, with or without diacritics
     * @return true when the word is an English or Polish stop word
     */
    static boolean isStopWord(String word) {
        String folded = TextFold.ascii(word)
        return FOLDED_ENGLISH.contains(folded) || FOLDED_POLISH.contains(folded)
    }

    /**
     * @param word Any word
     * @return true when the folded word is a Polish stop word
     */
    static boolean isPolish(String word) {
        return FOLDED_POLISH.contains(TextFold.ascii(word))
    }

    /**
     * @param word Any word
     * @return true when the folded word is an English stop word
     */
    static boolean isEnglish(String word) {
        return FOLDED_ENGLISH.contains(TextFold.ascii(word))
    }
}
