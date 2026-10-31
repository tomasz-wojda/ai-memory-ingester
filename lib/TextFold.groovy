import java.text.Normalizer

/**
 * Case and diacritic folding that matches the FTS5 `unicode61 remove_diacritics 2` tokenizer,
 * plus the letters it does not fold.
 */
class TextFold {

    /**
     * Lower-cases a value and removes diacritics, including the letter that the FTS5 tokenizer keeps (ł).
     *
     * @param value Any text
     * @return ASCII-folded lower-case text
     */
    static String ascii(String value) {
        String lowered = (value ?: '').toLowerCase(Locale.ROOT).replace('ł', 'l').replace('đ', 'd').replace('ø', 'o')
        String decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return decomposed.replaceAll(/\p{M}+/, '')
    }

    /**
     * Lists spellings of an ASCII word in which some letters l are written as ł.
     * The FTS5 tokenizer folds every Polish diacritic except ł, so "bylo" does not match "było".
     * Words with one or two letters l give up to three variants; other words give none.
     *
     * @param word Folded ASCII word
     * @return Variants that contain ł and are otherwise ASCII
     */
    static List<String> polishLVariants(String word) {
        String value = word ?: ''
        List<Integer> positions = []
        value.eachWithIndex { String c, int index -> if (c == 'l') positions << index }
        if (positions.isEmpty() || positions.size() > 2 || value.length() < 3) return []
        List<String> variants = []
        int combinations = (1 << positions.size()) - 1
        for (int mask = 1; mask <= combinations; mask++) {
            StringBuilder builder = new StringBuilder(value)
            positions.eachWithIndex { int position, int bit ->
                if ((mask & (1 << bit)) != 0) builder.setCharAt(position, 'ł' as char)
            }
            variants << builder.toString()
        }
        return variants
    }
}
