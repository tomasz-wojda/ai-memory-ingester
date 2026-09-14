import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.sql.Sql
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.regex.Pattern

class JournalStore {

    private static final Object SCHEMA_LOCK = new Object()

    private final Sql sql
    private final File dbFile

    JournalStore(File dbPath = Config.journalDbPath()) {
        this.dbFile = dbPath.canonicalFile
        dbFile.parentFile?.mkdirs()
        def driver = Thread.currentThread().contextClassLoader
            .loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
        def conn = driver.connect("jdbc:sqlite:${dbFile.absolutePath}", new java.util.Properties())
        this.sql = new Sql(conn)
        initSchema()
    }

    File getDbFile() {
        return dbFile
    }

    void close() {
        sql.close()
    }

    Map record(Map event) {
        Map normalized = JournalEvent.normalize(event.containsKey('schema_version') ? event : event + [schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION])
        int attempts = 0
        while (true) {
            try {
                return recordTransaction(normalized)
            } catch (Exception error) {
                attempts++
                if (attempts >= 16 || !isBusyError(error)) {
                    throw error
                }
                Thread.sleep(10L * attempts)
            }
        }
    }

    private Map recordTransaction(Map normalized) {
        Map result = null
        sql.execute('BEGIN IMMEDIATE')
        try {
            Map existing = sql.firstRow(
                'SELECT observation_id, sequence, event_hash FROM journal_idempotency WHERE idempotency_key = ?',
                [normalized.idempotency_key]
            ) as Map
            if (existing) {
                result = [
                    observation_id: existing.observation_id,
                    logical_turn_key: normalized.logical_turn_key,
                    sequence: existing.sequence,
                    idempotency: 'existing',
                    event_hash: existing.event_hash,
                    durable: true
                ]
                sql.execute('COMMIT')
                return result
            }

            long sequence = allocateSequence()
            String observationId = JournalEvent.observationId()
            String priorHash = sql.firstRow('SELECT event_hash FROM journal_observations WHERE sequence = ?', [sequence - 1])?.event_hash ?: ''
            Map row = new LinkedHashMap(normalized)
            row.observation_id = observationId
            row.sequence = sequence
            row.prior_event_hash = priorHash
            row.event_hash = JournalEvent.eventHash(row, priorHash)
            row.tickets_json = JsonOutput.toJson(row.tickets ?: [])

            sql.execute('''
                INSERT INTO journal_observations (
                    observation_id, logical_turn_key, sequence, recorded_at, event_time, event_time_confidence,
                    source_ide, source_kind, source_identity, source_fidelity, source_fidelity_rank,
                    session_id, prompt_id, user_text, assistant_text, outcome_summary, mode, tickets_json,
                    impact, raw_payload, content_hash, turn_hash, prior_event_hash, event_hash,
                    source_byte_offset, source_start_line, source_end_line, legacy_sequence, dialect, tier
                ) VALUES (
                    :observation_id, :logical_turn_key, :sequence, :recorded_at, :event_time, :event_time_confidence,
                    :source_ide, :source_kind, :source_identity, :source_fidelity, :source_fidelity_rank,
                    :session_id, :prompt_id, :user_text, :assistant_text, :outcome_summary, :mode, :tickets_json,
                    :impact, :raw_payload, :content_hash, :turn_hash, :prior_event_hash, :event_hash,
                    :source_byte_offset, :source_start_line, :source_end_line, :legacy_sequence, :dialect, :tier
                )
            ''', row)

            sql.execute('''
                INSERT INTO journal_idempotency (idempotency_key, observation_id, sequence, event_hash, recorded_at)
                VALUES (?, ?, ?, ?, ?)
            ''', [row.idempotency_key, observationId, sequence, row.event_hash, row.recorded_at])

            upsertSourceIdentity(row)
            result = [
                observation_id: observationId,
                logical_turn_key: row.logical_turn_key,
                sequence: sequence,
                idempotency: 'inserted',
                event_hash: row.event_hash,
                durable: true
            ]
            sql.execute('COMMIT')
        } catch (Exception error) {
            sql.execute('ROLLBACK')
            throw error
        }
        return result
    }

    private static boolean isBusyError(Exception error) {
        String message = error?.message?.toString()?.toLowerCase() ?: ''
        Throwable cause = error.cause
        while (!message.contains('database is locked') && !message.contains('sqlite_busy') && cause) {
            message = cause.message?.toString()?.toLowerCase() ?: ''
            cause = cause.cause
        }
        return message.contains('database is locked') || message.contains('sqlite_busy')
    }

    Map getObservation(String observationId) {
        Map row = sql.firstRow('SELECT * FROM journal_observations WHERE observation_id = ?', [observationId]) as Map
        return row ? formatObservation(row) : null
    }

    Map getBySequence(long sequence) {
        Map row = sql.firstRow('SELECT * FROM journal_observations WHERE sequence = ?', [sequence]) as Map
        return row ? formatObservation(row) : null
    }

    List<Map> sequenceRange(long fromSequence, long toSequence) {
        if (fromSequence > toSequence) {
            long swap = fromSequence
            fromSequence = toSequence
            toSequence = swap
        }
        return sql.rows('''
            SELECT * FROM journal_observations
            WHERE sequence BETWEEN ? AND ?
            ORDER BY sequence ASC
        ''', [fromSequence, toSequence]).collect { formatObservation(it as Map) }
    }

    List<Map> tail(int limit = 20) {
        int bounded = Math.max(1, limit)
        return sql.rows('''
            SELECT * FROM journal_observations
            ORDER BY sequence DESC
            LIMIT ?
        ''', [bounded]).collect { formatObservation(it as Map) }.reverse()
    }

    List<Map> searchLiteral(String needle, int limit = 100) {
        String pattern = "%${needle.replace('%', '\\%').replace('_', '\\_')}%"
        return sql.rows('''
            SELECT * FROM journal_observations
            WHERE user_text LIKE ? ESCAPE '\\'
               OR assistant_text LIKE ? ESCAPE '\\'
               OR outcome_summary LIKE ? ESCAPE '\\'
               OR raw_payload LIKE ? ESCAPE '\\'
            ORDER BY sequence ASC
            LIMIT ?
        ''', [pattern, pattern, pattern, pattern, Math.max(1, limit)]).collect { formatObservation(it as Map) }
    }

    List<Map> searchRegex(String patternText, int limit = 100) {
        Pattern pattern = Pattern.compile(patternText)
        List<Map> matches = []
        int bounded = Math.max(1, limit)
        sql.eachRow('SELECT * FROM journal_observations ORDER BY sequence ASC') { row ->
            Map observation = formatObservation(row as Map)
            String haystack = [
                observation.user_text,
                observation.assistant_text,
                observation.outcome_summary,
                observation.raw_payload
            ].join('\n')
            if (matches.size() < bounded && pattern.matcher(haystack).find()) {
                matches << observation
            }
        }
        return matches
    }

    List<Map> listSources() {
        return sql.rows('''
            SELECT source_identity, source_ide, source_kind, first_seen_at, last_seen_at, observation_count
            FROM journal_source_identities
            ORDER BY source_identity ASC
        ''').collect { row ->
            [
                source_identity: row.source_identity,
                source_ide: row.source_ide,
                source_kind: row.source_kind,
                first_seen_at: row.first_seen_at,
                last_seen_at: row.last_seen_at,
                observation_count: row.observation_count
            ]
        }
    }

    List<Map> observationsForSource(String sourceIdentity) {
        return sql.rows('''
            SELECT * FROM journal_observations
            WHERE source_identity = ?
            ORDER BY sequence ASC
        ''', [sourceIdentity]).collect { formatObservation(it as Map) }
    }

    Map sourceSnapshot(String sourceIdentity, String fullHash) {
        Map row = sql.firstRow('''
            SELECT snapshot_id, source_identity, source_path, full_hash, byte_length, raw_bytes, recorded_at
            FROM journal_source_snapshots
            WHERE source_identity = ? AND full_hash = ?
            ORDER BY recorded_at DESC
            LIMIT 1
        ''', [sourceIdentity, fullHash]) as Map
        if (!row) return null
        byte[] rawBytes = row.raw_bytes as byte[]
        return [
            snapshot_id: row.snapshot_id,
            source_identity: row.source_identity,
            source_path: row.source_path,
            full_hash: row.full_hash,
            byte_length: row.byte_length,
            raw_hash: sha256Bytes(rawBytes)
        ]
    }

    List<Map> unparsedRangesForSnapshot(String snapshotId) {
        return sql.rows('''
            SELECT byte_start, byte_end, reason
            FROM journal_unparsed_ranges
            WHERE snapshot_id = ?
            ORDER BY byte_start ASC, byte_end ASC
        ''', [snapshotId]).collect { Map row ->
            [
                byte_start: row.byte_start as long,
                byte_end: row.byte_end as long,
                reason: row.reason?.toString()
            ]
        }
    }

    Map recordSourceSnapshot(String sourceIdentity, String sourcePath, File sourceFile) {
        byte[] rawBytes = sourceFile.bytes
        String fullHash = sha256Bytes(rawBytes)
        String snapshotId = sha256("${sourceIdentity}:${fullHash}:${rawBytes.length}")
        String now = Instant.now().toString()
        sql.execute('''
            INSERT OR IGNORE INTO journal_source_snapshots (
                snapshot_id, source_identity, source_path, full_hash, byte_length, raw_bytes, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ''', [snapshotId, sourceIdentity, sourcePath, fullHash, rawBytes.length, rawBytes, now])
        return [
            snapshot_id: snapshotId,
            source_identity: sourceIdentity,
            source_path: sourcePath,
            full_hash: fullHash,
            byte_length: rawBytes.length,
            inserted: sql.firstRow('SELECT changes() AS c').c as int > 0
        ]
    }

    void recordUnparsedRanges(String snapshotId, List<Map> ranges) {
        ranges.each { Map range ->
            String rangeId = sha256("${snapshotId}:${range.byte_start}:${range.byte_end}:${range.reason ?: ''}")
            sql.execute('''
                INSERT OR IGNORE INTO journal_unparsed_ranges (range_id, snapshot_id, byte_start, byte_end, reason)
                VALUES (?, ?, ?, ?, ?)
            ''', [rangeId, snapshotId, range.byte_start as long, range.byte_end as long, range.reason?.toString()])
        }
    }

    Map verifyIntegrity(boolean recordRun = true) {
        String runId = JournalEvent.observationId()
        String startedAt = Instant.now().toString()
        if (recordRun) {
            sql.execute('''
                INSERT INTO journal_integrity_runs (run_id, started_at, status)
                VALUES (?, ?, 'running')
            ''', [runId, startedAt])
        }

        List<Map> observations = sql.rows('SELECT * FROM journal_observations ORDER BY sequence ASC')
        String expectedPrior = ''
        boolean chainValid = true
        long expectedSequence = 1L
        List<String> issues = []

        observations.each { row ->
            long sequence = row.sequence as long
            if (sequence != expectedSequence) {
                chainValid = false
                issues << "sequence gap or reorder at ${sequence}, expected ${expectedSequence}"
            }
            String prior = row.prior_event_hash ?: ''
            if (prior != (expectedPrior ?: '')) {
                chainValid = false
                issues << "prior_event_hash mismatch at sequence ${sequence}"
            }
            Map formatted = formatObservation(row as Map)
            formatted.sequence = sequence
            formatted.observation_id = row.observation_id
            String recomputed = JournalEvent.eventHash(formatted, expectedPrior ?: '')
            if (recomputed != row.event_hash) {
                chainValid = false
                issues << "event_hash mismatch at sequence ${sequence}"
            }
            expectedPrior = row.event_hash
            expectedSequence++
        }

        Map canonicalConflicts = canonicalConflictReport()

        String status = chainValid ? 'ok' : 'failed'
        Map report = [
            observations_checked: observations.size(),
            chain_valid: chainValid,
            sequence_count: observations.size(),
            canonical_conflicts: canonicalConflicts.conflict_count,
            issues: issues
        ]
        if (recordRun) {
            sql.execute('''
                UPDATE journal_integrity_runs
                SET completed_at = ?, status = ?, observations_checked = ?, chain_valid = ?, report_json = ?
                WHERE run_id = ?
            ''', [Instant.now().toString(), status, observations.size(), chainValid ? 1 : 0, JsonOutput.toJson(report), runId])
        }

        return [
            run_id: runId,
            status: status,
            observations_checked: observations.size(),
            chain_valid: chainValid,
            canonical_conflicts: canonicalConflicts.conflict_count,
            issues: issues
        ]
    }

    String exportPromptLog(String sourceIdentity = null, int tailLines = Config.JOURNAL_EXPORT_TAIL_DEFAULT) {
        List<Map> observations = sourceIdentity
            ? sql.rows('SELECT * FROM journal_observations WHERE source_identity = ? ORDER BY sequence ASC', [sourceIdentity]).collect { formatObservation(it as Map) }
            : sql.rows('SELECT * FROM journal_observations ORDER BY sequence ASC').collect { formatObservation(it as Map) }
        List<String> blocks = observations.collect { Map observation -> renderPromptLogBlock(observation) }
        if (tailLines > 0 && blocks.size() > tailLines) {
            blocks = blocks[-tailLines..-1]
        }
        return blocks.join('\n\n')
    }

    List<Map> canonicalObservations() {
        return sql.rows('SELECT * FROM canonical_journal_observations ORDER BY sequence ASC')
            .collect { formatObservation(it as Map) }
    }

    Map backfillObservation(Map event) {
        Map normalized = event.containsKey('idempotency_key') ? event : JournalEvent.normalize(event)
        return record(normalized)
    }

    Map getSourceCursor(String sourceIdentity) {
        Map row = sql.firstRow('''
            SELECT source_identity, cursor_kind, cursor_value, updated_at
            FROM journal_source_cursors WHERE source_identity = ?
        ''', [sourceIdentity]) as Map
        return row ? [
            source_identity: row.source_identity,
            cursor_kind: row.cursor_kind,
            cursor_value: row.cursor_value,
            updated_at: row.updated_at
        ] : null
    }

    void setSourceCursor(String sourceIdentity, String cursorKind, String cursorValue) {
        String now = Instant.now().toString()
        sql.execute('''
            INSERT INTO journal_source_cursors (source_identity, cursor_kind, cursor_value, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(source_identity) DO UPDATE SET
                cursor_kind = excluded.cursor_kind,
                cursor_value = excluded.cursor_value,
                updated_at = excluded.updated_at
        ''', [sourceIdentity, cursorKind, cursorValue, now])
    }

    long getProjectionCursor(String projectionName = Config.JOURNAL_PROJECTION_NAME) {
        Map row = sql.firstRow('''
            SELECT cursor_sequence FROM journal_projection_cursors WHERE projection_name = ?
        ''', [projectionName]) as Map
        return row ? (row.cursor_sequence as long) : 0L
    }

    void setProjectionCursor(long sequence, String projectionName = Config.JOURNAL_PROJECTION_NAME) {
        String now = Instant.now().toString()
        sql.execute('''
            INSERT INTO journal_projection_cursors (projection_name, cursor_sequence, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(projection_name) DO UPDATE SET
                cursor_sequence = excluded.cursor_sequence,
                updated_at = excluded.updated_at
        ''', [projectionName, sequence, now])
    }

    Map recordReconciliationLink(String leftObservationId, String rightObservationId, String linkKind, String matchBasis = null) {
        String linkId = sha256("${leftObservationId}:${rightObservationId}:${linkKind}:${matchBasis ?: ''}")
        String now = Instant.now().toString()
        sql.execute('''
            INSERT OR IGNORE INTO journal_reconciliation_links (
                link_id, left_observation_id, right_observation_id, link_kind, match_basis, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?)
        ''', [linkId, leftObservationId, rightObservationId, linkKind, matchBasis, now])
        boolean inserted = sql.firstRow('SELECT changes() AS c').c as int > 0
        return [link_id: linkId, inserted: inserted, link_kind: linkKind]
    }

    List<Map> reconciliationLinksFor(String observationId) {
        return sql.rows('''
            SELECT link_id, left_observation_id, right_observation_id, link_kind, match_basis, recorded_at
            FROM journal_reconciliation_links
            WHERE left_observation_id = ? OR right_observation_id = ?
            ORDER BY recorded_at ASC
        ''', [observationId, observationId]).collect { Map row ->
            [
                link_id: row.link_id,
                left_observation_id: row.left_observation_id,
                right_observation_id: row.right_observation_id,
                link_kind: row.link_kind,
                match_basis: row.match_basis,
                recorded_at: row.recorded_at
            ]
        }
    }

    List<Map> findObservationsByPromptId(String promptId) {
        return sql.rows('''
            SELECT * FROM journal_observations WHERE prompt_id = ? ORDER BY sequence ASC
        ''', [promptId]).collect { formatObservation(it as Map) }
    }

    List<Map> findObservationsBySession(String sessionId, String sourceIde = null) {
        if (sourceIde) {
            return sql.rows('''
                SELECT * FROM journal_observations
                WHERE session_id = ? AND source_ide = ?
                ORDER BY sequence ASC
            ''', [sessionId, sourceIde]).collect { formatObservation(it as Map) }
        }
        return sql.rows('''
            SELECT * FROM journal_observations WHERE session_id = ? ORDER BY sequence ASC
        ''', [sessionId]).collect { formatObservation(it as Map) }
    }

    List<Map> observationsAfterSequence(long sequence) {
        return sql.rows('''
            SELECT * FROM journal_observations WHERE sequence > ? ORDER BY sequence ASC
        ''', [sequence]).collect { formatObservation(it as Map) }
    }

    List<Map> canonicalObservationsAfterSequence(long sequence) {
        return sql.rows('''
            SELECT * FROM canonical_journal_observations WHERE sequence > ? ORDER BY sequence ASC
        ''', [sequence]).collect { formatObservation(it as Map) }
    }

    Map checkpoint(String mode = 'TRUNCATE') {
        String normalized = (mode ?: 'TRUNCATE').toString().trim().toUpperCase()
        if (!(normalized in ['PASSIVE', 'FULL', 'RESTART', 'TRUNCATE'])) {
            throw new IllegalArgumentException("Unsupported checkpoint mode '${mode}'")
        }
        def statement = sql.connection.createStatement()
        def resultSet = statement.executeQuery("PRAGMA wal_checkpoint(${normalized})")
        resultSet.next()
        return [
            mode: normalized,
            busy: resultSet.getInt(1),
            log: resultSet.getInt(2),
            checkpointed: resultSet.getInt(3)
        ]
    }

    Map appendOnlyProbe(long sequence = 1L) {
        boolean updateBlocked = false
        boolean deleteBlocked = false
        try {
            sql.execute('UPDATE journal_observations SET user_text = ? WHERE sequence = ?', ['mutated', sequence])
        } catch (Exception ignored) {
            updateBlocked = true
        }
        try {
            sql.execute('DELETE FROM journal_observations WHERE sequence = ?', [sequence])
        } catch (Exception ignored) {
            deleteBlocked = true
        }
        return [update_blocked: updateBlocked, delete_blocked: deleteBlocked]
    }

    private void initSchema() {
        synchronized (SCHEMA_LOCK) {
            initSchemaUnsafe()
        }
    }

    private void initSchemaUnsafe() {
        sql.execute('PRAGMA busy_timeout=30000')
        sql.execute('PRAGMA journal_mode=WAL')
        sql.execute('PRAGMA synchronous=FULL')
        sql.execute('PRAGMA foreign_keys=ON')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_sequence (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                next_sequence INTEGER NOT NULL
            )
        ''')
        sql.execute('INSERT OR IGNORE INTO journal_sequence (id, next_sequence) VALUES (1, 1)')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_observations (
                observation_id TEXT PRIMARY KEY,
                logical_turn_key TEXT,
                sequence INTEGER NOT NULL UNIQUE,
                recorded_at TEXT NOT NULL,
                event_time TEXT,
                event_time_confidence TEXT,
                source_ide TEXT NOT NULL,
                source_kind TEXT NOT NULL,
                source_identity TEXT NOT NULL,
                source_fidelity TEXT NOT NULL,
                source_fidelity_rank INTEGER NOT NULL,
                session_id TEXT,
                prompt_id TEXT,
                user_text TEXT NOT NULL DEFAULT '',
                assistant_text TEXT NOT NULL DEFAULT '',
                outcome_summary TEXT,
                mode TEXT,
                tickets_json TEXT,
                impact TEXT,
                raw_payload TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                turn_hash TEXT NOT NULL,
                prior_event_hash TEXT,
                event_hash TEXT NOT NULL,
                source_byte_offset INTEGER,
                source_start_line INTEGER,
                source_end_line INTEGER,
                legacy_sequence INTEGER,
                dialect TEXT,
                tier TEXT
            )
        ''')

        sql.execute('CREATE INDEX IF NOT EXISTS idx_journal_observations_sequence ON journal_observations(sequence)')
        sql.execute('CREATE INDEX IF NOT EXISTS idx_journal_observations_logical_turn ON journal_observations(logical_turn_key)')
        sql.execute('CREATE INDEX IF NOT EXISTS idx_journal_observations_source ON journal_observations(source_identity)')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_idempotency (
                idempotency_key TEXT PRIMARY KEY,
                observation_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                event_hash TEXT NOT NULL,
                recorded_at TEXT NOT NULL
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_source_identities (
                source_identity TEXT PRIMARY KEY,
                source_ide TEXT NOT NULL,
                source_kind TEXT NOT NULL,
                first_seen_at TEXT NOT NULL,
                last_seen_at TEXT NOT NULL,
                observation_count INTEGER NOT NULL DEFAULT 0
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_source_cursors (
                source_identity TEXT PRIMARY KEY,
                cursor_kind TEXT NOT NULL,
                cursor_value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_projection_cursors (
                projection_name TEXT PRIMARY KEY,
                cursor_sequence INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_source_snapshots (
                snapshot_id TEXT PRIMARY KEY,
                source_identity TEXT NOT NULL,
                source_path TEXT NOT NULL,
                full_hash TEXT NOT NULL,
                byte_length INTEGER NOT NULL,
                raw_bytes BLOB NOT NULL,
                recorded_at TEXT NOT NULL
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_unparsed_ranges (
                range_id TEXT PRIMARY KEY,
                snapshot_id TEXT NOT NULL,
                byte_start INTEGER NOT NULL,
                byte_end INTEGER NOT NULL,
                reason TEXT
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_integrity_runs (
                run_id TEXT PRIMARY KEY,
                started_at TEXT NOT NULL,
                completed_at TEXT,
                status TEXT NOT NULL,
                observations_checked INTEGER,
                chain_valid INTEGER,
                report_json TEXT
            )
        ''')

        sql.execute('''
            CREATE TABLE IF NOT EXISTS journal_reconciliation_links (
                link_id TEXT PRIMARY KEY,
                left_observation_id TEXT NOT NULL,
                right_observation_id TEXT NOT NULL,
                link_kind TEXT NOT NULL,
                match_basis TEXT,
                recorded_at TEXT NOT NULL
            )
        ''')
        sql.execute('CREATE INDEX IF NOT EXISTS idx_journal_reconciliation_left ON journal_reconciliation_links(left_observation_id)')
        sql.execute('CREATE INDEX IF NOT EXISTS idx_journal_reconciliation_right ON journal_reconciliation_links(right_observation_id)')

        sql.execute('''
            CREATE TRIGGER IF NOT EXISTS journal_observations_no_update
            BEFORE UPDATE ON journal_observations
            BEGIN
                SELECT RAISE(ABORT, 'journal_observations is append-only');
            END
        ''')
        sql.execute('''
            CREATE TRIGGER IF NOT EXISTS journal_observations_no_delete
            BEFORE DELETE ON journal_observations
            BEGIN
                SELECT RAISE(ABORT, 'journal_observations is append-only');
            END
        ''')

        sql.execute('''
            CREATE VIEW IF NOT EXISTS canonical_journal_observations AS
            SELECT o.*
            FROM journal_observations o
            JOIN (
                SELECT logical_turn_key,
                       MIN(source_fidelity_rank) AS best_rank
                FROM journal_observations
                WHERE logical_turn_key IS NOT NULL AND TRIM(logical_turn_key) != ''
                GROUP BY logical_turn_key
            ) ranked ON o.logical_turn_key = ranked.logical_turn_key
                     AND o.source_fidelity_rank = ranked.best_rank
            WHERE o.sequence = (
                SELECT MAX(o2.sequence)
                FROM journal_observations o2
                WHERE o2.logical_turn_key = o.logical_turn_key
                  AND o2.source_fidelity_rank = ranked.best_rank
            )
            UNION ALL
            SELECT o.*
            FROM journal_observations o
            WHERE o.logical_turn_key IS NULL OR TRIM(o.logical_turn_key) = ''
        ''')

        sql.execute('''
            INSERT OR IGNORE INTO journal_meta (key, value) VALUES ('schema_version', ?)
        ''', [Config.JOURNAL_SCHEMA_VERSION.toString()])
    }

    private long allocateSequence() {
        sql.execute('UPDATE journal_sequence SET next_sequence = next_sequence + 1 WHERE id = 1')
        long sequence = sql.firstRow('SELECT next_sequence - 1 AS seq FROM journal_sequence WHERE id = 1').seq as long
        return sequence
    }

    private void upsertSourceIdentity(Map row) {
        String now = row.recorded_at
        int updated = sql.executeUpdate('''
            UPDATE journal_source_identities
            SET last_seen_at = ?, observation_count = observation_count + 1,
                source_ide = ?, source_kind = ?
            WHERE source_identity = ?
        ''', [now, row.source_ide, row.source_kind, row.source_identity])
        if (updated == 0) {
            sql.execute('''
                INSERT INTO journal_source_identities (
                    source_identity, source_ide, source_kind, first_seen_at, last_seen_at, observation_count
                ) VALUES (?, ?, ?, ?, ?, 1)
            ''', [row.source_identity, row.source_ide, row.source_kind, now, now])
        }
    }

    private Map formatObservation(def row) {
        List tickets = []
        String ticketsJson = column(row, 'tickets_json')?.toString()
        if (ticketsJson) {
            try {
                tickets = (new JsonSlurper().parseText(ticketsJson) ?: []) as List
            } catch (ignored) {
                tickets = []
            }
        }
        return [
            observation_id: column(row, 'observation_id'),
            logical_turn_key: column(row, 'logical_turn_key'),
            sequence: column(row, 'sequence'),
            recorded_at: column(row, 'recorded_at'),
            event_time: column(row, 'event_time'),
            event_time_confidence: column(row, 'event_time_confidence'),
            source_ide: column(row, 'source_ide'),
            source_kind: column(row, 'source_kind'),
            source_identity: column(row, 'source_identity'),
            source_fidelity: column(row, 'source_fidelity'),
            source_fidelity_rank: column(row, 'source_fidelity_rank'),
            session_id: column(row, 'session_id'),
            prompt_id: column(row, 'prompt_id'),
            user_text: column(row, 'user_text'),
            assistant_text: column(row, 'assistant_text'),
            outcome_summary: column(row, 'outcome_summary'),
            mode: column(row, 'mode'),
            tickets: tickets,
            impact: column(row, 'impact'),
            raw_payload: column(row, 'raw_payload'),
            content_hash: column(row, 'content_hash'),
            turn_hash: column(row, 'turn_hash'),
            prior_event_hash: column(row, 'prior_event_hash'),
            event_hash: column(row, 'event_hash'),
            source_byte_offset: column(row, 'source_byte_offset'),
            source_start_line: column(row, 'source_start_line'),
            source_end_line: column(row, 'source_end_line'),
            legacy_sequence: column(row, 'legacy_sequence'),
            dialect: column(row, 'dialect'),
            tier: column(row, 'tier')
        ]
    }

    private static Object column(def row, String name) {
        if (row instanceof Map) return (row as Map)[name]
        return row.getAt(name)
    }

    private Map canonicalConflictReport() {
        List<Map> conflicts = sql.rows('''
            SELECT logical_turn_key, COUNT(DISTINCT content_hash) AS versions
            FROM journal_observations
            WHERE logical_turn_key IS NOT NULL AND TRIM(logical_turn_key) != ''
            GROUP BY logical_turn_key
            HAVING COUNT(DISTINCT content_hash) > 1
        ''')
        int conflictCount = conflicts.size()
        return [conflict_count: conflictCount, groups: conflicts.size()]
    }

    private static String renderPromptLogBlock(Map observation) {
        String timestamp = observation.event_time ?: observation.recorded_at ?: Instant.now().toString()
        List<String> lines = [
            "[${timestamp}] USER: ${observation.user_text ?: ''}",
            "[${timestamp}] ASSISTANT: ${observation.assistant_text ?: observation.outcome_summary ?: ''}"
        ]
        if (observation.outcome_summary && observation.outcome_summary != observation.assistant_text) {
            lines << "OUTCOME: ${observation.outcome_summary}"
        }
        return lines.join('\n')
    }

    static List<Map> computeUncoveredRanges(byte[] sourceBytes, List<Map> parsedEntries) {
        if (!sourceBytes || sourceBytes.length == 0) return []
        List<long[]> covered = parsedEntries.collect { Map entry ->
            long start = (entry.byteOffset ?: 0L) as long
            String rawBody = entry.rawBody?.toString() ?: ''
            long end = start + rawBody.getBytes(StandardCharsets.UTF_8).length
            [start, end] as long[]
        }.findAll { it[1] > it[0] }.sort { a, b -> a[0] <=> b[0] }

        List<Map> uncovered = []
        long cursor = 0L
        covered.each { long[] span ->
            if (span[0] > cursor) {
                uncovered << [byte_start: cursor, byte_end: span[0], reason: 'unparsed_gap']
            }
            cursor = Math.max(cursor, span[1] as long)
        }
        if (cursor < sourceBytes.length) {
            uncovered << [byte_start: cursor, byte_end: sourceBytes.length as long, reason: 'trailing_unparsed']
        }
        return uncovered
    }

    private static String sha256(String text) {
        byte[] digest = MessageDigest.getInstance('SHA-256')
            .digest((text ?: '').getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format('%02x', it & 0xff) }.join()
    }

    private static String sha256Bytes(byte[] bytes) {
        byte[] digest = MessageDigest.getInstance('SHA-256').digest(bytes ?: new byte[0])
        return digest.collect { String.format('%02x', it & 0xff) }.join()
    }
}
