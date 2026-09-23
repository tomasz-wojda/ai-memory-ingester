class JournalProjector {

    static Map project(
        JournalStore store,
        File outputRoot,
        boolean dryRun = false,
        String projectionName = Config.JOURNAL_PROJECTION_NAME
    ) {
        long started = System.currentTimeMillis()
        File indexFile = new File(new File(outputRoot, '.index'), Config.CHAT_HASH_INDEX_NAME)
        Map<String, Map> hashIndex = TurnEmitter.loadHashIndex(indexFile)
        long sequence = (hashIndex.values().collect { (it.sequence ?: 0L) as long }.max() ?: 0L) + 1L
        long cursor = store.getProjectionCursor(projectionName)
        List<Map> observations = store.canonicalObservationsAfterSequence(cursor)
        Map totals = [
            canonical_scanned: observations.size(),
            emitted: 0,
            duplicate: 0,
            refreshed: 0,
            skipped_dross: 0,
            skipped_excluded: 0,
            cursor_before: cursor,
            cursor_after: cursor
        ]

        observations.each { Map observation ->
            Map entry = entryFromObservation(observation)
            Map classified = TurnClassifier.classify(entry, Config.CHAT_SPILL_BYTES)
            if (classified.excluded) {
                totals.skipped_excluded++
                return
            }
            classified.turnHash = observation.turn_hash?.toString() ?: TurnDocument.hash(classified)
            classified.journalObservationId = observation.observation_id
            classified.journalLogicalTurnKey = observation.logical_turn_key
            classified.journalSequence = observation.sequence
            classified.journalSourceIde = observation.source_ide
            classified.journalSourceFidelity = observation.source_fidelity
            long targetSequence = resolveSequence(classified.turnHash.toString(), hashIndex, sequence, observation)
            String spillPath = null
            if (classified.spillRequired) {
                Map artifactEntry = artifactEntryFrom(classified, observation)
                Map artifactResult = TurnEmitter.emit(
                    outputRoot, artifactEntry, 'artifacts', targetSequence, hashIndex, null, dryRun, false
                )
                if (artifactResult.emitted) totals.emitted++
                else totals.duplicate++
                spillPath = artifactResult.path?.toString()
            }
            if (classified.tier == 'dross') {
                totals.skipped_dross++
                return
            }
            Map turnResult = TurnEmitter.emit(
                outputRoot, classified, classified.tier.toString(), targetSequence, hashIndex, spillPath, dryRun, false
            )
            if (turnResult.emitted) totals.emitted++
            else if (turnResult.refreshed) totals.refreshed++
            else totals.duplicate++
            if (turnResult.emitted || (turnResult.sequence as long) >= sequence) {
                sequence = Math.max(sequence, (turnResult.sequence as long) + 1L)
            }
        }

        long maxSequence = observations ? (observations*.sequence.max() as long) : cursor
        if (!dryRun) {
            TurnEmitter.saveHashIndex(indexFile, hashIndex)
            store.setProjectionCursor(maxSequence, projectionName)
        }
        totals.cursor_after = maxSequence
        return [
            status: 'ok',
            totals: totals,
            elapsed_ms: System.currentTimeMillis() - started
        ]
    }

    static Map rebuild(
        JournalStore store,
        File outputRoot,
        boolean dryRun = false,
        String projectionName = Config.JOURNAL_PROJECTION_NAME
    ) {
        if (!dryRun) {
            store.setProjectionCursor(0L, projectionName)
        }
        return project(store, outputRoot, dryRun, projectionName)
    }

    private static Map entryFromObservation(Map observation) {
        Map repair = TimestampRepair.parse(observation.event_time?.toString())
        if (!repair.timestamp && observation.event_time) {
            repair = [timestamp: observation.event_time, confidence: observation.event_time_confidence ?: 'lost']
        }
        return [
            user: observation.user_text ?: '',
            assistant: observation.assistant_text ?: '',
            rawBody: observation.raw_payload ?: '',
            rawMode: observation.mode,
            ticketKeys: observation.tickets ?: [],
            impact: observation.impact ?: 'none',
            sourcePath: observation.source_identity ?: 'journal',
            startLine: observation.source_start_line ?: 0,
            endLine: observation.source_end_line ?: 0,
            byteOffset: observation.source_byte_offset ?: 0L,
            timestampRepair: repair,
            dialect: 'Journal',
            sections: sectionsFromPayload(observation.raw_payload?.toString()),
            turnHash: observation.turn_hash
        ]
    }

    private static Map artifactEntryFrom(Map classified, Map observation) {
        Map artifact = new LinkedHashMap(classified)
        artifact.dialect = 'Artifact'
        artifact.tier = 'artifacts'
        artifact.user = classified.user
        artifact.assistant = classified.rawBody ?: classified.assistant
        artifact.sections = [:]
        artifact.turnHash = TurnDocument.hash("artifact ${artifact.assistant}")
        artifact.journalObservationId = observation.observation_id
        return artifact
    }

    private static long resolveSequence(String turnHash, Map<String, Map> hashIndex, long nextSequence, Map observation) {
        if (hashIndex.containsKey(turnHash)) {
            return hashIndex[turnHash].sequence as long
        }
        if (observation.legacy_sequence) {
            return observation.legacy_sequence as long
        }
        return nextSequence
    }

    private static Map sectionsFromPayload(String payload) {
        if (!payload) return [:]
        Map sections = [:]
        def matcher = (payload =~ '(?m)^#{1,3}\\s+([A-Z][A-Z0-9 /&_-]{2,40})\\s*$')
        List<Integer> starts = []
        List<String> labels = []
        while (matcher.find()) {
            starts << matcher.start()
            labels << matcher.group(1)
        }
        if (!starts) return [:]
        starts.eachWithIndex { int start, int index ->
            int end = index + 1 < starts.size() ? starts[index + 1] : payload.length()
            sections[labels[index]] = payload.substring(start, end).replaceFirst(/(?m)^###?\s+[^\n]+\n?/, '').trim()
        }
        return sections
    }
}
