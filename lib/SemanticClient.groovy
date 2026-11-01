import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin client of the local memory service (HTTP over a Unix socket, bearer token).
 * Every method returns a map: ok, status, body, reason. It never throws for a service problem,
 * so a caller can fall back to the lexical order.
 */
class SemanticClient {

    static final long CIRCUIT_MILLIS = 30_000L
    static final int CONTRACT_VERSION = 1
    static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024
    private static final ConcurrentHashMap<String, Long> CIRCUITS = new ConcurrentHashMap<>()

    final File socketFile
    final File tokenFile

    SemanticClient(File socketFile, File tokenFile) {
        this.socketFile = socketFile
        this.tokenFile = tokenFile
    }

    /**
     * @param workspaceRoot Workspace root, or null to resolve it like the other commands
     * @return Client for the default socket (AI_MEMORY_SERVICE_SOCKET overrides it)
     */
    static SemanticClient discover(File workspaceRoot = null) {
        File root = workspaceRoot ?: Config.workspaceRoot(null, new File('.'))
        String override = System.getenv('AI_MEMORY_SERVICE_SOCKET')
        File socket = override ? new File(override) : new File(root, Config.SERVICE_SOCKET_RELATIVE)
        File token = new File(socket.parentFile, 'memory-service.token')
        return new SemanticClient(socket, token)
    }

    static void resetCircuit() {
        CIRCUITS.clear()
    }

    Map rerank(String query, List<Map> passages, String model, int deadlineMs) {
        Map body = [contract_version: CONTRACT_VERSION, query: query, passages: passages.collect { [id: it.id, text: it.text] }, deadline_ms: deadlineMs]
        if (model) body.model = model
        return call('POST', '/v1/rerank', body, deadlineMs + 700)
    }

    Map searchLate(String query, int topK, Map filters = [:]) {
        return call('POST', '/v1/search/late', [contract_version: CONTRACT_VERSION, query: query, top_k: topK, filters: filters], 4000)
    }

    Map upsertPassages(List<Map> documents) {
        return call('POST', '/v1/passages/upsert', [contract_version: CONTRACT_VERSION, documents: documents], 60_000)
    }

    Map deletePassages(List<Map> docs) {
        return call('POST', '/v1/passages/delete', [contract_version: CONTRACT_VERSION, docs: docs], 60_000)
    }

    Map missingPassages(List<Map> documents) {
        return call('POST', '/v1/passages/missing', [contract_version: CONTRACT_VERSION, documents: documents], 60_000)
    }

    Map listDocuments() {
        return call('GET', '/v1/passages/docs', null, 60_000)
    }

    Map indexBatch(int batch) {
        return call('POST', '/v1/index/upsert', [contract_version: CONTRACT_VERSION, batch: batch], 600_000)
    }

    Map indexStatus() {
        return call('GET', '/v1/index/status', null, 5000)
    }

    Map health() {
        return call('GET', '/v1/health', null, 2000)
    }

    Map call(String method, String path, Object body, int timeoutMillis) {
        if (!socketFile.exists()) return failure('service_down', 0)
        if (System.currentTimeMillis() < (CIRCUITS.get(path) ?: 0L)) return failure('service_circuit_open', 0)
        String token
        try {
            token = tokenFile.getText('UTF-8').trim()
        } catch (IOException ignored) {
            return failure('service_down', 0)
        }
        byte[] payload = body == null ? new byte[0] : JsonOutput.toJson(body).getBytes('UTF-8')
        SocketChannel[] holder = new SocketChannel[1]
        CompletableFuture<Map> future = CompletableFuture.supplyAsync({ exchange(method, path, token, payload, holder) } as java.util.function.Supplier<Map>)
        try {
            Map reply = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            return classify(reply, path)
        } catch (TimeoutException ignored) {
            closeQuietly(holder[0])
            openCircuit(path)
            return failure('deadline_exceeded', 0)
        } catch (ExecutionException ignored) {
            openCircuit(path)
            return failure('service_down', 0)
        }
    }

    private Map exchange(String method, String path, String token, byte[] payload, SocketChannel[] holder) {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        holder[0] = channel
        try {
            channel.connect(UnixDomainSocketAddress.of(socketFile.toPath()))
            String head = "${method} ${path} HTTP/1.1\r\nHost: local\r\nAuthorization: Bearer ${token}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n"
            channel.write(ByteBuffer.wrap(head.getBytes('UTF-8')))
            if (payload.length > 0) {
                ByteBuffer out = ByteBuffer.wrap(payload)
                while (out.hasRemaining()) channel.write(out)
            }
            ByteArrayOutputStream collected = new ByteArrayOutputStream()
            ByteBuffer buffer = ByteBuffer.allocate(65536)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                collected.write(buffer.array(), 0, buffer.limit())
                buffer.clear()
                if (collected.size() > MAX_RESPONSE_BYTES) throw new IOException('The service response is too large')
            }
            String text = collected.toString('UTF-8')
            int split = text.indexOf('\r\n\r\n')
            int status = Integer.parseInt(text.substring(0, text.indexOf('\r\n')).split(' ')[1])
            Object parsed = split >= 0 && text.length() > split + 4 ? new JsonSlurper().parseText(text.substring(split + 4)) : [:]
            return [status: status, body: parsed instanceof Map ? parsed : [value: parsed]]
        } finally {
            closeQuietly(channel)
        }
    }

    private Map classify(Map reply, String path) {
        int status = reply.status as int
        if (status == 200) return [ok: true, status: status, body: reply.body, reason: null]
        String code = ((reply.body as Map)?.error ?: '').toString()
        String reason = [400: 'bad_request', 401: 'unauthorized', 404: 'model_unavailable', 409: 'contract_mismatch', 429: 'service_busy', 503: 'model_unavailable', 504: 'deadline_exceeded'][status]
        if (code == 'index_stale') reason = 'index_stale'
        reason = reason ?: 'service_down'
        if (reason != 'service_busy' && reason != 'bad_request') openCircuit(path)
        return [ok: false, status: status, body: reply.body, reason: reason, error: code]
    }

    private static Map failure(String reason, int status) {
        return [ok: false, status: status, body: [:], reason: reason]
    }

    private static void openCircuit(String path) {
        CIRCUITS.put(path, System.currentTimeMillis() + CIRCUIT_MILLIS)
    }

    private static void closeQuietly(SocketChannel channel) {
        try { channel?.close() } catch (IOException ignored) {}
    }
}
