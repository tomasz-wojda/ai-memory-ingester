import groovy.json.JsonOutput

String mode = 'run'
String setFile = null
String outFile = null
String stateDir = null
String datasetName = null
List<String> systems = ['S0', 'S1']
long asOfSequence = 0L
int depth = 20
boolean jsonOutput = false
List<String> importFiles = []
boolean replace = false
String restoreName = null
String exportFile = null

int argumentIndex = 0
while (argumentIndex < args.length) {
    String argument = args[argumentIndex]
    if (argument == '--validate') {
        mode = 'validate'
    } else if (argument == '--gold-candidates') {
        mode = 'candidates'
    } else if (argument == '--pool-export') {
        mode = 'pool'
    } else if (argument == '--run') {
        mode = 'run'
    } else if (argument == '--gold-status') {
        mode = 'gold-status'
    } else if (argument == '--gold-enable') {
        mode = 'gold-enable'
    } else if (argument == '--gold-disable') {
        mode = 'gold-disable'
    } else if (argument == '--gold-history') {
        mode = 'gold-history'
    } else if (argument == '--gold-import' && argumentIndex + 1 < args.length) {
        mode = 'gold-import'
        importFiles << args[++argumentIndex]
    } else if (argument == '--gold-restore' && argumentIndex + 1 < args.length) {
        mode = 'gold-restore'
        restoreName = args[++argumentIndex]
    } else if (argument == '--gold-export' && argumentIndex + 1 < args.length) {
        mode = 'gold-export'
        exportFile = args[++argumentIndex]
    } else if (argument == '--replace') {
        replace = true
    } else if (argument == '--json') {
        jsonOutput = true
    } else if (argument == '--set' && argumentIndex + 1 < args.length) {
        setFile = args[++argumentIndex]
    } else if (argument == '--out' && argumentIndex + 1 < args.length) {
        outFile = args[++argumentIndex]
    } else if (argument == '--state-dir' && argumentIndex + 1 < args.length) {
        stateDir = args[++argumentIndex]
    } else if (argument == '--dataset' && argumentIndex + 1 < args.length) {
        datasetName = args[++argumentIndex]
    } else if (argument == '--systems' && argumentIndex + 1 < args.length) {
        systems = args[++argumentIndex].tokenize(',').collect { it.trim().toUpperCase() }
    } else if (argument == '--as-of-sequence' && argumentIndex + 1 < args.length) {
        asOfSequence = args[++argumentIndex].toLong()
    } else if (argument == '--depth' && argumentIndex + 1 < args.length) {
        depth = args[++argumentIndex].toInteger()
    } else if (argument == '--data-dir' && argumentIndex + 1 < args.length) {
        Config.setDataDir(args[++argumentIndex])
    } else if (argument.startsWith('--data-dir=')) {
        Config.setDataDir(argument.substring('--data-dir='.length()))
    } else if (argument == '--help' || argument == '-h') {
        mode = 'help'
    }
    argumentIndex++
}

File repositoryRoot = new File('.').canonicalFile
File workspaceRoot = Config.workspaceRoot(null, repositoryRoot)
File evalDir = new File(Config.DATA_DIR, 'eval')
File gold = new File(setFile ?: new File(evalDir, 'gold.jsonl').path)

if (mode == 'help') {
    printUsage()
    return
}
if (mode == 'candidates') {
    File states = new File(stateDir ?: new File(workspaceRoot, '.ai-worklog/state').path)
    File target = new File(outFile ?: new File(evalDir, 'candidates.jsonl').path)
    List<Map> candidates = GoldSet.candidatesFromState(states)
    GoldSet.write(candidates, target)
    println "GOLD_CANDIDATES\tqueries=${candidates.size()}\tout=${target.path}\tstate_dir=${states.path}"
    return
}
if (mode.startsWith('gold-')) {
    if (mode == 'gold-status') {
        Map status = GoldSet.status(gold)
        println "GOLD_STATUS\tenabled=${GoldSet.enabled(evalDir)}\tsource=${GoldSet.enabledSource(evalDir)}\tquestions=${status.questions}\tlabelled=${status.labelled}\tgrades=${(status.grades as Map).collect { k, v -> "${k}:${v}" }.join(',')}\tfile=${gold.path}"
        (status.warnings as List<String>).each { println "GOLD_WARNING\t${it}" }
    } else if (mode == 'gold-enable' || mode == 'gold-disable') {
        GoldSet.setEnabled(evalDir, mode == 'gold-enable')
        println "GOLD_FLAG\tenabled=${mode == 'gold-enable'}\tstate_file=${GoldSet.stateFile(evalDir).path}"
    } else if (mode == 'gold-import') {
        try {
            Map result = GoldSet.importFiles(evalDir, gold, importFiles.collect { new File(it) }, replace)
            println "GOLD_IMPORT\tadded=${result.added}\tupdated=${result.updated}\tunchanged=${result.unchanged}\tkept=${result.kept}\tremoved=${result.removed}\tsnapshot=${result.snapshot ?: 'none (no change)'}\tfile=${gold.path}"
            if (!GoldSet.enabled(evalDir)) println "GOLD_NOTE\tThe gold set is switched off. It is not used until you run: eval --gold-enable"
        } catch (IllegalArgumentException error) {
            System.err.println("Import refused: ${error.message}")
            System.exit(2)
        }
    } else if (mode == 'gold-history') {
        List<String> names = GoldSet.history(evalDir)
        names.each { println "GOLD_SNAPSHOT\t${it}" }
        println "GOLD_HISTORY\tsnapshots=${names.size()}\tfolder=${GoldSet.historyDir(evalDir).path}"
    } else if (mode == 'gold-restore') {
        try {
            String replaced = GoldSet.restore(evalDir, gold, restoreName)
            println "GOLD_RESTORE\trestored=${restoreName}\tprevious_version_kept_as=${replaced ?: 'none (same content)'}"
        } catch (IllegalArgumentException error) {
            System.err.println("Restore refused: ${error.message}")
            System.exit(2)
        }
    } else if (mode == 'gold-export') {
        if (!gold.isFile()) {
            System.err.println("Gold set not found: ${gold.path}")
            System.exit(2)
        }
        File target = new File(exportFile)
        target.parentFile?.mkdirs()
        target.setText(gold.getText('UTF-8'), 'UTF-8')
        println "GOLD_EXPORT\tout=${target.path}"
    }
    return
}
if ((mode == 'pool' || mode == 'run') && setFile == null && !GoldSet.enabled(evalDir)) {
    System.err.println("The gold set is switched off (feature flag), so it is not used.")
    System.err.println("Switch it on with: eval --gold-enable   (or set AI_MEMORY_GOLD=1). A set given with --set is always used.")
    System.exit(4)
}
if (!gold.isFile()) {
    System.err.println("Gold set not found: ${gold.path}")
    System.exit(2)
}
List<Map> queries = GoldSet.load(gold)
List<String> problems = GoldSet.validate(queries)
if (mode == 'validate') {
    problems.each { println "GOLD_PROBLEM\t${it}" }
    println "GOLD_VALID\tqueries=${queries.size()}\tproblems=${problems.size()}\tlabelled=${queries.count { !(it.labels as List).isEmpty() }}"
    if (problems) System.exit(1)
    return
}
if (problems) {
    problems.take(10).each { System.err.println("GOLD_PROBLEM\t${it}") }
    System.exit(1)
}
String dataset = datasetName ?: DatasetRegistry.getActiveDataset()
unknownSystems = systems.findAll { !EvalRunner.SYSTEMS.containsKey(it) }
if (unknownSystems) {
    System.err.println("Unknown system: ${unknownSystems}. Known: ${EvalRunner.SYSTEMS.keySet()}")
    System.exit(2)
}
if (mode == 'pool') {
    File target = new File(outFile ?: new File(evalDir, 'pool.jsonl').path)
    List<Map> rows = EvalRunner.pool(Config.DATA_DIR, dataset, queries, systems, depth, asOfSequence)
    GoldSet.write(rows, target)
    println "POOL_EXPORT\tqueries=${queries.size()}\trows=${rows.size()}\tout=${target.path}"
    return
}

List<Map> runs = systems.collect { String id -> EvalRunner.runSystem(Config.DATA_DIR, dataset, queries, id, asOfSequence) }
List<Map> comparison = EvalRunner.compare(runs)
String runId = "eval-${new Date().format('yyyyMMdd-HHmmss')}"
SemanticStore.saveRun(Config.DATA_DIR, [
    run_id: runId,
    config: [systems: systems, dataset: dataset, as_of_sequence: asOfSequence, gold: gold.name, queries: queries.size()],
    metrics: [systems: runs.collectEntries { [(it.system): [aggregate: it.aggregate, by_class: it.by_class, by_language: it.by_language]] }, comparison: comparison],
    per_query: runs.collectMany { Map run -> (run.per_query as List<Map>).collect { it + [system: run.system] } }
])
if (jsonOutput) {
    println JsonOutput.prettyPrint(JsonOutput.toJson([run_id: runId, systems: runs.collect { it - [per_query: null] }, comparison: comparison]))
    return
}
println "EVAL_RUN\t${runId}\tqueries=${queries.size()}\tdataset=${dataset}\tas_of_sequence=${asOfSequence}"
runs.each { Map run ->
    println "SYSTEM\t${run.system}\t${run.description}\telapsed_ms=${Math.round(run.elapsed_ms as double)}\tdegraded_queries=${run.degraded_queries}"
    printRow('all', run.aggregate as Map)
    (run.by_class as Map).each { String name, Map metrics -> printRow("class ${name}", metrics) }
    (run.by_language as Map).each { String name, Map metrics -> printRow("lang ${name}", metrics) }
}
comparison.each { Map item ->
    println "COMPARE\t${item.system} against ${item.against}"
    ['ndcg_10', 'recall_50', 'zero_result'].each { String metric ->
        Map diff = item[metric] as Map
        println "  ${metric}\tdiff=${fmt(diff.mean_diff)}\tci95=[${fmt(diff.ci_low)}, ${fmt(diff.ci_high)}]\tpairs=${diff.pairs}"
    }
}

static void printRow(String label, Map metrics) {
    println "  ${label.padRight(14)} n=${metrics.queries}\tzero=${fmt(metrics.zero_result)}\trecall@10=${fmt(metrics.recall_10)}\trecall@50=${fmt(metrics.recall_50)}\tndcg@10=${fmt(metrics.ndcg_10)}\tmrr@10=${fmt(metrics.mrr_10)}\tsuccess@1=${fmt(metrics.success_1)}"
}

static String fmt(Object value) {
    return value == null ? 'n/a' : String.format('%.3f', value as double)
}

static void printUsage() {
    println "Usage: ai-memory-ingester eval [options]"
    println ""
    println "  --run                       Run the gold set against systems (default)"
    println "  --systems S0,S1             Systems to compare; the first is the reference"
    println "                              S0 strict query only, S1 strict then relaxed query,"
    println "                              S2 S1 plus re-rank, S5 S2 plus the late pool, S6 late re-score (S2, S5 and S6 need the service)"
    println "  --set FILE                  Gold set, JSON lines (default data/eval/gold.jsonl)"
    println "  --as-of-sequence N          Ignore turns with a higher sequence number"
    println "  --dataset NAME              Dataset (default: the active dataset)"
    println "  --json                      JSON output"
    println "  --validate                  Check the gold set file"
    println "  --gold-status               Show the feature flag, the counts and the grades of the gold set, with warnings"
    println "  --gold-enable | --gold-disable   Switch the default gold set on or off (default: off; AI_MEMORY_GOLD=1 or 0 overrides)"
    println "  --gold-import FILE          Merge a file in the gold set format (repeat for more files; --replace drops other queries)"
    println "  --gold-history              List the snapshots kept before each change"
    println "  --gold-restore NAME         Put a snapshot back (the current version is kept first)"
    println "  --gold-export FILE          Copy the gold set to FILE, to edit it and import it again"
    println "  --gold-candidates           Create unlabelled queries from ticket state files"
    println "      --state-dir DIR         State folder (default .ai-worklog/state)"
    println "      --out FILE              Output (default data/eval/candidates.jsonl)"
    println "  --pool-export               Export the top results of each system for labelling"
    println "      --depth N               Results per system (default 20)"
    println "      --out FILE              Output (default data/eval/pool.jsonl)"
    println ""
    println "The gold set stays out of git (data/eval/). Runs are stored in data/semantic.db."
}
