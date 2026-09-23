import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Assembles and materializes a byte-bounded resume context digest.
 */
class ResumeDigest {

    /**
     * Assembles priority-ordered recent, relevant, and high-impact items.
     *
     * @param recent Recent turn items
     * @param relevant Ticket-relevant items
     * @param highImpact High-impact items
     * @param budgetBytes Total output byte budget
     * @return Assembly map with selected items and byte counts
     */
    static Map assemble(
        List<Map> recent,
        List<Map> relevant = [],
        List<Map> highImpact = [],
        int budgetBytes = Config.RESUME_BUDGET_BYTES
    ) {
        int reservedBytes = 320
        int contentBudget = Math.max(0, budgetBytes - reservedBytes)
        int used = 0
        List<Map> selected = []
        Set<String> identities = [] as Set<String>

        [recent ?: [], relevant ?: [], highImpact ?: []].each { List<Map> tier ->
            tier.each { Map item ->
                String identity = identityOf(item)
                if (identities.contains(identity)) return
                String content = contentOf(item)
                int bytes = content.getBytes(StandardCharsets.UTF_8).length
                if (bytes == 0 || used + bytes > contentBudget) return
                selected << new LinkedHashMap(item) + [content: content]
                identities << identity
                used += bytes
            }
        }
        return [
            items: selected,
            content_bytes: used,
            budget_bytes: budgetBytes,
            turn_count: selected.size()
        ]
    }

    /**
     * Assembles a digest directly from emitted turn files.
     *
     * @param outputRoot Worklog-chat root
     * @param budgetBytes Total output byte budget
     * @return Assembly map
     */
    static Map assemble(File outputRoot, int budgetBytes = Config.RESUME_BUDGET_BYTES, JournalStore journalStore = null) {
        if (journalStore) {
            Map journalAssembly = assembleFromJournal(journalStore, outputRoot, budgetBytes)
            if (journalAssembly.items) return journalAssembly
        }
        File turnsRoot = new File(outputRoot, 'turns')
        if (!turnsRoot.exists()) return assemble([], [], [], budgetBytes)
        List<Map> items = []
        turnsRoot.eachFileRecurse(groovy.io.FileType.FILES) { File file ->
            if (file.name.endsWith('.md')) {
                String relative = outputRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                items << [
                    id: sequenceOf(file.name),
                    source_archive: 'chat:turns',
                    file_path: relative.substring('turns/'.length()),
                    file_name: file.name,
                    extension: '.md',
                    rank: null,
                    snippet: '',
                    content: file.getText('UTF-8'),
                    sequence: sequenceOf(file.name)
                ]
            }
        }
        items.sort { a, b -> (b.sequence as long) <=> (a.sequence as long) }
        List<Map> recent = items.take(Config.RESUME_RECENT_TURNS)
        String dominant = dominantTicket(recent)
        List<Map> authoritative = dominant ? authoritativeItems(outputRoot, dominant) : []
        List<Map> relevant = dominant ? items.findAll { ticketOf(it) == dominant } : []
        List<Map> impact = dominant
            ? items.findAll { ticketOf(it) == dominant && it.file_path?.toString()?.contains('/impact-high/') }
            : items.findAll { it.file_path?.toString()?.contains('/impact-high/') }
        return assemble(authoritative + recent, relevant, impact, budgetBytes)
    }

    /**
     * Renders an assembly as Markdown.
     *
     * @param assembly Assembly map
     * @return Byte-bounded Markdown digest
     */
    static String render(Map assembly) {
        int budget = (assembly.budget_bytes ?: Config.RESUME_BUDGET_BYTES) as int
        List<String> lines = [
            '# Worklog Chat Resume',
            '',
            "Generated: ${Instant.now()}",
            "Turns: ${assembly.turn_count ?: 0}",
            "Content bytes: ${assembly.content_bytes ?: 0}",
            "Budget bytes: ${budget}",
            ''
        ]
        (assembly.items as List ?: []).each { Map item ->
            lines << "---"
            lines << ''
            lines << "Source: `${item.file_path ?: item.file_name ?: 'unknown'}`"
            lines << ''
            lines << contentOf(item).trim()
            lines << ''
        }
        String rendered = lines.join('\n').trim() + '\n'
        byte[] bytes = rendered.getBytes(StandardCharsets.UTF_8)
        if (bytes.length <= budget) return rendered
        int contentLimit = Math.max(0, budget - 1)
        return new String(bytes, 0, contentLimit, StandardCharsets.UTF_8).replaceAll(/\uFFFD$/, '') + '\n'
    }

    /**
     * Writes a rendered assembly to RESUME.md.
     *
     * @param outputRoot Worklog-chat root
     * @param assembly Assembly map
     * @return Written digest file
     */
    static File write(File outputRoot, Map assembly) {
        outputRoot.mkdirs()
        File digest = new File(outputRoot, Config.CHAT_RESUME_DIGEST_NAME)
        digest.setText(render(assembly), 'UTF-8')
        return digest
    }

    /**
     * Assembles and writes a digest from emitted files.
     *
     * @param outputRoot Worklog-chat root
     * @param budgetBytes Total byte budget
     * @return Written digest file
     */
    static File write(File outputRoot, int budgetBytes = Config.RESUME_BUDGET_BYTES, JournalStore journalStore = null) {
        return write(outputRoot, assemble(outputRoot, budgetBytes, journalStore))
    }

    static Map assembleFromJournal(JournalStore store, File outputRoot, int budgetBytes = Config.RESUME_BUDGET_BYTES) {
        List<Map> canonical = store.canonicalObservations().findAll { Map observation ->
            !MemoryExclusion.matches(observation)
        }
        if (!canonical) return assemble([], [], [], budgetBytes)
        List<Map> items = canonical.collect { Map observation ->
            [
                id: observation.sequence as long,
                source_archive: 'chat:turns',
                file_path: "journal/${observation.observation_id}.md",
                file_name: "turn-${observation.sequence}.md",
                extension: '.md',
                rank: null,
                snippet: '',
                content: journalTurnMarkdown(observation),
                sequence: observation.sequence as long
            ]
        }
        items.sort { a, b -> (b.sequence as long) <=> (a.sequence as long) }
        List<Map> recent = items.take(Config.RESUME_RECENT_TURNS)
        String dominant = dominantTicket(recent)
        List<Map> authoritative = dominant ? authoritativeItems(outputRoot, dominant) : []
        List<Map> relevant = dominant ? items.findAll { ticketOf(it) == dominant } : []
        List<Map> impact = dominant
            ? items.findAll { ticketOf(it) == dominant && (observationImpact(it) == 'high') }
            : items.findAll { observationImpact(it) == 'high' }
        return assemble(authoritative + recent, relevant, impact, budgetBytes)
    }

    private static String journalTurnMarkdown(Map observation) {
        Map entry = [
            user: observation.user_text ?: '',
            assistant: observation.assistant_text ?: '',
            rawMode: observation.mode,
            ticketKeys: observation.tickets ?: [],
            impact: observation.impact ?: 'none',
            sourcePath: observation.source_identity ?: 'journal',
            startLine: observation.source_start_line ?: 0,
            endLine: observation.source_end_line ?: 0,
            timestampRepair: [
                timestamp: observation.event_time ?: observation.recorded_at,
                confidence: observation.event_time_confidence ?: 'lost'
            ],
            journalObservationId: observation.observation_id,
            journalLogicalTurnKey: observation.logical_turn_key,
            journalSequence: observation.sequence,
            journalSourceIde: observation.source_ide,
            journalSourceFidelity: observation.source_fidelity,
            turnHash: observation.turn_hash
        ]
        return TurnDocument.render(entry, observation.sequence as long)
    }

    private static String observationImpact(Map item) {
        def matcher = contentOf(item) =~ /(?m)^impact:\s*"?([^"\s]+)"?\s*$/
        return matcher.find() ? matcher.group(1) : 'none'
    }

    private static String identityOf(Map item) {
        def matcher = contentOf(item) =~ /(?m)^turn_hash:\s*"?([^"\s]+)"?\s*$/
        if (matcher.find()) return matcher.group(1)
        return "${item.origin_db ?: ''}:${item.id ?: ''}:${item.file_path ?: item.file_name ?: ''}"
    }

    private static String contentOf(Map item) {
        return item.content?.toString() ?: item.snippet?.toString() ?: ''
    }

    private static long sequenceOf(String name) {
        def matcher = name =~ /(?:turn|artifact)-(\d+)\.md$/
        return matcher.find() ? matcher.group(1).toLong() : 0L
    }

    private static String ticketOf(Map item) {
        String path = item.file_path?.toString() ?: ''
        String fromPath = path.tokenize('/').find { it ==~ /[a-z]+[0-9]+|noticket/ && it != 'journal' }
        if (fromPath) return fromPath
        def matcher = contentOf(item) =~ /(?m)^tickets:\s*(\[[^\]]*\])/
        if (matcher.find()) {
            List tickets = (new JsonSlurper().parseText(matcher.group(1)) ?: []) as List
            if (tickets) return TicketKey.facet(tickets[0]?.toString())
        }
        return null
    }

    private static String dominantTicket(List<Map> items) {
        Map<String, Integer> counts = [:].withDefault { 0 }
        items.each { Map item ->
            String ticket = ticketOf(item)
            if (ticket && ticket != 'noticket') counts[ticket] = counts[ticket] + 1
        }
        if (!counts) return null
        def winner = counts.max { a, b -> a.value <=> b.value }
        return winner.value >= Math.ceil(items.size() / 2.0d) ? winner.key : null
    }

    private static List<Map> authoritativeItems(File outputRoot, String ticket) {
        List<File> files = []
        File capsule = new File(outputRoot, "ticket-state/${ticket}.md")
        if (capsule.exists()) files << capsule
        ['status', 'next-action'].each { String category ->
            File root = new File(outputRoot, "worklogs/${ticket}/${category}")
            if (root.exists()) {
                List<File> categoryFiles = []
                root.eachFile(groovy.io.FileType.FILES) { File file -> if (file.name.endsWith('.md')) categoryFiles << file }
                if (categoryFiles) files << categoryFiles.sort { a, b -> b.name <=> a.name }[0]
            }
        }
        List<Map> items = []
        if (capsule.exists()) {
            String capsuleContent = capsule.getText('UTF-8')
            Map metadata = TicketFreshness.frontMatter(capsuleContent)
            if (metadata.freshness == 'stale') {
                List<String> paths = []
                def pathMatcher = (metadata.newer_evidence ?: '') =~ /"([^"]+)"/
                while (pathMatcher.find()) paths << pathMatcher.group(1)
                String warning = [
                    '# AUTHORITATIVE WORKLOG STALE',
                    '',
                    "Authoritative through: `${metadata.authoritative_through ?: 'unknown'}`",
                    "Newer evidence through: `${metadata.newer_evidence_through ?: 'unknown'}`",
                    '',
                    paths.collect { "- `${it}`" }.join('\n')
                ].join('\n').trim() + '\n'
                items << [
                    id: -1000L,
                    source_archive: Config.WORKLOG_STATE_ARCHIVE,
                    file_path: "ticket-state/${ticket}-freshness-warning.md",
                    file_name: "${ticket}-freshness-warning.md",
                    extension: '.md',
                    rank: null,
                    content: warning
                ]
            }
        }
        files.eachWithIndex { File file, int index ->
            items << [
                id: -1L - index,
                source_archive: index == 0 ? Config.WORKLOG_STATE_ARCHIVE : Config.WORKLOG_CHUNKS_ARCHIVE,
                file_path: outputRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/'),
                file_name: file.name,
                extension: '.md',
                rank: null,
                content: file.getText('UTF-8')
            ]
        }
        return items
    }
}
