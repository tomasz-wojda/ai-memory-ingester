import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

    /**
     * @param evalDir Folder of the gold set (data/eval)
     * @return File that holds the switch for the gold set
     */
    static File stateFile(File evalDir) {
        return new File(evalDir, 'gold.state.json')
    }

    /**
     * @param evalDir Folder of the gold set (data/eval)
     * @return Folder with the snapshots of earlier versions and the change log
     */
    static File historyDir(File evalDir) {
        return new File(evalDir, 'gold-history')
    }

    /**
     * Tells whether the default gold set may be used. Off unless it was switched on.
     * AI_MEMORY_GOLD (1 or 0) overrides the state file.
     *
     * @param evalDir Folder of the gold set
     * @return true when the gold set is switched on
     */
    static boolean enabled(File evalDir) {
        String override = System.getProperty('ai.memory.gold') ?: System.getenv('AI_MEMORY_GOLD')
        if (override != null && override.trim()) return override.trim().toLowerCase() in ['1', 'on', 'true', 'yes']
        File state = stateFile(evalDir)
        if (!state.isFile()) return false
        try {
            return (new JsonSlurper().parse(state) as Map).enabled == true
        } catch (Exception ignored) {
            return false
        }
    }

    /**
     * @param evalDir Folder of the gold set
     * @return Where the switch comes from: environment, state file or default
     */
    static String enabledSource(File evalDir) {
        String override = System.getProperty('ai.memory.gold') ?: System.getenv('AI_MEMORY_GOLD')
        if (override != null && override.trim()) return 'environment'
        return stateFile(evalDir).isFile() ? 'state file' : 'default'
    }

    /**
     * @param evalDir Folder of the gold set
     * @param value true to switch the gold set on, false to switch it off
     */
    static void setEnabled(File evalDir, boolean value) {
        evalDir.mkdirs()
        stateFile(evalDir).setText(JsonOutput.prettyPrint(JsonOutput.toJson([enabled: value, updated: Instant.now().toString()])) + '\n', 'UTF-8')
    }

    /**
     * Merges query files into the gold set. A query with the same qid replaces the old one.
     * Before the set changes, the old version is kept as a snapshot.
     *
     * @param evalDir Folder of the gold set
     * @param goldFile The gold set file
     * @param incomingFiles Files in the gold set format (for example the export of the labelling page); a later file wins
     * @param replace true to drop every query that is not in the incoming files
     * @return Map with added, updated, kept, removed, unchanged and the snapshot name (or null when nothing changed)
     */
    static Map importFiles(File evalDir, File goldFile, List<File> incomingFiles, boolean replace = false) {
        Map<String, Map> incoming = new LinkedHashMap<>()
        incomingFiles.each { File file ->
            List<Map> loaded = load(file)
            List<String> problems = validate(loaded)
            if (problems) throw new IllegalArgumentException("${file.name}: ${problems.take(5).join('; ')}".toString())
            loaded.each { Map query -> incoming[query.qid.toString()] = query }
        }
        List<Map> existing = goldFile.isFile() ? load(goldFile) : []
        Map<String, Map> existingById = existing.collectEntries { Map query -> [(query.qid.toString()): query] }
        Map<String, Map> merged = new LinkedHashMap<>()
        if (!replace) existingById.each { String id, Map query -> merged[id] = query }
        int added = 0
        int updated = 0
        int unchanged = 0
        incoming.each { String id, Map query ->
            if (!existingById.containsKey(id)) added++
            else if (existingById[id] != query) updated++
            else unchanged++
            merged[id] = query
        }
        int kept = replace ? 0 : existingById.keySet().count { !incoming.containsKey(it) }
        int removed = replace ? existingById.keySet().count { !incoming.containsKey(it) } : 0
        List<Map> result = merged.values().toList()
        String snapshotName = null
        if (result != existing) {
            if (goldFile.isFile()) snapshotName = snapshot(evalDir, goldFile)
            write(result, goldFile)
        }
        Map summary = [added: added, updated: updated, kept: kept, removed: removed, unchanged: unchanged, snapshot: snapshotName]
        logChange(evalDir, summary + [action: replace ? 'replace' : 'import', files: incomingFiles*.name, queries: result.size()])
        return summary
    }

    /**
     * @param evalDir Folder of the gold set
     * @return Names of the snapshots, oldest first
     */
    static List<String> history(File evalDir) {
        return (historyDir(evalDir).listFiles({ File f -> f.name ==~ /gold-[0-9-]+\.jsonl/ } as FileFilter) ?: [])*.name.sort()
    }

    /**
     * Puts an earlier version back. The current version is kept as a snapshot first.
     *
     * @param evalDir Folder of the gold set
     * @param goldFile The gold set file
     * @param name Snapshot name from history
     * @return Name of the snapshot of the version that was replaced (or null when the file was the same)
     */
    static String restore(File evalDir, File goldFile, String name) {
        if (!(name ==~ /gold-[0-9-]+\.jsonl/)) throw new IllegalArgumentException("Not a snapshot name: ${name}".toString())
        File source = new File(historyDir(evalDir), name)
        if (!source.isFile()) throw new IllegalArgumentException("No such snapshot: ${name}".toString())
        String snapshotName = null
        if (goldFile.isFile() && goldFile.text != source.text) snapshotName = snapshot(evalDir, goldFile)
        goldFile.parentFile?.mkdirs()
        Files.copy(source.toPath(), goldFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        logChange(evalDir, [action: 'restore', from: name, snapshot: snapshotName])
        return snapshotName
    }

    /**
     * @param goldFile The gold set file
     * @return Counts, the grades given, and warnings when the set cannot support a measurement
     */
    static Map status(File goldFile) {
        List<Map> queries = goldFile.isFile() ? load(goldFile) : []
        Map<Integer, Integer> grades = [0: 0, 1: 0, 2: 0, 3: 0]
        queries.each { Map query -> ((query.labels ?: []) as List<Map>).each { Map label -> grades[label.rel as int] = (grades[label.rel as int] ?: 0) + 1 } }
        int labelled = queries.count { !((it.labels ?: []) as List).isEmpty() }
        List<String> warnings = []
        if (queries.isEmpty()) {
            warnings << 'The gold set is empty.'
        } else {
            if (labelled < queries.size()) warnings << "${queries.size() - labelled} of ${queries.size()} questions have no grades (a question with no grades cannot be scored).".toString()
            if (labelled && grades[2] + grades[3] == 0) warnings << 'No result is graded 2 or 3, so recall cannot be measured (recall counts only grades 2 and 3).'
            if (queries.size() < 150) warnings << "The evaluation plan asks for at least 150 questions; this set has ${queries.size()}.".toString()
        }
        return [questions: queries.size(), labelled: labelled, unlabelled: queries.size() - labelled, grades: grades, warnings: warnings]
    }

    private static String snapshot(File evalDir, File goldFile) {
        File dir = historyDir(evalDir)
        dir.mkdirs()
        String stamp = DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss').withZone(ZoneOffset.UTC).format(Instant.now())
        File target = new File(dir, "gold-${stamp}.jsonl".toString())
        int counter = 1
        while (target.exists()) target = new File(dir, "gold-${stamp}-${++counter}.jsonl".toString())
        Files.copy(goldFile.toPath(), target.toPath())
        return target.name
    }

    private static void logChange(File evalDir, Map entry) {
        File dir = historyDir(evalDir)
        dir.mkdirs()
        new File(dir, 'log.jsonl').append(JsonOutput.toJson([time: Instant.now().toString()] + entry) + '\n', 'UTF-8')
    }
}
