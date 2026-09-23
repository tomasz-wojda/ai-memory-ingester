class MemoryExclusion {

    static final List<String> HANDOFF_MARKERS = [
        'commit handoff',
        'commit_handoff',
        'commit-handoff',
        'commit hook',
        'commit-hook',
        'require-commit-handoff',
        'capture-agent-response',
        'semantic git commit',
        'git add --'
    ].asImmutable()

    static boolean matches(Map entry) {
        String content = [
            entry.user,
            entry.user_text,
            entry.assistant,
            entry.assistant_text,
            entry.rawBody,
            entry.raw_payload,
            entry.content
        ].findAll { it != null }.collect { it.toString() }.join('\n')
        return matches(content)
    }

    static boolean matches(String content) {
        String normalized = (content ?: '').toLowerCase(Locale.ROOT)
        return normalized.contains('ai-vault') &&
            HANDOFF_MARKERS.any { String marker -> normalized.contains(marker) }
    }
}
