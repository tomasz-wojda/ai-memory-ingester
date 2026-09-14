import java.time.Instant
import java.time.format.DateTimeParseException

class TranscriptReconciler {

    static Map reconcile(
        JournalStore store,
        File workspaceRoot,
        File homeDir = null,
        boolean dryRun = false,
        List<String> sourceFilter = null
    ) {
        File resolvedHome = homeDir ?: defaultHome()
        long started = System.currentTimeMillis()
        Map totals = [
            sources_scanned: 0,
            turns_parsed: 0,
            observations_appended: 0,
            observations_existing: 0,
            links_created: 0,
            conflicts: 0,
            lag_reports: 0,
            errors: 0,
            unknown_format_failures: 0
        ]
        List<Map> sourceReports = []
        List<Map> conflictReports = []
        List<Map> lagReports = []

        [
            [adapter: CursorTranscriptAdapter, ide: 'cursor'],
            [adapter: ClaudeTranscriptAdapter, ide: 'claude']
        ].each { Map binding ->
            Class adapter = binding.adapter as Class
            List<File> files = adapter.discoverTranscriptFiles(workspaceRoot, resolvedHome) as List<File>
            if (sourceFilter) {
                files = files.findAll { File file ->
                    String identity = adapter.sourceIdentityFor(file)
                    sourceFilter.any { String filter -> identity.contains(filter) || file.canonicalPath.contains(filter) }
                }
            }
            files.each { File file ->
                totals.sources_scanned++
                Map sourceReport = [source_path: file.canonicalPath, source_ide: binding.ide]
                try {
                    String sourceIdentity = adapter.sourceIdentityFor(file)
                    Map cursor = store.getSourceCursor(sourceIdentity)
                    long startOffset = cursor?.cursor_value?.toString()?.isLong() ? cursor.cursor_value.toLong() : 0L
                    Map parsed = adapter.parseFile(file, startOffset) as Map
                    sourceReport.format_version = parsed.format_version
                    sourceReport.turns_parsed = (parsed.turns as List)?.size() ?: 0
                    totals.turns_parsed += sourceReport.turns_parsed as int

                    (parsed.turns as List ?: []).each { Map turn ->
                        Map outcome = reconcileTurn(store, turn, binding.ide as String, dryRun)
                        if (outcome.appended) totals.observations_appended++
                        if (outcome.existing) totals.observations_existing++
                        if (outcome.linked) totals.links_created++
                        if (outcome.conflict) {
                            totals.conflicts++
                            conflictReports << outcome.conflict_report
                        }
                        if (outcome.lag) {
                            totals.lag_reports++
                            lagReports << outcome.lag_report
                        }
                    }

                    if (!dryRun) {
                        store.setSourceCursor(sourceIdentity, 'byte_offset', (parsed.byte_offset_end as long).toString())
                    }
                    sourceReport.status = 'ok'
                    sourceReport.byte_offset_end = parsed.byte_offset_end
                    if (parsed.errors) sourceReport.errors = parsed.errors
                } catch (IllegalStateException ex) {
                    totals.unknown_format_failures++
                    totals.errors++
                    sourceReport.status = 'unknown_format'
                    sourceReport.error = ex.message
                } catch (Exception ex) {
                    totals.errors++
                    sourceReport.status = 'failed'
                    sourceReport.error = ex.message
                }
                sourceReports << sourceReport
            }
        }

        Map integrity = store.verifyIntegrity(!dryRun)
        return [
            status: totals.unknown_format_failures > 0 ? 'failed' : 'ok',
            totals: totals,
            sources: sourceReports,
            conflicts: conflictReports,
            lag: lagReports,
            integrity: integrity,
            elapsed_ms: System.currentTimeMillis() - started
        ]
    }

    private static Map reconcileTurn(JournalStore store, Map turn, String sourceIde, boolean dryRun) {
        Map event = transcriptEvent(turn, sourceIde)
        Map normalized = JournalEvent.normalize(event)
        Map match = findMatchingObservation(store, normalized)
        Map result = [appended: false, existing: false, linked: false, conflict: false, lag: false]

        if (match?.kind == 'conflict') {
            result.conflict = true
            result.conflict_report = [
                existing_observation_id: match.observation.observation_id,
                existing_sequence: match.observation.sequence,
                transcript_logical_turn_key: normalized.logical_turn_key,
                match_basis: match.basis,
                existing_content_hash: match.observation.content_hash,
                transcript_content_hash: normalized.content_hash
            ]
        }
        if (match?.kind == 'lag') {
            result.lag = true
            result.lag_report = [
                rule_observation_id: match.observation.observation_id,
                rule_sequence: match.observation.sequence,
                transcript_logical_turn_key: normalized.logical_turn_key,
                basis: match.basis
            ]
        }

        if (dryRun) {
            result.appended = match == null || match.kind == 'conflict'
            result.existing = match?.kind == 'exact'
            result.linked = match?.observation != null
            return result
        }

        Map recorded = store.record(normalized)
        result.appended = recorded.idempotency == 'inserted'
        result.existing = recorded.idempotency == 'existing'
        if (match?.observation && recorded.observation_id) {
            String linkKind = match.kind == 'conflict' ? 'conflict' : (match.kind == 'lag' ? 'recovered' : 'match')
            Map link = store.recordReconciliationLink(
                match.observation.observation_id as String,
                recorded.observation_id as String,
                linkKind,
                match.basis
            )
            result.linked = link.inserted as boolean
        }
        return result
    }

    private static Map transcriptEvent(Map turn, String sourceIde) {
        return [
            schema_version: Config.JOURNAL_EVENT_SCHEMA_VERSION,
            source_ide: sourceIde,
            source_kind: 'native_transcript',
            source_fidelity: 'native_transcript',
            source_identity: turn.source_identity?.toString(),
            session_id: turn.session_id,
            prompt_id: turn.prompt_id,
            logical_turn_key: turn.logical_turn_key,
            user_text: turn.user_text ?: '',
            assistant_text: turn.assistant_text ?: '',
            outcome_summary: turn.outcome_summary,
            mode: turn.mode,
            tickets: turn.tickets ?: [],
            impact: impactOf(turn),
            event_time: turn.event_time,
            event_time_confidence: turn.event_time_confidence ?: 'lost',
            raw_payload: turn.raw_payload ?: '',
            source_byte_offset: turn.source_byte_offset,
            source_start_line: turn.source_start_line,
            source_end_line: turn.source_end_line
        ]
    }

    private static Map findMatchingObservation(JournalStore store, Map normalized) {
        if (normalized.prompt_id) {
            List<Map> byPrompt = store.findObservationsByPromptId(normalized.prompt_id.toString())
            Map ruleWrite = byPrompt.find { it.source_kind == 'rule_write' || it.source_kind == 'deterministic_hook' }
            if (ruleWrite) {
                if (ruleWrite.content_hash == normalized.content_hash) {
                    return [observation: ruleWrite, kind: 'exact', basis: 'prompt_id']
                }
                if (withinTolerance(ruleWrite.event_time?.toString(), normalized.event_time?.toString())) {
                    return [observation: ruleWrite, kind: 'conflict', basis: 'prompt_id_time']
                }
                return [observation: ruleWrite, kind: 'lag', basis: 'prompt_id_missing_transcript_at_record_time']
            }
            Map existingTranscript = byPrompt.find { it.source_kind == 'native_transcript' }
            if (existingTranscript) {
                return [observation: existingTranscript, kind: 'exact', basis: 'prompt_id']
            }
        }

        if (normalized.session_id) {
            List<Map> sessionObservations = store.findObservationsBySession(normalized.session_id.toString(), normalized.source_ide?.toString())
            Map contentMatch = sessionObservations.find {
                it.content_hash == normalized.content_hash &&
                    (it.source_kind == 'rule_write' || it.source_kind == 'native_transcript' || it.source_kind == 'deterministic_hook')
            }
            if (contentMatch) {
                return [observation: contentMatch, kind: 'exact', basis: 'session_content']
            }
            Map timeMatch = sessionObservations.find {
                withinTolerance(it.event_time?.toString(), normalized.event_time?.toString()) &&
                    it.user_text?.toString()?.trim() == normalized.user_text?.toString()?.trim() &&
                    (it.source_kind == 'rule_write' || it.source_kind == 'deterministic_hook')
            }
            if (timeMatch) {
                if (timeMatch.content_hash == normalized.content_hash) {
                    return [observation: timeMatch, kind: 'exact', basis: 'session_time_content']
                }
                return [observation: timeMatch, kind: 'conflict', basis: 'session_time']
            }
        }
        return null
    }

    private static boolean withinTolerance(String left, String right) {
        if (!left || !right) return false
        try {
            long leftMs = Instant.parse(left).toEpochMilli()
            long rightMs = Instant.parse(right).toEpochMilli()
            return Math.abs(leftMs - rightMs) <= Config.JOURNAL_RECONCILIATION_TIME_TOLERANCE_MS
        } catch (DateTimeParseException ignored) {
            return left.take(16) == right.take(16)
        }
    }

    private static String impactOf(Map turn) {
        String assistant = turn.assistant_text?.toString() ?: ''
        if (assistant.contains('FILES CHANGED') || assistant.contains('REMOTE CHANGES')) return 'high'
        return 'none'
    }

    private static File defaultHome() {
        String home = System.getProperty('user.home')
        return home ? new File(home).canonicalFile : new File('.').canonicalFile
    }
}
