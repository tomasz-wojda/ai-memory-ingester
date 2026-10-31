import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Reads, checks and seeds the labelled query set (JSON lines).
 * One line: qid, class (K Q P T X), lang (en pl pl-ascii mix), query, ticket, synthetic, labels [{doc, rel}], split (dev test).
 */
class GoldSet {

    static final List<String> CLASSES = ['K', 'Q', 'P', 'T', 'X']
    static final List<String> LANGUAGES = ['en', 'pl', 'pl-ascii', 'mix']
    static final List<String> SPLITS = ['dev', 'test']

    /**
     * @param file JSON lines file
     * @return Queries in file order
     */
    static List<Map> load(File file) {
        JsonSlurper slurper = new JsonSlurper()
        return file.readLines('UTF-8').findAll { it.trim() }.collect { String line -> slurper.parseText(line) as Map }
    }

    /**
     * @param queries Loaded queries
     * @return Problems found; an empty list means the set is valid
     */
    static List<String> validate(List<Map> queries) {
        List<String> problems = []
        Set<String> ids = [] as Set<String>
        queries.eachWithIndex { Map query, int index ->
            String where = "line ${index + 1} (${query.qid ?: 'no qid'})"
            if (!query.qid) problems << "${where}: qid is missing"
            else if (!ids.add(query.qid.toString())) problems << "${where}: qid is repeated"
            if (!(query['class'] in CLASSES)) problems << "${where}: class must be one of ${CLASSES}"
            if (!(query.lang in LANGUAGES)) problems << "${where}: lang must be one of ${LANGUAGES}"
            if (!(query.split in SPLITS)) problems << "${where}: split must be one of ${SPLITS}"
            if (!query.query?.toString()?.trim()) problems << "${where}: query is empty"
            if (!(query.labels instanceof List)) {
                problems << "${where}: labels must be a list"
            } else {
                (query.labels as List<Map>).each { Map label ->
                    if (!label.doc?.toString()?.trim()) problems << "${where}: a label has no doc"
                    if (!(label.rel instanceof Integer) || (label.rel as int) < 0 || (label.rel as int) > 3) {
                        problems << "${where}: rel must be 0 to 3 for ${label.doc}"
                    }
                }
            }
        }
        return problems
    }

    /**
     * @param query One loaded query
     * @return Grade by document id
     */
    static Map<String, Integer> labelMap(Map query) {
        Map<String, Integer> labels = [:]
        ((query.labels ?: []) as List<Map>).each { Map label -> labels[label.doc.toString()] = label.rel as int }
        return labels
    }

    /**
     * Creates unlabelled query candidates from ticket state decisions, blockers and next actions.
     * Each item gives a question form (class Q) and a keyword form (class K). The split is fixed by the ticket key,
     * so a ticket never appears in both dev and test.
     *
     * @param stateDir Folder with the ticket state JSON files
     * @return Candidates without labels
     */
    static List<Map> candidatesFromState(File stateDir) {
        List<Map> candidates = []
        JsonSlurper slurper = new JsonSlurper()
        (stateDir.listFiles({ File f -> f.name.endsWith('.json') } as FileFilter) ?: []).sort { it.name }.each { File file ->
            Map state
            try {
                state = slurper.parse(file) as Map
            } catch (Exception ignored) {
                return
            }
            String key = state.ticket_key?.toString() ?: file.name.replace('.json', '')
            String split = splitFor(key)
            int counter = 0
            ((state.decisions instanceof List ? state.decisions : []) as List).each { Object item ->
                if (item instanceof Map && item.description) {
                    counter = addPair(candidates, key, split, 'decision', item.id?.toString(), item.description.toString(), counter)
                }
            }
            ((state.blockers instanceof List ? state.blockers : []) as List).each { Object item ->
                if (item instanceof Map && item.description) {
                    counter = addPair(candidates, key, split, 'blocker', null, item.description.toString(), counter)
                }
            }
            if (state.next_action) {
                addPair(candidates, key, split, 'next', null, state.next_action.toString(), counter)
            }
        }
        return candidates
    }

    /**
     * @param ticket Ticket key
     * @return dev for about 70% of tickets, test for the rest, the same on every run
     */
    static String splitFor(String ticket) {
        return Math.abs((ticket ?: '').hashCode()) % 10 < 7 ? 'dev' : 'test'
    }

    private static int addPair(List<Map> candidates, String key, String split, String kind, String id, String description, int counter) {
        String flat = description.replaceAll(/\s+/, ' ').trim()
        List<String> words = flat.tokenize(' ').take(10)
        if (words.size() < 3) return counter
        String statement = words.join(' ').replaceAll(/[\p{P}&&[^_\-\/.]]+$/, '')
        boolean polish = flat =~ /[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]/
        String lead = polish
            ? [decision: 'Co ustaliliśmy w sprawie', blocker: 'Co blokuje', next: 'Jaki jest następny krok dla'][kind]
            : [decision: 'What did we decide about', blocker: 'What is blocking', next: 'What is the next step for'][kind]
        List<String> keywords = words.findAll { String word -> !StopWords.isStopWord(word.replaceAll(/[\p{P}&&[^_\-\/.]]/, '')) }.take(3)
        String lang = polish ? 'pl' : 'en'
        Map source = [ticket: key, kind: kind, id: id]
        candidates << [qid: "c-${key}-${kind}${counter}q", 'class': 'Q', lang: lang, query: "${lead} ${statement}?".toString(),
                       ticket: null, synthetic: false, source: source, labels: [], split: split]
        if (keywords) {
            candidates << [qid: "c-${key}-${kind}${counter}k", 'class': 'K', lang: lang, query: keywords.join(' '),
                           ticket: null, synthetic: false, source: source, labels: [], split: split]
        }
        return counter + 1
    }

    /**
     * @param queries Queries to write
     * @param file Target file (JSON lines)
     */
    static void write(List<Map> queries, File file) {
        file.parentFile?.mkdirs()
        file.setText(queries.collect { JsonOutput.toJson(it) }.join('\n') + (queries ? '\n' : ''), 'UTF-8')
    }
}
