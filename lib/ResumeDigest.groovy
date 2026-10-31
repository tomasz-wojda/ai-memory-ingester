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

    /**
     * Reads canonical journal observations that are not excluded from memory.
     *
     * @param store Journal store
     * @return Observations, oldest first
     */
    static List<Map> canonicalObservations(JournalStore store) {
        return store.canonicalObservations().findAll { Map observation -> !MemoryExclusion.matches(observation) }
    }

    /**
     * Groups observations by canonical ticket key.
     *
     * @param observations Canonical observations, oldest first
     * @return Observations of each ticket, oldest first
     */
    static Map<String, List<Map>> observationsByTicket(List<Map> observations) {
        Map<String, List<Map>> grouped = [:]
        observations.each { Map observation ->
            ((observation.tickets ?: []) as List)
                .collect { TicketKey.canonical(it?.toString()) }
                .findAll { it != null }
                .unique()
                .each { String key -> grouped.computeIfAbsent(key) { [] } << observation }
        }
        return grouped
    }

    /**
     * Lists tickets that have an active ticket state file.
     *
     * @param workspaceRoot Workspace root
     * @return Maps with key, lifecycle, phase, next_action and updated_at, sorted by key
     */
    static List<Map> activeTickets(File workspaceRoot) {
        File stateDir = new File(workspaceRoot, '.ai-worklog/state')
        if (!stateDir.isDirectory()) return []
        List<Map> tickets = []
        stateDir.listFiles({ File f -> f.name.endsWith('.json') } as FileFilter)?.sort { it.name }?.each { File file ->
            Map state
            try {
                state = new JsonSlurper().parse(file) as Map
            } catch (Exception ignored) {
                return
            }
            String key = TicketKey.canonical(state.ticket_key?.toString() ?: file.name.replace('.json', ''))
            if (!key) return
            String lifecycle = state.lifecycle?.toString()
            boolean active = lifecycle
                ? lifecycle in ['active', 'waiting_decision', 'queued']
                : hasOpenWorklog(workspaceRoot, key)
            if (!active) return
            tickets << [
                key: key,
                lifecycle: lifecycle ?: 'active',
                phase: state.phase?.toString() ?: '',
                next_action: state.next_action?.toString() ?: '',
                updated_at: state.updated_at?.toString() ?: '',
                state: state,
                state_file: file
            ]
        }
        return tickets
    }

    /**
     * Builds the per-ticket resume document.
     *
     * @param outputRoot Worklog-chat root
     * @param workspaceRoot Workspace root
     * @param ticket Canonical ticket key
     * @param observations Journal observations of the ticket, oldest first
     * @param budgetBytes Byte budget of the document
     * @return Map with ticket, content, latest_sequence and turns
     */
    static Map buildTicketResume(
        File outputRoot,
        File workspaceRoot,
        String ticket,
        List<Map> observations,
        int budgetBytes = Config.TICKET_RESUME_BUDGET_BYTES
    ) {
        String facet = TicketKey.facet(ticket)
        File capsule = new File(outputRoot, "ticket-state/${facet}.md")
        File stateFile = new File(workspaceRoot, ".ai-worklog/state/${ticket}.json")
        Map state = readState(stateFile)
        Map metadata = capsule.exists() ? TicketFreshness.frontMatter(capsule.getText('UTF-8')) : [:]
        String freshness = metadata.freshness ?: 'undetermined'
        long latestSequence = observations ? observations.collect { it.sequence as long }.max() : 0L

        List<String> head = []
        head << "# ${ticket} Resume"
        head << ''
        head << '## Current state'
        head << ''
        head << (capsule.exists() ? clipToLine(stripFrontMatter(capsule.getText('UTF-8')), CURRENT_STATE_BYTES, capsule.name) : 'No capsule yet.')
        head << ''
        head << '## Next action'
        head << ''
        head << (state.next_action ? state.next_action.toString().trim() : 'not set')
        head << ''
        head << '## Open decisions'
        head << ''
        List<String> open = openItems(state)
        head << (open ? open.join('\n') : 'None.')
        head << ''
        head << '## Recent turns'
        head << ''
        List<String> tail = ['', '## Sources', '']
        String capsulePath = relativePath(workspaceRoot, capsule)
        String statePath = relativePath(workspaceRoot, stateFile)
        if (capsule.exists()) tail << "- `${capsulePath}`"
        if (stateFile.exists()) tail << "- `${statePath}`"

        List<Map> recent = observations.reverse().take(Config.TICKET_RESUME_RECENT_TURNS).collect { journalItem(it) }
        String fixedHead = head.join('\n') + '\n'
        int reserved = 700 + bytes(fixedHead) + bytes(tail.join('\n')) + recent.size() * 40
        int available = Math.max(0, budgetBytes - reserved)
        Map turns = assemble(recent, [], [], available + 320)
        List<String> body = []
        (turns.items as List<Map>).each { Map item ->
            body << '---'
            body << ''
            body << "Source: `${item.file_path}`"
            body << ''
            body << contentOf(item).trim()
            body << ''
            tail << "- `${item.file_path}`"
        }
        if (!body) body << 'No recorded turns.'
        String frontMatter = [
            '---',
            "ticket: \"${ticket}\"",
            "generated_at: \"${Instant.now()}\"",
            "budget_bytes: ${budgetBytes}",
            "turns: ${turns.turn_count}",
            "latest_sequence: ${latestSequence}",
            "capsule: \"${capsule.exists() ? capsulePath : ''}\"",
            "state_file: \"${stateFile.exists() ? statePath : ''}\"",
            "freshness: \"${freshness}\"",
            '---',
            ''
        ].join('\n')
        String content = frontMatter + fixedHead + body.join('\n') + '\n' + tail.join('\n') + '\n'
        return [ticket: ticket, content: fitBudget(content, budgetBytes), latest_sequence: latestSequence, turns: turns.turn_count]
    }

    /**
     * Tells whether an existing per-ticket resume is still current.
     *
     * @param file Existing resume file
     * @param latestSequence Latest journal sequence of the ticket
     * @param inputs Files the resume is built from
     * @return true when the file can stay as it is
     */
    static boolean ticketResumeCurrent(File file, long latestSequence, List<File> inputs) {
        if (!file.exists()) return false
        Map metadata = TicketFreshness.frontMatter(file.getText('UTF-8'))
        if ((metadata.latest_sequence?.toString() ?: '') != latestSequence.toString()) return false
        return inputs.every { File input -> !input.exists() || input.lastModified() <= file.lastModified() }
    }

    /**
     * Builds the index form of RESUME.md.
     *
     * @param outputRoot Worklog-chat root
     * @param workspaceRoot Workspace root
     * @param budgetBytes Byte budget of the index
     * @param observations Canonical journal observations, oldest first
     * @return Rendered Markdown index
     */
    static String buildIndex(File outputRoot, File workspaceRoot, int budgetBytes, List<Map> observations) {
        List<Map> tickets = activeTickets(workspaceRoot)
        List<String> blocks = []
        tickets.each { Map ticket ->
            String key = ticket.key
            File capsule = new File(outputRoot, "ticket-state/${TicketKey.facet(key)}.md")
            Map metadata = capsule.exists() ? TicketFreshness.frontMatter(capsule.getText('UTF-8')) : [:]
            File resume = new File(outputRoot, "${Config.TICKET_RESUME_DIR}/${key}.md")
            blocks << [
                "### ${key}",
                '',
                "- Lifecycle: ${ticket.lifecycle}",
                "- Phase: ${ticket.phase ?: 'unknown'}",
                "- Next action: ${oneLine(ticket.next_action, 300)}",
                "- Freshness: ${metadata.freshness ?: 'undetermined'}",
                "- Updated: ${ticket.updated_at ?: 'unknown'}",
                "- Resume: `${relativePath(workspaceRoot, resume)}`",
                capsule.exists() ? "- Capsule: `${relativePath(workspaceRoot, capsule)}`" : null,
                ''
            ].findAll { it != null }.join('\n')
        }
        List<Map> untagged = observations.findAll { Map observation ->
            !((observation.tickets ?: []) as List).any { TicketKey.canonical(it?.toString()) }
        }.reverse().take(Config.RESUME_RECENT_TURNS).collect { journalItem(it) }
        String activeSection = "## Active tickets\n\n" + (blocks ? blocks.join('\n') : 'None.\n')
        int reserved = 400 + bytes(activeSection)
        Map turns = assemble(untagged, [], [], Math.max(0, budgetBytes - reserved) + 320)
        List<String> lines = [
            '# Worklog Chat Resume',
            '',
            "Generated: ${Instant.now()}",
            "Turns: ${turns.turn_count ?: 0}",
            "Content bytes: ${turns.content_bytes ?: 0}",
            "Budget bytes: ${budgetBytes}",
            '',
            activeSection,
            '## Recent turns without a ticket',
            ''
        ]
        (turns.items as List<Map>).each { Map item ->
            lines << '---'
            lines << ''
            lines << "Source: `${item.file_path}`"
            lines << ''
            lines << contentOf(item).trim()
            lines << ''
        }
        if (!turns.items) lines << 'None.'
        return fitBudget(lines.join('\n').trim() + '\n', budgetBytes)
    }

    /**
     * Writes a file through a temporary file and a rename.
     *
     * @param target Destination file
     * @param content New content
     */
    static void writeAtomic(File target, String content) {
        target.parentFile.mkdirs()
        File temporary = new File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.setText(content, 'UTF-8')
        java.nio.file.Files.move(temporary.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private static final int CURRENT_STATE_BYTES = 5000

    private static Map journalItem(Map observation) {
        return [
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

    private static Map readState(File file) {
        if (!file.exists()) return [:]
        try {
            return (new JsonSlurper().parse(file) as Map) ?: [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    private static List<String> openItems(Map state) {
        List<String> lines = []
        ((state.decisions instanceof List ? state.decisions : []) as List).each { Object item ->
            if (item instanceof Map && item.status != 'resolved') {
                lines << "- Decision ${item.id ?: ''} (${item.status ?: 'open'}): ${oneLine(item.description?.toString(), 300)}".replace('Decision  ', 'Decision ')
            }
        }
        ((state.blockers instanceof List ? state.blockers : []) as List).each { Object item ->
            if (item instanceof Map && item.status == 'active') {
                lines << "- Blocker: ${oneLine(item.description?.toString(), 300)}"
            }
        }
        return lines
    }

    private static boolean hasOpenWorklog(File workspaceRoot, String key) {
        File dir = new File(workspaceRoot, 'worklog')
        return dir.isDirectory() && (dir.listFiles({ File f -> f.isFile() && f.name.endsWith("_${key}.log") } as FileFilter)?.length ?: 0) > 0
    }

    private static String stripFrontMatter(String markdown) {
        if (!markdown.startsWith('---')) return markdown.trim()
        int end = markdown.indexOf('\n---', 3)
        return end < 0 ? markdown.trim() : markdown.substring(end + 4).trim()
    }

    private static String clipToLine(String text, int limit, String name) {
        if (bytes(text) <= limit) return text
        StringBuilder out = new StringBuilder()
        int used = 0
        for (String line : text.readLines()) {
            int size = bytes(line) + 1
            if (used + size > limit) break
            out << line << '\n'
            used += size
        }
        return out.toString().trim() + "\n\n(truncated; full capsule in ${name})"
    }

    private static String oneLine(String text, int limit) {
        String flat = (text ?: '').replaceAll(/\s+/, ' ').trim()
        return flat.length() <= limit ? flat : flat.substring(0, limit - 3) + '...'
    }

    private static String relativePath(File root, File file) {
        java.nio.file.Path base = root.toPath().toAbsolutePath().normalize()
        java.nio.file.Path target = file.toPath().toAbsolutePath().normalize()
        if (!target.startsWith(base)) return target.toString()
        return base.relativize(target).toString().replace('\\', '/')
    }

    private static int bytes(String text) {
        return (text ?: '').getBytes(StandardCharsets.UTF_8).length
    }

    private static String fitBudget(String text, int budget) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8)
        if (data.length <= budget) return text
        return new String(data, 0, Math.max(0, budget - 1), StandardCharsets.UTF_8).replaceAll(/�$/, '') + '\n'
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
