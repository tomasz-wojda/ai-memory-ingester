import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

class JournalEvent {

    static Map parseJson(String jsonText) {
        if (!jsonText?.trim()) {
            throw new IllegalArgumentException('Journal event JSON is required on stdin')
        }
        Object parsed = new JsonSlurper().parseText(jsonText)
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException('Journal event must be a JSON object')
        }
        return normalize((parsed as Map).collectEntries { k, v -> [k.toString(), v] })
    }

    static Map normalize(Map input) {
        int schemaVersion = (input.schema_version ?: input.event_schema_version ?: 0) as int
        if (schemaVersion != Config.JOURNAL_EVENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported journal event schema version ${schemaVersion}")
        }

        String sourceIde = requiredString(input, 'source_ide')
        String sourceKind = requiredString(input, 'source_kind')
        String sourceIdentity = requiredString(input, 'source_identity').replace('\\', '/')
        String userText = stringValue(input.user_text)
        String assistantText = stringValue(input.assistant_text)
        String rawPayload = input.containsKey('raw_payload') ? stringValue(input.raw_payload) : defaultRawPayload(userText, assistantText, input)

        String sourceFidelity = stringValue(input.source_fidelity)
        if (!sourceFidelity) {
            sourceFidelity = defaultSourceFidelity(sourceKind)
        }
        if (!Config.JOURNAL_SOURCE_FIDELITY_RANK.containsKey(sourceFidelity)) {
            throw new IllegalArgumentException("Unknown source_fidelity '${sourceFidelity}'")
        }

        List<String> tickets = []
        if (input.tickets instanceof Collection) {
            tickets = (input.tickets as Collection).collect { it?.toString()?.trim() }.findAll { it }
        }

        Map normalized = new LinkedHashMap([
            schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
            source_ide: sourceIde,
            source_kind: sourceKind,
            source_identity: sourceIdentity,
            source_fidelity: sourceFidelity,
            source_fidelity_rank: Config.JOURNAL_SOURCE_FIDELITY_RANK[sourceFidelity],
            session_id: nullableString(input.session_id),
            prompt_id: nullableString(input.prompt_id),
            logical_turn_key: nullableString(input.logical_turn_key),
            user_text: userText,
            assistant_text: assistantText,
            outcome_summary: nullableString(input.outcome_summary),
            mode: nullableString(input.mode),
            tickets: tickets,
            impact: nullableString(input.impact) ?: 'none',
            event_time: nullableString(input.event_time),
            event_time_confidence: nullableString(input.event_time_confidence) ?: 'lost',
            raw_payload: rawPayload,
            source_byte_offset: longValue(input.source_byte_offset),
            source_start_line: intValue(input.source_start_line),
            source_end_line: intValue(input.source_end_line),
            legacy_sequence: longValue(input.legacy_sequence),
            dialect: nullableString(input.dialect),
            tier: nullableString(input.tier),
            recorded_at: Instant.now().toString()
        ])

        if (!normalized.logical_turn_key) {
            normalized.logical_turn_key = deriveLogicalTurnKey(normalized)
        }
        normalized.content_hash = contentHash(normalized.user_text, normalized.assistant_text)
        normalized.turn_hash = TurnDocument.hash([
            user: normalized.user_text,
            assistant: normalized.assistant_text
        ])
        normalized.idempotency_key = idempotencyKey(normalized)
        return normalized
    }

    static Map fromParsedEntry(Map entry, String sourceIdentity, long legacySequence, String sourceIde = 'historical', String sourceKind = 'parser_backfill') {
        Map repair = (entry.timestampRepair ?: [:]) as Map
        String rawBody = entry.rawBody?.toString() ?: ''
        Map payload = new LinkedHashMap([
            schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
            source_ide: sourceIde,
            source_kind: sourceKind,
            source_fidelity: 'parser_backfill',
            source_identity: sourceIdentity.replace('\\', '/'),
            session_id: "${sourceSlug(sourceIdentity)}:${(repair.timestamp ?: 'unknown').toString().take(10)}",
            prompt_id: null,
            logical_turn_key: "${sourceIdentity.replace('\\', '/')}#${entry.startLine ?: 0}-${entry.endLine ?: 0}",
            user_text: entry.user?.toString() ?: '',
            assistant_text: entry.assistant?.toString() ?: '',
            outcome_summary: entry.assistant?.toString()?.take(4000),
            mode: FacetPath.modeFacet(entry.rawMode?.toString()),
            tickets: ((entry.ticketKeys ?: []) as List).collect { it.toString() },
            impact: entry.impact?.toString() ?: 'none',
            event_time: repair.timestamp?.toString(),
            event_time_confidence: repair.confidence?.toString() ?: 'lost',
            raw_payload: rawBody,
            source_byte_offset: entry.byteOffset ?: 0L,
            source_start_line: entry.startLine ?: 0,
            source_end_line: entry.endLine ?: 0,
            legacy_sequence: legacySequence,
            dialect: entry.dialect?.toString(),
            tier: entry.tier?.toString()
        ])
        return normalize(payload)
    }

    static String idempotencyKey(Map event) {
        String promptId = event.prompt_id?.toString()
        if (promptId) {
            return "${event.source_identity}::prompt::${promptId}"
        }
        String sessionId = event.session_id?.toString()
        long byteOffset = (event.source_byte_offset ?: 0L) as long
        int startLine = (event.source_start_line ?: 0) as int
        int endLine = (event.source_end_line ?: 0) as int
        if (sessionId && (byteOffset > 0L || startLine > 0 || endLine > 0)) {
            return "${event.source_identity}::${sessionId}::${byteOffset}:${startLine}:${endLine}"
        }
        if (byteOffset > 0L || startLine > 0 || endLine > 0) {
            return "${event.source_identity}::locator::${byteOffset}:${startLine}:${endLine}"
        }
        if (sessionId) {
            return "${event.source_identity}::session::${sessionId}::${event.recorded_at}"
        }
        return "${event.source_identity}::ephemeral::${UUID.randomUUID()}"
    }

    static String deriveLogicalTurnKey(Map event) {
        if (event.prompt_id) return event.prompt_id.toString()
        int startLine = (event.source_start_line ?: 0) as int
        int endLine = (event.source_end_line ?: 0) as int
        if (startLine > 0 || endLine > 0) {
            return "${event.source_identity}#${startLine}-${endLine}"
        }
        return null
    }

    static String contentHash(String userText, String assistantText) {
        return sha256("${userText}\n${assistantText}")
    }

    static String eventHash(Map event, String priorEventHash) {
        Map canonical = [
            observation_id: event.observation_id,
            logical_turn_key: event.logical_turn_key,
            sequence: event.sequence,
            recorded_at: event.recorded_at,
            event_time: event.event_time,
            event_time_confidence: event.event_time_confidence,
            source_ide: event.source_ide,
            source_kind: event.source_kind,
            source_identity: event.source_identity,
            source_fidelity_rank: event.source_fidelity_rank,
            session_id: event.session_id,
            prompt_id: event.prompt_id,
            user_text: event.user_text,
            assistant_text: event.assistant_text,
            outcome_summary: event.outcome_summary,
            mode: event.mode,
            tickets: event.tickets,
            impact: event.impact,
            raw_payload: event.raw_payload,
            content_hash: event.content_hash,
            turn_hash: event.turn_hash,
            prior_event_hash: priorEventHash ?: '',
            legacy_sequence: event.legacy_sequence
        ]
        return sha256(JsonOutput.toJson(canonical))
    }

    static String observationId() {
        byte[] bytes = new byte[16]
        new Random().nextBytes(bytes)
        return bytes.collect { String.format('%02x', it & 0xff) }.join()
    }

    private static String defaultRawPayload(String userText, String assistantText, Map input) {
        if (input.raw_body) return stringValue(input.raw_body)
        return [
            userText ? "USER:\n${userText}" : '',
            assistantText ? "ASSISTANT:\n${assistantText}" : ''
        ].findAll { it }.join('\n\n')
    }

    private static String defaultSourceFidelity(String sourceKind) {
        switch (sourceKind) {
            case 'native_transcript': return 'native_transcript'
            case 'deterministic_hook': return 'deterministic_hook'
            case 'rule_write': return 'rule_write'
            case 'rule_summary': return 'rule_summary'
            case 'parser_backfill': return 'parser_backfill'
            default: return 'rule_write'
        }
    }

    private static String sourceSlug(String path) {
        String normalized = (path ?: '').replace('\\', '/')
        String base = normalized.tokenize('/').takeRight(3).join('-')
        return FacetPath.sanitizeToken(base) ?: 'unknown'
    }

    private static String requiredString(Map input, String key) {
        String value = stringValue(input[key])
        if (!value) throw new IllegalArgumentException("Missing required field '${key}'")
        return value
    }

    private static String stringValue(Object value) {
        return value == null ? '' : value.toString()
    }

    private static String nullableString(Object value) {
        String text = stringValue(value).trim()
        return text ?: null
    }

    private static Long longValue(Object value) {
        if (value == null || value.toString().trim().isEmpty()) return null
        return value.toString().toLong()
    }

    private static Integer intValue(Object value) {
        if (value == null || value.toString().trim().isEmpty()) return null
        return value.toString().toInteger()
    }

    private static String sha256(String text) {
        byte[] digest = MessageDigest.getInstance('SHA-256')
            .digest((text ?: '').getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format('%02x', it & 0xff) }.join()
    }
}
