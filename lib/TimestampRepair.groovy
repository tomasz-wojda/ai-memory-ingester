import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAccessor

/**
 * Normalizes prompt-log timestamps and repairs entries with incomplete time data.
 */
class TimestampRepair {

    private static final ZoneId WARSAW = ZoneId.of('Europe/Warsaw')
    private static final List<DateTimeFormatter> OFFSET_FORMATTERS = [
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss Z'),
        DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm Z')
    ]
    private static final List<DateTimeFormatter> LOCAL_FORMATTERS = [
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm:ss'),
        DateTimeFormatter.ofPattern('yyyy-MM-dd HH:mm')
    ]

    /**
     * Parses a timestamp into an ISO instant and confidence classification.
     *
     * @param rawTimestamp Source timestamp text
     * @return Map containing timestamp, confidence, instant, and zone
     */
    static Map parse(String rawTimestamp) {
        String raw = clean(rawTimestamp)
        if (!raw || raw.contains('${') || raw.contains('$(')) {
            return lost(raw)
        }

        try {
            Instant instant = Instant.parse(raw)
            return result(instant, 'exact', ZoneOffset.UTC, raw)
        } catch (DateTimeParseException ignored) {
        }

        String normalized = raw
            .replaceAll(/\s+\(local,\s*UTC([+-]\d{1,2})\)\s*$/, ' UTC$1')
            .replaceAll(/\s+\(UTC([+-]\d{1,2})(?::?(\d{2}))?\)\s*$/, ' UTC$1$2')
            .replaceAll(/\s+\((CEST|CET|Europe\/Warsaw)\)\s*$/, ' $1')
            .trim()

        def rangeMatcher = normalized =~ /^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})-\d{2}:\d{2}\s+(.*)$/
        if (rangeMatcher.matches()) {
            normalized = "${rangeMatcher.group(1)} ${rangeMatcher.group(2)} ${rangeMatcher.group(3)}"
        }

        Map namedZone = parseNamedZone(normalized)
        if (namedZone != null) return namedZone

        Map utcOffset = parseUtcOffset(normalized)
        if (utcOffset != null) return utcOffset

        for (DateTimeFormatter formatter : OFFSET_FORMATTERS) {
            try {
                OffsetDateTime value = OffsetDateTime.parse(normalized, formatter)
                return result(value.toInstant(), 'exact', value.offset, raw)
            } catch (DateTimeParseException ignored) {
            }
        }

        for (DateTimeFormatter formatter : LOCAL_FORMATTERS) {
            try {
                LocalDateTime value = LocalDateTime.parse(normalized, formatter)
                return result(value.atZone(WARSAW).toInstant(), 'local', WARSAW, raw)
            } catch (DateTimeParseException ignored) {
            }
        }

        try {
            LocalDate date = LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
            return result(date.atStartOfDay(WARSAW).toInstant(), 'date_only', WARSAW, raw)
        } catch (DateTimeParseException ignored) {
            return lost(raw)
        }
    }

    /**
     * Interpolates lost and date-only timestamps from their nearest resolved neighbours.
     *
     * @param entries Ordered entry maps containing timestamp repair results
     * @return The same ordered entries with repaired timestamp values
     */
    static List<Map> interpolate(List<Map> entries) {
        if (!entries) return entries ?: []
        entries.eachWithIndex { Map entry, int index ->
            Map repair = (entry.timestampRepair ?: parse(entry.rawTimestamp?.toString())) as Map
            entry.timestampRepair = repair
            if (!(repair.confidence in ['lost', 'date_only'])) return

            Instant before = nearest(entries, index, -1)
            Instant after = nearest(entries, index, 1)
            Instant repaired = repair.instant as Instant

            if (repair.confidence == 'lost') {
                if (before != null && after != null) {
                    long midpoint = before.toEpochMilli() + ((after.toEpochMilli() - before.toEpochMilli()) / 2L)
                    repaired = Instant.ofEpochMilli(midpoint)
                } else {
                    repaired = before ?: after
                }
            } else if (before != null || after != null) {
                LocalDate targetDate = repaired.atZone(WARSAW).toLocalDate()
                Instant candidate = before ?: after
                LocalTime neighbourTime = candidate.atZone(WARSAW).toLocalTime()
                repaired = ZonedDateTime.of(targetDate, neighbourTime, WARSAW).toInstant()
            }

            if (repaired != null) {
                repair.instant = repaired
                repair.timestamp = repaired.toString()
            }
        }
        return entries
    }

    /**
     * Resolves a source zone token.
     *
     * @param zoneToken Source zone text
     * @return Resolved zone identifier
     */
    static ZoneId zoneFor(String zoneToken) {
        String token = zoneToken?.trim()?.replaceAll(/^\(|\)$/, '')
        if (!token || token == 'CEST' || token == 'CET' || token == 'Europe/Warsaw') {
            return WARSAW
        }
        if (token == 'Z' || token.equalsIgnoreCase('UTC')) {
            return ZoneOffset.UTC
        }
        def utcMatcher = token =~ /^UTC([+-])(\d{1,2})(?::?(\d{2}))?$/
        if (utcMatcher.matches()) {
            int hours = utcMatcher.group(2).toInteger()
            int minutes = utcMatcher.group(3) ? utcMatcher.group(3).toInteger() : 0
            int totalSeconds = (hours * 3600 + minutes * 60) * (utcMatcher.group(1) == '-' ? -1 : 1)
            return ZoneOffset.ofTotalSeconds(totalSeconds)
        }
        if (token ==~ /^[+-]\d{4}$/) {
            return ZoneOffset.of("${token.substring(0, 3)}:${token.substring(3)}")
        }
        if (token ==~ /^[+-]\d{2}:\d{2}$/) {
            return ZoneOffset.of(token)
        }
        return WARSAW
    }

    private static Map parseNamedZone(String value) {
        def matcher = value =~ /^(\d{4}-\d{2}-\d{2})(?:[ T](\d{2}:\d{2})(?::(\d{2}))?)?\s+(CEST|CET|Europe\/Warsaw)$/
        if (!matcher.matches()) return null
        LocalDate date = LocalDate.parse(matcher.group(1))
        LocalTime time = matcher.group(2)
            ? LocalTime.parse("${matcher.group(2)}:${matcher.group(3) ?: '00'}")
            : LocalTime.MIDNIGHT
        ZoneId zone = zoneFor(matcher.group(4))
        String confidence = matcher.group(2) ? 'exact' : 'date_only'
        return result(ZonedDateTime.of(date, time, zone).toInstant(), confidence, zone, value)
    }

    private static Map parseUtcOffset(String value) {
        def matcher = value =~ /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})(?::(\d{2}))?\s+UTC([+-]\d{1,2})(?::?(\d{2}))?$/
        if (!matcher.matches()) return null
        LocalDate date = LocalDate.parse(matcher.group(1))
        LocalTime time = LocalTime.parse("${matcher.group(2)}:${matcher.group(3) ?: '00'}")
        String zoneText = "UTC${matcher.group(4)}${matcher.group(5) ?: ''}"
        ZoneId zone = zoneFor(zoneText)
        return result(ZonedDateTime.of(date, time, zone).toInstant(), 'exact', zone, value)
    }

    private static Instant nearest(List<Map> entries, int start, int direction) {
        int index = start + direction
        while (index >= 0 && index < entries.size()) {
            Map other = entries[index].timestampRepair as Map
            if (other?.instant instanceof Instant && other.confidence in ['exact', 'local']) {
                return other.instant as Instant
            }
            index += direction
        }
        return null
    }

    private static Map result(Instant instant, String confidence, ZoneId zone, String raw) {
        return [timestamp: instant.toString(), confidence: confidence, instant: instant, zone: zone.id, raw: raw]
    }

    private static Map lost(String raw) {
        return [timestamp: null, confidence: 'lost', instant: null, zone: WARSAW.id, raw: raw]
    }

    private static String clean(String value) {
        String cleaned = value?.trim()
        if (!cleaned) return cleaned
        cleaned = cleaned.replaceFirst(/^(?:TIMESTAMP|Timestamp)\s*:\s*/, '')
        return cleaned.replaceAll(/^\[|\]$/, '').trim()
    }
}
