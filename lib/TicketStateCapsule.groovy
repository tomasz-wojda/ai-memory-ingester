class TicketStateCapsule {
    static Map assemble(String ticket, List<Map> chunks) {
        Map summary = selectLatestSection(chunks, 'summary')
        Map decisions = selectLockedDecisions(chunks)
        Map delivery = selectLatestSection(chunks, 'delivery')
        Map status = selectLatestSection(chunks, 'status')
        Map nextActions = selectNextActions(chunks)
        Map blockers = selectBlockers(chunks)
        List<Map> selected = [summary, decisions, delivery, status, blockers, nextActions]
            .findAll { it }
            .unique { Map section -> "${section.source_path}:${section.start_line}-${section.end_line}" }
        return [ticket: ticket, sections: selected, sources: selected.collect { "${it.source_path}:${it.start_line}-${it.end_line}" }.unique()]
    }

    static Map selectLatestSection(List<Map> chunks, String category) {
        List<Map> eligible = chunks.findAll { it.category == category }
        return eligible ? eligible.max { a, b -> compareOrder(a, b) } : null
    }

    static Map selectLockedDecisions(List<Map> chunks) {
        List<Map> eligible = chunks.findAll {
            it.category == 'decisions' || ((it.lines as List<String>).join('\n') =~ /(?i)\b(?:LOCKED|DECIDED|RECOMMENDATION)\b/)
        }
        return eligible ? eligible.max { a, b -> compareOrder(a, b) } : null
    }

    static Map selectBlockers(List<Map> chunks) {
        List<Map> eligible = chunks.findAll {
            it.category in ['status', 'delivery', 'manual-actions'] &&
                ((it.lines as List<String>).join('\n') =~ /(?i)\b(?:BLOCKER|BLOCKED|GATED|OUTSTANDING)\b/)
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
        List<String> lines = [
            '---',
            "ticket: \"${capsule.ticket}\"",
            'category: "state"',
            'authority: "state"',
            'state: "current"',
            "sources: [${(capsule.sources as List).collect { '"' + escape(it) + '"' }.join(',')}]",
            '---',
            '',
            "# ${capsule.ticket} Current State",
            ''
        ]
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

    private static int compareOrder(Map left, Map right) {
        int byModified = (left.source_mtime as long) <=> (right.source_mtime as long)
        return byModified != 0 ? byModified : (left.source_order as int) <=> (right.source_order as int)
    }

    private static String escape(String value) {
        return (value ?: '').replace('\\', '\\\\').replace('"', '\\"')
    }
}
