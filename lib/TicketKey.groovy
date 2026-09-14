import java.util.regex.Pattern

class TicketKey {
    private static final Pattern SOURCE_PATTERN = ~/\b(?:[A-Z][A-Z0-9]{1,9}-\d{1,8}|(?i:SCTASK\d{6,10}))\b/
    private static final Pattern HYPHENATED = ~/(?i)^[A-Z][A-Z0-9]{1,9}-\d{1,8}$/
    private static final Pattern SERVICE_NOW_TASK = ~/(?i)^SCTASK\d{6,10}$/

    static List<String> extract(String text) {
        List<String> keys = []
        def matcher = SOURCE_PATTERN.matcher(text ?: '')
        while (matcher.find()) {
            String key = canonical(matcher.group())
            if (key && !keys.contains(key)) keys << key
        }
        return keys
    }

    static String canonical(String value) {
        String candidate = (value ?: '').trim().toUpperCase(Locale.ROOT)
        if (candidate ==~ SERVICE_NOW_TASK || candidate ==~ HYPHENATED) return candidate
        return null
    }

    static boolean supported(String value) {
        return canonical(value) != null
    }

    static String facet(String value) {
        String key = canonical(value)
        return key ? key.toLowerCase(Locale.ROOT).replaceAll(/[^a-z0-9]/, '') : 'noticket'
    }

    static List<String> prioritize(String headerText, String bodyText) {
        List<String> result = []
        [extract(headerText), extract(bodyText)].each { List<String> keys ->
            keys.each { String key -> if (!result.contains(key)) result << key }
        }
        return result
    }
}
