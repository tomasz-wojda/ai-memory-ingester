import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

String mode = 'record'
String observationId = null
Long sequenceFrom = null
Long sequenceTo = null
Integer tailCount = null
String literalQuery = null
String regexQuery = null
String exportSource = null
Integer exportTail = null
String parityPath = null
String checkpointMode = 'TRUNCATE'
boolean jsonOutput = false
File journalPath = Config.journalDbPath()

int argumentIndex = 0
while (argumentIndex < args.length) {
    String argument = args[argumentIndex]
    if (argument == '--get' && argumentIndex + 1 < args.length) {
        mode = 'get'
        observationId = args[++argumentIndex]
    } else if (argument.startsWith('--get=')) {
        mode = 'get'
        observationId = argument.substring('--get='.length())
    } else if (argument == '--sequence-range' && argumentIndex + 2 < args.length) {
        mode = 'sequence-range'
        sequenceFrom = args[++argumentIndex].toLong()
        sequenceTo = args[++argumentIndex].toLong()
    } else if (argument.startsWith('--sequence-range=')) {
        mode = 'sequence-range'
        List<String> parts = argument.substring('--sequence-range='.length()).split(':')
        sequenceFrom = parts[0].toLong()
        sequenceTo = parts.size() > 1 ? parts[1].toLong() : parts[0].toLong()
    } else if (argument == '--tail' && argumentIndex + 1 < args.length) {
        mode = 'tail'
        tailCount = args[++argumentIndex].toInteger()
    } else if (argument.startsWith('--tail=')) {
        mode = 'tail'
        tailCount = argument.substring('--tail='.length()).toInteger()
    } else if (argument == '--literal' && argumentIndex + 1 < args.length) {
        mode = 'literal'
        literalQuery = args[++argumentIndex]
    } else if (argument.startsWith('--literal=')) {
        mode = 'literal'
        literalQuery = argument.substring('--literal='.length())
    } else if (argument == '--regex' && argumentIndex + 1 < args.length) {
        mode = 'regex'
        regexQuery = args[++argumentIndex]
    } else if (argument.startsWith('--regex=')) {
        mode = 'regex'
        regexQuery = argument.substring('--regex='.length())
    } else if (argument == '--sources') {
        mode = 'sources'
    } else if (argument == '--integrity') {
        mode = 'integrity'
    } else if (argument == '--checkpoint') {
        mode = 'checkpoint'
    } else if (argument.startsWith('--checkpoint=')) {
        mode = 'checkpoint'
        checkpointMode = argument.substring('--checkpoint='.length())
    } else if (argument == '--export') {
        mode = 'export'
    } else if (argument == '--parity' && argumentIndex + 1 < args.length) {
        mode = 'parity'
        parityPath = args[++argumentIndex]
    } else if (argument.startsWith('--parity=')) {
        mode = 'parity'
        parityPath = argument.substring('--parity='.length())
    } else if (argument == '--source' && argumentIndex + 1 < args.length) {
        exportSource = args[++argumentIndex]
    } else if (argument.startsWith('--source=')) {
        exportSource = argument.substring('--source='.length())
    } else if (argument == '--export-tail' && argumentIndex + 1 < args.length) {
        exportTail = args[++argumentIndex].toInteger()
    } else if (argument.startsWith('--export-tail=')) {
        exportTail = argument.substring('--export-tail='.length()).toInteger()
    } else if (argument == '--json') {
        jsonOutput = true
    } else if (argument == '--journal-db' && argumentIndex + 1 < args.length) {
        journalPath = new File(args[++argumentIndex]).canonicalFile
    } else if (argument.startsWith('--journal-db=')) {
        journalPath = new File(argument.substring('--journal-db='.length())).canonicalFile
    } else if (argument == '--data-dir' && argumentIndex + 1 < args.length) {
        Config.setDataDir(args[++argumentIndex])
        journalPath = Config.journalDbPath()
    } else if (argument.startsWith('--data-dir=')) {
        Config.setDataDir(argument.substring('--data-dir='.length()))
        journalPath = Config.journalDbPath()
    }
    argumentIndex++
}

JournalStore store = new JournalStore(journalPath)
try {
    switch (mode) {
        case 'record':
            String stdin = System.in.text
            if (!stdin?.trim()) {
                System.err.println('record-event requires JSON on stdin')
                System.exit(2)
            }
            Map payload = JournalEvent.parseJson(stdin)
            Map result = store.record(payload)
            emit(jsonOutput, result)
            println jsonOutput ? JsonOutput.prettyPrint(JsonOutput.toJson(result)) : formatRecordResult(result)
            System.exit(0)
            break
        case 'get':
            Map observation = store.getObservation(observationId)
            if (!observation) {
                System.err.println("Observation not found: ${observationId}")
                System.exit(1)
            }
            emit(jsonOutput, observation)
            println jsonOutput ? JsonOutput.prettyPrint(JsonOutput.toJson(observation)) : formatObservation(observation)
            break
        case 'sequence-range':
            List<Map> range = store.sequenceRange(sequenceFrom, sequenceTo)
            emit(jsonOutput, [from: sequenceFrom, to: sequenceTo, count: range.size(), observations: range])
            if (jsonOutput) {
                println JsonOutput.prettyPrint(JsonOutput.toJson([from: sequenceFrom, to: sequenceTo, count: range.size(), observations: range]))
            } else {
                range.each { println formatObservation(it) + '\n---' }
            }
            break
        case 'tail':
            List<Map> tail = store.tail(tailCount ?: 20)
            emit(jsonOutput, [count: tail.size(), observations: tail])
            if (jsonOutput) {
                println JsonOutput.prettyPrint(JsonOutput.toJson([count: tail.size(), observations: tail]))
            } else {
                tail.each { println formatObservation(it) }
            }
            break
        case 'literal':
            List<Map> literalMatches = store.searchLiteral(literalQuery)
            emit(jsonOutput, [query: literalQuery, count: literalMatches.size(), observations: literalMatches])
            if (jsonOutput) {
                println JsonOutput.prettyPrint(JsonOutput.toJson([query: literalQuery, count: literalMatches.size(), observations: literalMatches]))
            } else {
                literalMatches.each { println formatObservation(it) }
            }
            break
        case 'regex':
            List<Map> regexMatches = store.searchRegex(regexQuery)
            emit(jsonOutput, [pattern: regexQuery, count: regexMatches.size(), observations: regexMatches])
            if (jsonOutput) {
                println JsonOutput.prettyPrint(JsonOutput.toJson([pattern: regexQuery, count: regexMatches.size(), observations: regexMatches]))
            } else {
                regexMatches.each { println formatObservation(it) }
            }
            break
        case 'sources':
            List<Map> sources = store.listSources()
            emit(jsonOutput, [count: sources.size(), sources: sources])
            if (jsonOutput) {
                println JsonOutput.prettyPrint(JsonOutput.toJson([count: sources.size(), sources: sources]))
            } else {
                sources.each { Map source ->
                    println "${source.source_identity}\t${source.source_ide}\t${source.source_kind}\tcount=${source.observation_count}"
                }
            }
            break
        case 'integrity':
            Map integrity = store.verifyIntegrity()
            emit(jsonOutput, integrity)
            println jsonOutput ? JsonOutput.prettyPrint(JsonOutput.toJson(integrity)) : "integrity\t${integrity.status}\tchecked=${integrity.observations_checked}\tchain_valid=${integrity.chain_valid}"
            System.exit(integrity.status == 'ok' ? 0 : 1)
            break
        case 'checkpoint':
            Map checkpoint = store.checkpoint(checkpointMode)
            emit(jsonOutput, checkpoint)
            if (jsonOutput) {
                println JsonOutput.prettyPrint(JsonOutput.toJson(checkpoint))
            } else {
                println "checkpoint\tmode=${checkpoint.mode}\tbusy=${checkpoint.busy}\tlog=${checkpoint.log}\tcheckpointed=${checkpoint.checkpointed}"
            }
            break
        case 'export':
            String exported = store.exportPromptLog(exportSource, exportTail ?: Config.JOURNAL_EXPORT_TAIL_DEFAULT)
            print exported
            if (!exported.isEmpty() && !exported.endsWith('\n')) println()
            break
        case 'parity':
            File sourceFile = new File(parityPath).canonicalFile
            if (!sourceFile.exists()) {
                System.err.println("Parity source not found: ${sourceFile}")
                System.exit(2)
            }
            String sourceIdentity = exportSource ?: sourceFile.name
            String fullHash = sha256(sourceFile.bytes)
            Map snapshot = store.sourceSnapshot(sourceIdentity, fullHash)
            List<Map> parsed = ChatLogParser.parseFile(sourceFile, sourceIdentity)
            parsed.each { Map entry -> entry.timestampRepair = TimestampRepair.parse(entry.rawTimestamp?.toString()) }
            TimestampRepair.interpolate(parsed)
            List<Map> expected = parsed.collect { Map entry ->
                Map classified = TurnClassifier.classify(entry, Config.CHAT_SPILL_BYTES)
                [
                    turn_hash: TurnDocument.hash(classified),
                    event_time: classified.timestampRepair?.timestamp?.toString(),
                    user_text: classified.user?.toString() ?: '',
                    assistant_text: classified.assistant?.toString() ?: '',
                    outcome_summary: classified.assistant?.toString()?.take(4000)?.trim() ?: null,
                    raw_payload: classified.rawBody?.toString() ?: '',
                    source_start_line: classified.startLine as int,
                    source_end_line: classified.endLine as int
                ]
            }
            List<Map> actual = store.observationsForSource(sourceIdentity).findAll { it.source_kind == 'parser_backfill' }
            List<Map> actualComparable = actual.collect { Map observation ->
                [
                    turn_hash: observation.turn_hash?.toString(),
                    event_time: observation.event_time?.toString(),
                    user_text: observation.user_text?.toString() ?: '',
                    assistant_text: observation.assistant_text?.toString() ?: '',
                    outcome_summary: observation.outcome_summary?.toString(),
                    raw_payload: observation.raw_payload?.toString() ?: '',
                    source_start_line: observation.source_start_line as int,
                    source_end_line: observation.source_end_line as int
                ]
            }
            List<String> expectedHashes = expected.collect { it.turn_hash?.toString() }
            List<String> actualHashes = actualComparable.collect { it.turn_hash?.toString() }
            List<Map> expectedUnparsed = JournalStore.computeUncoveredRanges(sourceFile.bytes, parsed)
            List<Map> storedUnparsed = snapshot
                ? store.unparsedRangesForSnapshot(snapshot.snapshot_id.toString())
                : []
            boolean unparsedMatch = ranges(expectedUnparsed) == ranges(storedUnparsed)
            Map report = [
                source_identity: sourceIdentity,
                source_hash: fullHash,
                snapshot_present: snapshot != null,
                snapshot_hash_valid: snapshot?.raw_hash == fullHash,
                expected_observations: expectedHashes.size(),
                actual_observations: actualHashes.size(),
                turn_hashes_match: expectedHashes == actualHashes,
                duplicate_occurrences_match: multiset(expectedHashes) == multiset(actualHashes),
                order_match: expected.collect { [it.source_start_line, it.source_end_line] } ==
                    actualComparable.collect { [it.source_start_line, it.source_end_line] },
                timestamps_match: expected*.event_time == actualComparable*.event_time,
                roles_match: expected.collect { [it.user_text, it.assistant_text] } ==
                    actualComparable.collect { [it.user_text, it.assistant_text] },
                raw_payloads_match: expected*.raw_payload == actualComparable*.raw_payload,
                outcome_summaries_match: expected*.outcome_summary == actualComparable*.outcome_summary,
                first_outcome_mismatch: firstMismatch(expected*.outcome_summary, actualComparable*.outcome_summary),
                legacy_sequences_present: actual.every { it.legacy_sequence != null },
                expected_unparsed_ranges: expectedUnparsed.size(),
                stored_unparsed_ranges: storedUnparsed.size(),
                source_bytes_covered: unparsedMatch
            ]
            report.parity = report.snapshot_present && report.snapshot_hash_valid &&
                report.expected_observations == report.actual_observations &&
                report.turn_hashes_match && report.duplicate_occurrences_match &&
                report.order_match && report.timestamps_match && report.roles_match &&
                report.raw_payloads_match && report.outcome_summaries_match &&
                report.legacy_sequences_present && report.source_bytes_covered
            if (jsonOutput) {
                println JsonOutput.prettyPrint(JsonOutput.toJson(report))
            } else {
                println "parity\t${report.parity ? 'ok' : 'failed'}\tsource=${sourceIdentity}\texpected=${report.expected_observations}\tactual=${report.actual_observations}\tsnapshot=${report.snapshot_hash_valid}\tsource_bytes_covered=${report.source_bytes_covered}"
            }
            System.exit(report.parity ? 0 : 1)
            break
        default:
            System.err.println("Unknown journal mode: ${mode}")
            System.exit(2)
    }
} finally {
    store.close()
}

static void emit(boolean jsonOutput, Object payload) {
}

static String formatRecordResult(Map result) {
    return "record-event\tid=${result.observation_id}\tsequence=${result.sequence}\tidempotency=${result.idempotency}\tevent_hash=${result.event_hash}"
}

static String formatObservation(Map observation) {
    return "seq=${observation.sequence}\tid=${observation.observation_id}\tlogical_turn_key=${observation.logical_turn_key}\tsource=${observation.source_identity}\tturn_hash=${observation.turn_hash}"
}

static Map<String, Integer> multiset(List<String> values) {
    Map<String, Integer> counts = [:].withDefault { 0 }
    values.each { String value -> counts[value] = counts[value] + 1 }
    return counts
}

static List<String> ranges(List<Map> values) {
    return values.collect { Map value ->
        "${value.byte_start}:${value.byte_end}:${value.reason ?: ''}"
    }
}

static Integer firstMismatch(List expected, List actual) {
    int size = Math.min(expected.size(), actual.size())
    for (int index = 0; index < size; index++) {
        if (expected[index] != actual[index]) return index
    }
    return expected.size() == actual.size() ? null : size
}

static String sha256(byte[] bytes) {
    byte[] digest = MessageDigest.getInstance('SHA-256').digest(bytes ?: new byte[0])
    return digest.collect { String.format('%02x', it & 0xff) }.join()
}
