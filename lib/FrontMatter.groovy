class FrontMatter {

    private static boolean isRegexSpace(char value) {
        return value == (char) ' ' || (value >= (char) 9 && value <= (char) 13)
    }

    /**
     * Splits leading front matter from the body in linear time. Candidate closing
     * markers are tried in the same order as the former regular expression.
     *
     * @param content Document text
     * @param requireBody True when a newline must follow the closing marker line
     * @return Map with front (the text between the markers) and body (the text after the closing marker line, null when requireBody is false), or null when the text has no front matter
     */
    static Map split(String content, boolean requireBody = true) {
        String text = content ?: ''
        int length = text.length()
        if (!text.startsWith('---')) return null
        int cursor = 3
        while (cursor < length && isRegexSpace(text.charAt(cursor))) cursor++
        int lastNewline = cursor > 3 ? text.lastIndexOf('\n', cursor - 1) : -1
        if (lastNewline < 3) return null
        int firstNewline = text.indexOf('\n', 3)
        boolean borrowedTried = firstNewline >= lastNewline || !text.startsWith('---', lastNewline + 1)
        int closing = text.indexOf('\n---', lastNewline + 1)
        String front = null
        while (true) {
            if (closing < 0) {
                if (borrowedTried) return null
                borrowedTried = true
                closing = lastNewline
                front = ''
            } else if (front == null || closing > lastNewline) {
                front = text.substring(lastNewline + 1, closing)
            }
            if (!requireBody) return [front: front, body: null]
            int after = closing + 4
            int end = after
            while (end < length && isRegexSpace(text.charAt(end))) end++
            int bodyNewline = end > after ? text.lastIndexOf('\n', end - 1) : -1
            if (bodyNewline >= after) return [front: front, body: text.substring(bodyNewline + 1)]
            closing = closing == lastNewline && borrowedTried && front == '' ? -1 : text.indexOf('\n---', closing + 1)
        }
    }

    /**
     * @param content Document text
     * @return The text without its leading front matter, or the text itself when it has none
     */
    static String strip(String content) {
        Map parts = split(content)
        return parts ? parts.body as String : (content ?: '')
    }
}
