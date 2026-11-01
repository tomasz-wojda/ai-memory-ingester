List<String> archives = ['chat:turns', 'worklog:chunks', 'worklog:state']
String datasetName = null
boolean verifyOnly = false
boolean rebuild = false
boolean encode = true
int batchSize = 50
boolean help = false
String workspaceValue = null

int argumentIndex = 0
while (argumentIndex < args.length) {
    String argument = args[argumentIndex]
    if (argument == '--verify') {
        verifyOnly = true
    } else if (argument == '--rebuild') {
        rebuild = true
    } else if (argument == '--no-encode') {
        encode = false
    } else if (argument == '--archives' && argumentIndex + 1 < args.length) {
        archives = args[++argumentIndex].tokenize(',').collect { it.trim() }.findAll { it }
    } else if (argument == '--batch' && argumentIndex + 1 < args.length) {
        batchSize = args[++argumentIndex].toInteger()
    } else if (argument == '--workspace-root' && argumentIndex + 1 < args.length) {
        workspaceValue = args[++argumentIndex]
    } else if (argument == '--dataset' && argumentIndex + 1 < args.length) {
        datasetName = args[++argumentIndex]
    } else if (argument == '--data-dir' && argumentIndex + 1 < args.length) {
        Config.setDataDir(args[++argumentIndex])
    } else if (argument.startsWith('--data-dir=')) {
        Config.setDataDir(argument.substring('--data-dir='.length()))
    } else if (argument == '--help' || argument == '-h') {
        help = true
    }
    argumentIndex++
}
if (help) {
    println "Usage: ai-memory-ingester reindex [options]"
    println "  --archives A,B   Archives to index (default chat:turns,worklog:chunks,worklog:state)"
    println "  --verify         Report documents that need indexing and orphans; change nothing"
    println "  --rebuild        Index every document again"
    println "  --no-encode      Store passages only; skip the vector encoding"
    println "  --batch N        Documents per call (default 50)"
    println "  --dataset NAME   Dataset (default: the active dataset)"
    println "Needs the memory service (ai-memory-service serve). Exit codes: 0 done, 1 failed or problems found, 3 service not running."
    return
}

long started = System.currentTimeMillis()
SemanticClient client = SemanticClient.discover(workspaceValue ? new File(workspaceValue) : null)
Map health = client.health()
if (!health.ok) {
    System.err.println("The memory service is not running (${health.reason}).")
    System.exit(3)
}
String dataset = datasetName ?: DatasetRegistry.getActiveDataset()
List<File> databases = DatasetRegistry.getDatabasesForDataset(dataset).findAll { Config.isSearchableDatabase(it.name) }
Set<String> live = [] as Set<String>
int scanned = 0
int skipped = 0
int upserted = 0
int passageCount = 0
int needed = 0

databases.each { File database ->
    MemoryEngine engine = new MemoryEngine(database.absolutePath)
    try {
        archives.each { String archive ->
            List<Map> batch = []
            Closure flush = {
                if (batch.isEmpty()) return
                List<Map> keys = batch.collect { [origin_db: it.origin_db, source_archive: it.source_archive, file_path: it.file_path, content_hash: it.content_hash] }
                List<Integer> missing = rebuild ? (0..<batch.size()).toList() : ((client.missingPassages(keys).body as Map)?.missing ?: []) as List<Integer>
                needed += missing.size()
                skipped += batch.size() - missing.size()
                if (!verifyOnly && missing) {
                    List<Map> documents = missing.collect { int index ->
                        Map doc = batch[index]
                        List<Map> passages = PassageBuilder.split(doc)
                        [origin_db: doc.origin_db, source_archive: doc.source_archive, file_path: doc.file_path, content_hash: doc.content_hash,
                         passages: passages.collect { [section: it.section, start: it.start, end: it.end, tokens: it.tokens, text: it.text] }]
                    }
                    Map reply = client.upsertPassages(documents)
                    if (!reply.ok) {
                        System.err.println("Upsert failed: ${reply.reason}")
                        System.exit(1)
                    }
                    upserted += documents.size()
                    passageCount += ((reply.body as Map).passages ?: 0) as int
                }
                batch.clear()
            }
            engine.eachDocument(archive) { Map doc ->
                Map entry = [origin_db: database.name, source_archive: doc.source_archive, file_path: doc.file_path,
                             content: doc.content, content_hash: PassageBuilder.contentHash(doc.content as String)]
                live << "${entry.origin_db}|${entry.source_archive}|${entry.file_path}".toString()
                scanned++
                batch << entry
                if (batch.size() >= batchSize) flush.call()
            }
            flush.call()
        }
    } finally {
        engine.close()
    }
}

Set<String> scopeArchives = archives as Set<String>
Set<String> scopeDatabases = databases.collect { it.name } as Set<String>
Map listing = client.listDocuments()
List<Map> orphans = listing.ok
    ? ((listing.body as Map).documents as List<Map>).findAll { Map d ->
        scopeArchives.contains(d.source_archive) && scopeDatabases.contains(d.origin_db) &&
            !live.contains("${d.origin_db}|${d.source_archive}|${d.file_path}".toString())
    }
    : []
int removed = 0
if (!verifyOnly && orphans) {
    removed = ((client.deletePassages(orphans.collect { [origin_db: it.origin_db, source_archive: it.source_archive, file_path: it.file_path] }).body as Map)?.removed ?: 0) as int
}
int encoded = 0
String encodeNote = 'not requested'
if (!verifyOnly && encode) {
    Map status = client.indexStatus()
    if (status.ok && (status.body as Map).late_model) {
        encodeNote = 'done'
        while (true) {
            Map reply = client.indexBatch(64)
            if (!reply.ok) {
                encodeNote = "stopped (${reply.reason})"
                break
            }
            encoded += ((reply.body as Map).encoded ?: 0) as int
            if (((reply.body as Map).remaining ?: 0) as int == 0 || ((reply.body as Map).encoded ?: 0) as int == 0) break
        }
    } else {
        encodeNote = 'skipped (no late-interaction model)'
    }
}
println "REINDEX\tmode=${verifyOnly ? 'verify' : (rebuild ? 'rebuild' : 'update')}\tscanned=${scanned}\tneeded=${needed}\tskipped=${skipped}\tdocuments=${upserted}\tpassages=${passageCount}\torphans=${orphans.size()}\tremoved=${removed}\tencoded=${encoded}\tencode=${encodeNote}\telapsed_ms=${System.currentTimeMillis() - started}"
if (verifyOnly && (needed > 0 || orphans)) System.exit(1)
