@Grab('org.xerial:sqlite-jdbc:3.45.1.0')
@Grab('org.slf4j:slf4j-nop:2.0.12')

class TestQueryPlanSuite {

    private static int passed = 0
    private static int failed = 0
    private static final List<String> failures = []
    private static GroovyClassLoader loader
    private static Class TextFold
    private static Class StopWords
    private static Class QueryPlanner
    private static Class MemoryRetrieval
    private static Class EvalRunner
    private static Class EvalMetrics
    private static Class GoldSet
    private static Class SemanticStore
    private static Class Config

    static void main(String[] args) {
        initialize()
        File root = new File(System.getProperty('java.io.tmpdir'), "query-plan-suite-${System.nanoTime()}")
        root.mkdirs()
        try {
            testTextFold()
            testStopWords()
            testPlanner()
            Map fixture = loader.loadClass('SemanticFixtureBuilder').build(root, loader.loadClass('MemoryEngine')) as Map
            File data = fixture.data as File
            Config.setDataDir(data.absolutePath)
            List<Map> queries = GoldSet.load(new File('tests/fixtures/semantic/queries.jsonl')) as List<Map>
            testRetrieval(data)
            testEvaluation(data, queries)
            testGoldSet(root, queries)
            testSemanticStore(root)
            testCommandLine(data)
        } finally {
            root.deleteDir()
        }
        println "Query plan tests: ${passed} passed, ${failed} failed"
        failures.each { println "  - ${it}" }
        if (failed > 0) System.exit(1)
    }

    private static void testTextFold() {
        verify('ascii folds Polish letters including ł', TextFold.ascii('Użytkowników Łódź Źdźbło') == 'uzytkownikow lodz zdzblo')
        verify('ascii keeps plain text', TextFold.ascii('Traefik 2.0') == 'traefik 2.0')
        verify('one l gives one variant', TextFold.polishLVariants('bylo') == ['było'])
        verify('two l letters give three variants', TextFold.polishLVariants('lalka') as Set == ['łalka', 'lałka', 'łałka'] as Set)
        verify('three l letters give none', TextFold.polishLVariants('lollipop').isEmpty() && TextFold.polishLVariants('llll').isEmpty())
        verify('a word without l gives none', TextFold.polishLVariants('decyzja').isEmpty())
    }

    private static void testStopWords() {
        verify('english stop word', StopWords.isStopWord('What') && StopWords.isStopWord('about'))
        verify('polish stop word', StopWords.isStopWord('jak') && StopWords.isStopWord('dlaczego'))
        verify('polish stop word with and without diacritics', StopWords.isStopWord('jeśli') && StopWords.isStopWord('jesli'))
        verify('technical words stay', !StopWords.isStopWord('pod') && !StopWords.isStopWord('go') && !StopWords.isStopWord('traefik'))
    }

    private static void testPlanner() {
        Map question = QueryPlanner.plan('what did we decide about the traefik attribute') as Map
        verify('question is not explicit', question.explicit == false)
        verify('stop words are removed', question.stop_words_removed == ['what', 'did', 'we', 'about', 'the'])
        verify('relaxed form ORs the content words with prefixes', question.relaxed == 'decide OR deci* OR traefik OR trae* OR attribute OR attrib*')
        verify('strict form is the text as typed', question.strict == 'what did we decide about the traefik attribute')
        verify('english is guessed', question.language_guess == 'en')

        Map polish = QueryPlanner.plan('jak rozwiazalismy problem z traefik') as Map
        verify('polish is guessed from stop words', polish.language_guess == 'pl')
        verify('polish filler is removed', polish.stop_words_removed == ['jak', 'rozwiazalismy', 'z'])
        verify('polish ł variant is added for a polish query', (polish.relaxed as String).contains('probłem'))

        Map english = QueryPlanner.plan('script removing users eap pod') as Map
        verify('pod is not a stop word', (english.relaxed as String).contains('eap') && english.language_guess == 'en')
        verify('no ł variants for an english query', !(QueryPlanner.plan('collect the logs') as Map).relaxed.toString().contains('ł'))

        verify('operator query is explicit', QueryPlanner.plan('bunyan AND pin*').explicit == true && QueryPlanner.plan('bunyan AND pin*').relaxed == null)
        verify('phrase query is explicit', QueryPlanner.plan('"exact phrase" thing').explicit == true)
        verify('prefix term is explicit', QueryPlanner.plan('retent*').explicit == true)
        verify('column filter is explicit', QueryPlanner.plan('file_path:kd11 foo').explicit == true)
        verify('parentheses are explicit', QueryPlanner.plan('kd11 AND (x OR y)').explicit == true)
        verify('lower-case or is a normal word', QueryPlanner.plan('this or that setup').explicit == false)

        Map punctuated = QueryPlanner.plan('kube-core/uat.values.yaml pinned?') as Map
        verify('punctuated term is quoted', (punctuated.relaxed as String).startsWith('"kube-core/uat.values.yaml" OR pinned'))
        verify('trailing question mark is stripped', !(punctuated.relaxed as String).contains('?'))

        Map acronym = QueryPlanner.plan('SA token') as Map
        verify('upper-case acronym is kept', (acronym.relaxed as String).startsWith('sa OR token'))
        Map onlyStops = QueryPlanner.plan('what is the') as Map
        verify('a query of only stop words keeps its words', onlyStops.relaxed != null && onlyStops.stop_words_removed == [])
        verify('empty query has no relaxed form', QueryPlanner.plan('').relaxed == null)

        String many = (1..80).collect { "word${it}x" }.join(' ')
        verify('relaxed form is capped', (QueryPlanner.plan(many).relaxed as String).split(' OR ').length <= QueryPlanner.MAX_TERMS)
    }

    private static Map search(File data, String query, Map options = [:]) {
        return MemoryRetrieval.searchTopic(data, 'worklog-chat', query, 20, options) as Map
    }

    private static List<String> ids(Map result) {
        return (result.results as List<Map>).collect { Map row -> "${row.origin_db}:${row.source_archive}:${row.file_path}".toString() }
    }

    private static void testRetrieval(File data) {
        String question = 'what did we decide about the nginx timeout'
        Map strictOnly = search(data, question, [relax_mode: 'off'])
        verify('strict query finds nothing for a question', (strictOnly.results as List).isEmpty())
        verify('plan says strict when relax is off', (strictOnly.query_plan as Map).form_used == 'strict')

        Map relaxed = search(data, question)
        verify('relaxed query finds results for the same question', !(relaxed.results as List).isEmpty())
        verify('plan says relaxed', (relaxed.query_plan as Map).form_used == 'relaxed' && (relaxed.query_plan as Map).relaxed_used == true)
        verify('relaxed rows carry the bm25_relaxed signal', (relaxed.results as List<Map>).any { it.signals?.containsKey('bm25_relaxed') })
        verify('the decision chunk is in the first five', ids(relaxed).take(5).any { it.endsWith('fix101/decisions/0001-decisions.md') })

        Map keyword = search(data, 'nginx timeout')
        Map keywordOff = search(data, 'nginx timeout', [relax_mode: 'off'])
        verify('a keyword query that works is unchanged', ids(keyword) == ids(keywordOff) && (keyword.query_plan as Map).relaxed_used == false)

        Map forced = search(data, 'nginx timeout', [relax_mode: 'on'])
        verify('relax on adds the relaxed pool even when strict has rows', (forced.query_plan as Map).relaxed_used == true)

        ['nginx OR certificate', '"backup retention"', 'retent*', 'FIX-101 OR FIX-102'].each { String explicit ->
            Map result = search(data, explicit)
            verify("explicit query is never relaxed: ${explicit}", (result.query_plan as Map)?.relaxed_used != true)
        }

        System.setProperty('ai.memory.relax', '0')
        try {
            Map killed = search(data, question)
            verify('kill switch turns the relaxed query off', (killed.results as List).isEmpty() && !Config.relaxEnabled())
        } finally {
            System.clearProperty('ai.memory.relax')
        }
        verify('kill switch is off by default', Config.relaxEnabled())

        Map polish = search(data, 'decyzja dotyczaca usuwania uzytkownikow')
        verify('polish question without diacritics finds the decision', ids(polish).take(5).any { it.endsWith('fix104/decisions/0001-decisions.md') || it.contains('fix104') })
        Map letterL = search(data, 'jak rozwiazalismy problem z timeoutem')
        verify('polish question finds the polish turn', ids(letterL).take(5).any { it.contains('fix101') })

        Map ticketOnly = search(data, 'FIX-103')
        verify('a bare ticket key through searchTopic returns that ticket, state first', ids(ticketOnly).first().endsWith('worklog:state:fix103.md') && ids(ticketOnly).every { !it.contains('noticket') })

        Map first = search(data, question)
        Map second = search(data, question)
        verify('the relaxed result is deterministic', ids(first) == ids(second))
    }

    private static void testEvaluation(File data, List<Map> queries) {
        Map s0 = EvalRunner.runSystem(data, 'worklog-chat', queries, 'S0') as Map
        Map s1 = EvalRunner.runSystem(data, 'worklog-chat', queries, 'S1') as Map
        Map q0 = (s0.by_class as Map).Q as Map
        Map q1 = (s1.by_class as Map).Q as Map
        verify('baseline returns nothing for most questions', (q0.zero_result as double) >= 0.75d)
        verify('relaxed system returns something for every question', (q1.zero_result as double) == 0.0d)
        verify('relaxed recall at 50 on questions is at least 0.85', (q1.recall_50 as double) >= 0.85d)
        verify('relaxed ndcg on questions beats baseline', (q1.ndcg_10 as double) > (q0.ndcg_10 as double) + 0.3d)
        Map k0 = (s0.by_class as Map).K as Map
        Map k1 = (s1.by_class as Map).K as Map
        verify('keyword class does not change', k0.ndcg_10 == k1.ndcg_10 && k0.recall_50 == k1.recall_50)
        Map x0 = (s0.by_class as Map).X as Map
        Map x1 = (s1.by_class as Map).X as Map
        verify('explicit class does not change', x0.ndcg_10 == x1.ndcg_10 && x0.zero_result == x1.zero_result)
        Map t0 = (s0.by_class as Map).T as Map
        Map t1 = (s1.by_class as Map).T as Map
        verify('ticket class does not get worse', (t1.ndcg_10 as double) >= (t0.ndcg_10 as double))
        verify('per language groups are present', (s1.by_language as Map).keySet().containsAll(['en', 'pl-ascii']))
        List<Map> comparison = EvalRunner.compare([s0, s1]) as List<Map>
        verify('comparison shows a positive ndcg difference', ((comparison[0].ndcg_10 as Map).mean_diff as double) > 0.0d)
        verify('comparison shows fewer zero results', ((comparison[0].zero_result as Map).mean_diff as double) < 0.0d)

        Map asOf = EvalRunner.runSystem(data, 'worklog-chat', queries, 'S1', 1L) as Map
        verify('the as-of filter removes turns above the sequence', (asOf.aggregate as Map).recall_50 != null)

        List<Map> pool = EvalRunner.pool(data, 'worklog-chat', queries.take(3), ['S0', 'S1'], 5) as List<Map>
        verify('pool export lists unlabelled documents with their systems', !pool.isEmpty() && pool.every { it.rel == null && it.systems })
    }

    private static void testGoldSet(File root, List<Map> queries) {
        verify('the fixture queries are valid', GoldSet.validate(queries).isEmpty())
        List<Map> broken = [[qid: 'a', 'class': 'Z', lang: 'en', query: '', split: 'dev', labels: [[doc: 'x', rel: 7]]], [qid: 'a', 'class': 'K', lang: 'xx', query: 'q', split: 'maybe', labels: 'no']]
        List<String> problems = GoldSet.validate(broken) as List<String>
        verify('validation finds the problems', problems.size() >= 7)

        File state = new File(root, 'state')
        state.mkdirs()
        new File(state, 'AB-1.json').setText('{"ticket_key":"AB-1","decisions":[{"id":"D1","description":"Use option A for the production run with the pod flagged","status":"resolved"}],"blockers":[{"description":"PR not merged yet in the images","status":"active"}],"next_action":"Check the merged scripts on Monday and run the dry-run"}', 'UTF-8')
        new File(state, 'AB-2.json').setText('{"ticket_key":"AB-2","decisions":[{"id":"D1","description":"Najpierw tryb próbny na UAT, potem produkcja","status":"open"}]}', 'UTF-8')
        new File(state, 'broken.json').setText('not json', 'UTF-8')
        List<Map> candidates = GoldSet.candidatesFromState(state) as List<Map>
        verify('candidates give a question and a keyword form for each item', candidates.count { it['class'] == 'Q' } == 4 && candidates.count { it['class'] == 'K' } == 4)
        verify('candidates have no labels', candidates.every { it.labels == [] })
        verify('polish text gets polish wording', candidates.any { it.lang == 'pl' && it.query.toString().startsWith('Co ustaliliśmy w sprawie') })
        verify('english decision wording', candidates.any { it.query == 'What did we decide about Use option A for the production run with the pod?' })
        verify('split is stable', GoldSet.splitFor('AB-1') == GoldSet.splitFor('AB-1') && GoldSet.splitFor('AB-1') in ['dev', 'test'])
        verify('both forms of an item share the split', candidates.groupBy { it.source.ticket }.every { String t, List<Map> items -> items*.split.unique().size() == 1 })
        File out = new File(root, 'eval/candidates.jsonl')
        GoldSet.write(candidates, out)
        verify('written candidates can be loaded again', GoldSet.load(out).size() == candidates.size())
    }

    private static void testSemanticStore(File root) {
        File data = new File(root, 'store-data')
        SemanticStore.saveRun(data, [run_id: 'r1', config: [systems: ['S0']], metrics: [x: 1], per_query: [[qid: 'a']]])
        SemanticStore.saveRun(data, [run_id: 'r1', config: [systems: ['S0']], metrics: [x: 2], per_query: [[qid: 'a']]])
        verify('run is stored in semantic.db', SemanticStore.path(data).isFile())
        def driver = loader.loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
        groovy.sql.Sql sql = new groovy.sql.Sql(driver.connect("jdbc:sqlite:${SemanticStore.path(data).absolutePath}", new Properties()))
        try {
            verify('saving the same run id replaces it', sql.firstRow('select count(*) c from eval_runs').c == 1)
        } finally {
            sql.close()
        }
    }

    private static Map command(List<String> arguments, Map<String, String> environment = [:]) {
        List<String> full = ['groovy', 'run.groovy'] + arguments
        ProcessBuilder builder = new ProcessBuilder(full).directory(new File('.').canonicalFile)
        builder.environment().putAll(environment)
        Process process = builder.start()
        StringBuffer out = new StringBuffer()
        StringBuffer err = new StringBuffer()
        process.consumeProcessOutput(out, err)
        int code = process.waitFor()
        return [code: code, out: out.toString(), err: err.toString()]
    }

    private static void testCommandLine(File data) {
        String gold = 'tests/fixtures/semantic/queries.jsonl'
        Map validate = command(['eval', '--validate', '--set', gold, '--data-dir', data.absolutePath])
        verify('eval --validate accepts the fixture queries', validate.code == 0 && validate.out.contains('GOLD_VALID\tqueries=24\tproblems=0'))

        Map run = command(['eval', '--set', gold, '--systems', 'S0,S1', '--data-dir', data.absolutePath])
        verify('eval run prints both systems and a comparison', run.code == 0 && run.out.contains('SYSTEM\tS0') && run.out.contains('SYSTEM\tS1') && run.out.contains('COMPARE\tS1 against S0'))
        verify('eval run stores the run in semantic.db', SemanticStore.path(data).isFile())

        Map explain = command(['query', 'what did we decide about the nginx timeout', '--explain', '--limit', '3', '--data-dir', data.absolutePath])
        verify('query --explain prints the plan and finds results', explain.code == 0 && explain.out.contains('Query plan: form=relaxed') && explain.out.contains('bm25_relaxed'))

        Map off = command(['query', 'what did we decide about the nginx timeout', '--relax', 'off', '--data-dir', data.absolutePath])
        verify('query --relax off keeps the old behavior', off.code == 0 && off.out.contains('No results found'))

        Map killed = command(['query', 'what did we decide about the nginx timeout', '--data-dir', data.absolutePath], [AI_MEMORY_RELAX: '0'])
        verify('AI_MEMORY_RELAX=0 keeps the old behavior', killed.code == 0 && killed.out.contains('No results found'))

        Map badFlag = command(['query', 'nginx', '--relax', 'maybe', '--data-dir', data.absolutePath])
        verify('a bad --relax value is refused', badFlag.code == 2 && badFlag.out.contains('--relax needs auto, on or off'))
    }

    private static void initialize() {
        loader = new GroovyClassLoader(TestQueryPlanSuite.classLoader)
        Thread.currentThread().contextClassLoader = loader
        ['Config', 'TextFold', 'StopWords', 'QueryPlanner', 'TicketKey', 'MemoryEngine', 'DatasetRegistry', 'TicketFreshness', 'TicketRetrieval',
         'SignalFusion', 'NeighborExpansion', 'ContextPacker', 'MemoryRetrieval', 'FederatedEngine', 'EvalMetrics', 'GoldSet',
         'SemanticStore', 'EvalRunner'].each { String name -> loader.parseClass(new File("lib/${name}.groovy")) }
        loader.parseClass(new File('tests/SemanticFixtureBuilder.groovy'))
        TextFold = loader.loadClass('TextFold')
        StopWords = loader.loadClass('StopWords')
        QueryPlanner = loader.loadClass('QueryPlanner')
        MemoryRetrieval = loader.loadClass('MemoryRetrieval')
        EvalRunner = loader.loadClass('EvalRunner')
        EvalMetrics = loader.loadClass('EvalMetrics')
        GoldSet = loader.loadClass('GoldSet')
        SemanticStore = loader.loadClass('SemanticStore')
        Config = loader.loadClass('Config')
    }

    private static void verify(String name, boolean condition) {
        if (condition) {
            passed++
        } else {
            failed++
            failures << name
        }
    }
}
