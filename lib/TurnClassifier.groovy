/**
 * Routes parsed entries into memory tiers and assigns explicit impact classes.
 */
class TurnClassifier {

    /**
     * Classifies an entry and attaches tier and impact values.
     *
     * @param entry Parsed entry
     * @param spillBytes Spill threshold
     * @return Classified entry
     */
    static Map classify(Map entry, long spillBytes = Config.CHAT_SPILL_BYTES) {
        Map classified = new LinkedHashMap(entry)
        String impact = impactOf(classified)
        classified.impact = impact
        classified.excluded = MemoryExclusion.matches(classified)
        long renderedBytes = ((classified.rawBody ?: '') as String).getBytes('UTF-8').length
        classified.spillRequired = classified.dialect != 'Artifact' && renderedBytes > spillBytes
        classified.tier = classified.dialect == 'Artifact'
            ? 'artifacts'
            : (isDross(classified) ? 'dross' : 'turns')
        return classified
    }

    /**
     * Determines impact from the approved explicit label sets.
     *
     * @param entry Parsed entry
     * @return high, low, or none
     */
    static String impactOf(Map entry) {
        Map sections = (entry.sections ?: [:]) as Map
        if (sections.containsKey('SEMANTIC COMMIT TITLE') || sections.containsKey('WRITE GATE')) {
            return 'high'
        }
        for (String label : ['FILES CHANGED', 'REMOTE CHANGES']) {
            if (sections.containsKey(label) && !isNegation(sections[label]?.toString())) {
                return 'high'
            }
        }
        if (sections.keySet().any { Config.LOW_IMPACT_LABELS.contains(it?.toString()) }) {
            return 'low'
        }
        return 'none'
    }

    /**
     * Applies the exact dross allowlist and structural safeguards.
     *
     * @param entry Parsed entry
     * @return Whether the entry is dross
     */
    static boolean isDross(Map entry) {
        String user = (entry.user ?: '').toString().trim().toLowerCase(Locale.ROOT)
        String assistant = (entry.assistant ?: '').toString()
        Map sections = (entry.sections ?: [:]) as Map
        boolean hasChangeSection = sections.containsKey('FILES CHANGED') || sections.containsKey('REMOTE CHANGES')
        return Config.DROSS_ALLOWLIST.contains(user) &&
            assistant.getBytes('UTF-8').length < 120 &&
            !hasChangeSection
    }

    private static boolean isNegation(String value) {
        String normalized = (value ?: '').trim().toLowerCase(Locale.ROOT)
            .replaceAll(/[.\s_-]+/, ' ')
        return normalized in ['', 'none', 'no', 'no changes', 'n a', 'not applicable', 'unchanged']
    }
}
