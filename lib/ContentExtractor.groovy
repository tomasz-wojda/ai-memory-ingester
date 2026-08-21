@Grab(group='org.apache.pdfbox', module='pdfbox', version='2.0.30')
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

/**
 * ContentExtractor.groovy
 *
 * Provides extension-based text extraction from archive entry streams.
 * Classifies each file by its extension into an extraction strategy,
 * reads text content with an encoding fallback chain, and detects
 * binary content in unknown file types.
 *
 * Usage: loaded via classpath by scripts (`groovy -cp lib <script>.groovy`).
 */

/**
 * Enumeration of extraction strategies applied to archive entries.
 */
enum ExtractionStrategy {
    /** File is plain text — read directly with encoding fallback. */
    TEXT_PLAIN,
    /** File requires library extraction (POI, PDFBox) — deferred, returns placeholder. */
    DOCUMENT,
    /** File is compiled binary or media — skip entirely. */
    BINARY_SKIP,
    /** Extension not recognised — attempt text read with binary detection. */
    UNKNOWN
}

/**
 * Stateless utility class for classifying and extracting text content
 * from archive entry streams based on file extension.
 */
class ContentExtractor {

    /**
     * Determines the extraction strategy for a given file extension.
     *
     * @param extension Lowercase extension including dot (e.g., ".java")
     * @return The ExtractionStrategy to apply
     */
    static ExtractionStrategy classify(String extension) {
        if (!extension || extension.isEmpty()) {
            return ExtractionStrategy.UNKNOWN
        }
        if (extension == '.pdf') {
            return ExtractionStrategy.TEXT_PLAIN
        }
        if (Config.TEXT_EXTENSIONS.contains(extension)) {
            return ExtractionStrategy.TEXT_PLAIN
        }
        if (Config.BINARY_EXTENSIONS.contains(extension)) {
            return ExtractionStrategy.BINARY_SKIP
        }
        if (Config.DOCUMENT_EXTENSIONS.contains(extension)) {
            return ExtractionStrategy.DOCUMENT
        }
        return ExtractionStrategy.UNKNOWN
    }

    /**
     * Extracts text content from an InputStream based on the file extension.
     *
     * Returns:
     *   - The text content for TEXT_PLAIN (including .pdf) and successfully-read UNKNOWN files
     *   - A placeholder string for DOCUMENT files (extraction deferred)
     *   - null for BINARY_SKIP files and UNKNOWN files detected as binary
     *
     * Content exceeding Config.MAX_FILE_SIZE is truncated.
     *
     * @param stream    InputStream of the archive entry content
     * @param extension Lowercase file extension (e.g., ".java")
     * @param sizeBytes Uncompressed size in bytes (used for pre-check)
     * @return Extracted text content, placeholder string, or null
     */
    static String extractText(InputStream stream, String extension, long sizeBytes) {
        if (extension == '.pdf') {
            return extractPdf(stream)
        }

        ExtractionStrategy strategy = classify(extension)

        switch (strategy) {
            case ExtractionStrategy.BINARY_SKIP:
                return null

            case ExtractionStrategy.DOCUMENT:
                return "[DOCUMENT: extraction deferred — ${extension}]"

            case ExtractionStrategy.TEXT_PLAIN:
                return readTextWithEncoding(stream, sizeBytes)

            case ExtractionStrategy.UNKNOWN:
                return readUnknownContent(stream, sizeBytes)

            default:
                return null
        }
    }

    /**
     * Extracts full text from a PDF stream with structured page boundaries.
     * Uses Apache PDFBox (PDDocument + PDFTextStripper).
     *
     * @param stream InputStream of the PDF file
     * @return Extracted plain text with page headers, or null on error
     */
    static String extractPdf(InputStream stream) {
        try {
            byte[] bytes = readBytesLimited(stream)
            if (bytes == null || bytes.length == 0) return null

            PDDocument pdDoc = PDDocument.load(bytes)
            try {
                int numPages = pdDoc.numberOfPages
                if (numPages <= 0) return null

                PDFTextStripper stripper = new PDFTextStripper()
                StringBuilder sb = new StringBuilder()

                for (int page = 1; page <= numPages; page++) {
                    stripper.startPage = page
                    stripper.endPage = page
                    String pageText = stripper.getText(pdDoc)
                    if (pageText != null && !pageText.trim().isEmpty()) {
                        sb.append("=== [Page ").append(page).append("] ===\n")
                        sb.append(pageText.trim()).append("\n\n")
                    }
                    if (sb.length() > Config.MAX_FILE_SIZE) {
                        sb.append("\n... [Truncated at maximum file size]\n")
                        break
                    }
                }
                String result = sb.toString().trim()
                return result.isEmpty() ? null : result
            } finally {
                pdDoc.close()
            }
        } catch (Exception e) {
            // PDF password-protected, encrypted, or corrupted
            return null
        }
    }

    // -----------------------------------------------------------------------
    // Private extraction methods
    // -----------------------------------------------------------------------

    /**
     * Reads text content from a stream using the encoding fallback chain
     * defined in Config.ENCODINGS. Truncates at Config.MAX_FILE_SIZE.
     *
     * @param stream    InputStream to read
     * @param sizeBytes Expected size (for pre-check; 0 if unknown)
     * @return Text content, or null if unreadable
     */
    private static String readTextWithEncoding(InputStream stream, long sizeBytes) {
        try {
            // Read raw bytes, respecting size limit
            byte[] rawBytes = readBytesLimited(stream)
            if (rawBytes == null || rawBytes.length == 0) {
                return null
            }
            return decodeWithFallback(rawBytes)
        } catch (Exception e) {
            // Silently skip unreadable files
            return null
        }
    }

    /**
     * Attempts to read an unknown file type as text. First checks for
     * binary content (null bytes in the first 512 bytes). If binary
     * detected, returns null. Otherwise decodes as text.
     *
     * @param stream    InputStream to read
     * @param sizeBytes Expected size
     * @return Text content if file appears textual, null otherwise
     */
    private static String readUnknownContent(InputStream stream, long sizeBytes) {
        try {
            byte[] rawBytes = readBytesLimited(stream)
            if (rawBytes == null || rawBytes.length == 0) {
                return null
            }
            // Heuristic: check for null bytes in the first 512 bytes
            if (isBinaryContent(rawBytes)) {
                return null
            }
            return decodeWithFallback(rawBytes)
        } catch (Exception e) {
            return null
        }
    }

    /**
     * Reads up to Config.MAX_FILE_SIZE bytes from a stream.
     *
     * @param stream InputStream to read
     * @return Byte array of content (may be truncated), or null on error
     */
    private static byte[] readBytesLimited(InputStream stream) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        long totalRead = 0
        int bytesRead

        while ((bytesRead = stream.read(buffer)) != -1) {
            long remaining = Config.MAX_FILE_SIZE - totalRead
            if (remaining <= 0) break
            int toWrite = (int) Math.min(bytesRead, remaining)
            baos.write(buffer, 0, toWrite)
            totalRead += toWrite
        }

        return baos.toByteArray()
    }

    /**
     * Decodes a byte array to String using the encoding fallback chain.
     * Tries each encoding in Config.ENCODINGS order. Falls back to
     * ISO-8859-1 as a last resort (it never throws on any byte sequence).
     *
     * @param rawBytes Byte array to decode
     * @return Decoded string
     */
    private static String decodeWithFallback(byte[] rawBytes) {
        for (String charset : Config.ENCODINGS) {
            try {
                // CharsetDecoder with REPORT mode would detect errors,
                // but for simplicity we use a pragmatic approach:
                // UTF-8 will naturally fail on invalid sequences when
                // we verify round-trip consistency.
                String decoded = new String(rawBytes, charset)
                // For UTF-8: verify no replacement characters appeared
                // that weren't in the source
                if (charset == 'UTF-8' && decoded.contains('\uFFFD')) {
                    continue  // Try next encoding
                }
                return decoded
            } catch (Exception ignored) {
                continue
            }
        }
        // Final fallback — ISO-8859-1 accepts all byte values
        return new String(rawBytes, 'ISO-8859-1')
    }

    /**
     * Heuristic binary detection: checks for null bytes (0x00) in the
     * first 512 bytes of content. Text files almost never contain nulls.
     *
     * @param rawBytes Byte array to inspect
     * @return true if content appears to be binary
     */
    private static boolean isBinaryContent(byte[] rawBytes) {
        int checkLength = Math.min(rawBytes.length, 512)
        for (int i = 0; i < checkLength; i++) {
            if (rawBytes[i] == 0) {
                return true
            }
        }
        return false
    }
}
