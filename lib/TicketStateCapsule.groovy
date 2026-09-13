class TicketStateCapsule {
    static Map assemble(String ticket, List<Map> chunks, Map freshness = [:]) {
        Map summary = selectTicketSummary(chunks)
        Map related = selectRelatedTicket(chunks)
        Map decisions = selectLockedDecisions(chunks)
        Map delivery = selectLatestSection(chunks, 'delivery')
        Map status = selectLatestSection(chunks, 'status')
        Map nextActions = selectNextActions(chunks)
        Map blockers = selectBlockers(chunks)
        List<Map> selected = [summary, related, decisions, delivery, status, blockers, nextActions]
            .findAll { it }
            .unique { Map section -> "${section.source_path}:${section.start_line}-${section.end_line}" }
        return [
            ticket: ticket,
            sections: selected,
            sources: selected.collect { "${it.source_path}:${it.start_line}-${it.end_line}" }.unique(),
            freshness: freshness.freshness ?: 'undetermined',
            authoritative_through: freshness.authoritative_through,
            newer_evidence_through: freshness.newer_evidence_through,
            newer_evidence: freshness.newer_evidence ?: []
        ]
    }

    static Map selectLatestSection(List<Map> chunks, String category) {
        List<Map> eligible = chunks.findAll { it.category == category }
        return eligible ? eligible.max { a, b -> compareOrder(a, b) } : null
    }

    static Map selectLockedDecisions(List<Map> chunks) {
        List<Map> eligible = chunks.findAll { it.category == 'decisions' }
        if (!eligible) {
            eligible = chunks.findAll {
                it.category == 'solutions' && ((it.lines as List<String>).join('\n') =~ /(?i)\b(?:LOCKED|DECIDED|RECOMMENDATION)\b/)
            }
        }
        return eligible ? eligible.max { a, b -> compareOrder(a, b) } : null
    }

    static Map selectBlockers(List<Map> chunks) {
        List<Map> eligible = chunks.findAll { it.category == 'blockers' }
        if (!eligible) {
            eligible = chunks.findAll {
                it.category in ['status', 'delivery', 'manual-actions'] &&
                    ((it.lines as List<String>).join('\n') =~ /(?i)\b(?:BLOCKER|BLOCKED|GATED|OUTSTANDING)\b/)
            }
        }
        return eligible ? eligible.max { a, b -> compareOrder(a, b) } : null
    }

    static Map selectNextActions(List<Map> chunks) {
        Map explicit = selectLatestSection(chunks, 'next-action')
        if (explicit) return explicit
        List<Map> eligible = chunks.findAll { ((it.lines as List<String>).join('\n') =~ /(?i)\bNEXT ACTIONS?\b/) }
        return eligible ? eligible.max { a, b -> compareOrder(a, b) } : null
    }

    static String render(Map capsule) {
        List<Map> newerEvidence = capsule.newer_evidence as List<Map> ?: []
        List<String> lines = [
            '---',
            "ticket: \"${capsule.ticket}\"",
            'category: "state"',
            'authority: "state"',
            'state: "current"',
            "freshness: \"${capsule.freshness ?: 'undetermined'}\"",
            "authoritative_through: \"${capsule.authoritative_through ?: ''}\"",
            "newer_evidence_through: \"${capsule.newer_evidence_through ?: ''}\"",
            "newer_evidence: [${newerEvidence.collect { '"' + escape(it.file_path?.toString()) + '"' }.join(',')}]",
            "sources: [${(capsule.sources as List).collect { '"' + escape(it) + '"' }.join(',')}]",
            '---',
            '',
            "# ${capsule.ticket} Current State",
            ''
        ]
        if (capsule.freshness == 'stale') {
            lines << '## AUTHORITATIVE WORKLOG STALE'
            lines << ''
            lines << "Authoritative through: `${capsule.authoritative_through}`"
            lines << "Newer evidence through: `${capsule.newer_evidence_through}`"
            newerEvidence.each { Map evidence ->
                lines << "- `${evidence.event_time}` `${evidence.file_path}`"
            }
            lines << ''
        }
        (capsule.sections as List<Map>).each { Map section ->
            lines << "## ${section.heading}"
            lines << ''
            lines << "Source: `${section.source_path}:${section.start_line}-${section.end_line}`"
            lines << ''
            lines << (section.lines as List<String>).join('\n').trim()
            lines << ''
        }
        return lines.join('\n').trim() + '\n'
    }

    static Map selectTicketSummary(List<Map> chunks) {
        List<Map> exact = chunks.findAll { normalizeHeading(it.heading?.toString()) == 'TICKET SUMMARY' }
        if (exact) return exact.max { a, b -> compareOrder(a, b) }
        List<Map> summaries = chunks.findAll {
            it.category == 'summary' && normalizeHeading(it.heading?.toString()) != 'RELATED TICKET'
        }
        return summaries ? summaries.min { a, b -> compareOrder(a, b) } : null
    }

    static Map selectRelatedTicket(List<Map> chunks) {
        List<Map> related = chunks.findAll { normalizeHeading(it.heading?.toString()).startsWith('RELATED TICKET') }
        return related ? related.max { a, b -> compareOrder(a, b) } : null
    }

    private static int compareOrder(Map left, Map right) {
        int byModified = (left.source_mtime as long) <=> (right.source_mtime as long)
        return byModified != 0 ? byModified : (left.source_order as int) <=> (right.source_order as int)
    }

    private static String normalizeHeading(String heading) {
        return (heading ?: '').toUpperCase().replaceAll(/\s+/, ' ').trim()
    }

    private static String escape(String value) {
        return (value ?: '').replace('\\', '\\\\').replace('"', '\\"')
    }
}
