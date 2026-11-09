import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.concurrent.TimeUnit

class ServiceAutostart {

    static final String FLAG = 'AI_MEMORY_SERVICE_AUTOSTART'
    static final String MODEL_VARIABLE = 'AI_MEMORY_SERVICE_MODEL'
    static final String DEFAULT_MODEL = 'mmarco-mMiniLMv2-L12-H384-v1'
    static final String CONFIG_RELATIVE = '.ai-worklog/memory-service.json'
    static final String LOG_RELATIVE = '.ai-worklog/service-autostart.log'
    static final int MAX_TOKENS = 512

    /**
     * Tells whether the sweep may start the service. AI_MEMORY_SERVICE_AUTOSTART=1 turns it on; it is off otherwise.
     *
     * @return True when the flag is 1
     */
    static boolean enabled() {
        return (System.getProperty('ai.memory.service_autostart') ?: System.getenv(FLAG)) == '1'
    }

    /**
     * Folder with the model folders and models.manifest.json. AI_MEMORY_MODELS_DIR overrides the default.
     *
     * @return Models folder
     */
    static File modelsDirectory() {
        String override = System.getProperty('ai.memory.models_dir') ?: System.getenv('AI_MEMORY_MODELS_DIR')
        if (override?.trim()) return new File(override.trim())
        return new File(System.getProperty('user.home') ?: '.', '.local/share/ai-memory-service/models')
    }

    /**
     * The manifest entry of a model.
     *
     * @param modelsDir Models folder
     * @param name Model folder name
     * @return Entry map, or null when the manifest, the entry or the model folder is missing
     */
    static Map manifestEntry(File modelsDir, String name) {
        File manifest = new File(modelsDir, 'models.manifest.json')
        if (!manifest.isFile() || !new File(modelsDir, name).isDirectory()) return null
        try {
            Map data = new JsonSlurper().parse(manifest) as Map
            Map entry = ((data.models ?: [:]) as Map)[name] as Map
            return (entry && entry.folder_checksum) ? entry : null
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * The service configuration for one model, as in the benchmark configurations.
     *
     * @param name Model folder name
     * @param entry Manifest entry (folder_checksum, licence)
     * @return Configuration map
     */
    static Map buildConfig(String name, Map entry) {
        return [
            rerank_model: name,
            models: [(name): [path: name, sha256: entry.folder_checksum, max_tokens: MAX_TOKENS, device: 'auto', licence: entry.licence ?: 'unverified']]
        ]
    }

    /**
     * Makes sure that a configuration file with a model exists. An existing file is never changed.
     *
     * @param workspaceRoot Workspace root
     * @param modelsDir Models folder
     * @param name Model folder name
     * @return Map with ready (boolean), written (boolean) and note
     */
    static Map prepare(File workspaceRoot, File modelsDir, String name) {
        File config = new File(workspaceRoot, CONFIG_RELATIVE)
        if (config.exists()) return [ready: true, written: false, note: 'configuration exists']
        Map entry = manifestEntry(modelsDir, name)
        if (entry == null) return [ready: false, written: false, note: 'skipped (model not installed)']
        config.parentFile.mkdirs()
        config.setText(JsonOutput.prettyPrint(JsonOutput.toJson(buildConfig(name, entry))) + '\n', 'UTF-8')
        return [ready: true, written: true, note: 'configuration written']
    }

    /**
     * Starts the service in the background when it does not run. The sweep never fails because of this step.
     *
     * @param repositoryRoot Repository root (holds service/ai-memory-service)
     * @param workspaceRoot Workspace root
     * @return Note for the SERVICE_AUTOSTART output line
     */
    static String ensure(File repositoryRoot, File workspaceRoot) {
        try {
            File launcher = new File(repositoryRoot, 'service/ai-memory-service')
            if (!launcher.isFile()) return 'skipped (service launcher not found)'
            String name = System.getenv(MODEL_VARIABLE)?.trim() ?: DEFAULT_MODEL
            Map prepared = prepare(workspaceRoot, modelsDirectory(), name)
            if (!prepared.ready) return prepared.note as String
            File python = new File(System.getProperty('user.home') ?: '.', '.local/share/ai-memory-service/venv/bin/python')
            if (isRunning(launcher, workspaceRoot, python)) return 'running'
            File log = new File(workspaceRoot, LOG_RELATIVE)
            log.parentFile.mkdirs()
            ProcessBuilder builder = new ProcessBuilder(['/bin/sh', '-c', 'nohup "$0" "$@" >> "$AI_MEMORY_SERVICE_LOG" 2>&1 &',
                                                         launcher.absolutePath, '--workspace-root', workspaceRoot.absolutePath, 'serve'])
            builder.directory(repositoryRoot)
            builder.environment().put('AI_MEMORY_SERVICE_LOG', log.absolutePath)
            if (python.isFile()) builder.environment().put('AI_MEMORY_SERVICE_PYTHON', python.absolutePath)
            builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            builder.start().waitFor(5, TimeUnit.SECONDS)
            File socket = new File(workspaceRoot, Config.SERVICE_SOCKET_RELATIVE)
            for (int i = 0; i < 40 && !socket.exists(); i++) Thread.sleep(50)
            String config = prepared.written ? 'configuration written, ' : ''
            return socket.exists()
                ? "started (${config}the model loads in about 4 s)".toString()
                : "started (${config}no socket yet; see ${LOG_RELATIVE})".toString()
        } catch (Exception error) {
            return "skipped (${error.class.simpleName})".toString()
        }
    }

    private static boolean isRunning(File launcher, File workspaceRoot, File python) {
        ProcessBuilder builder = new ProcessBuilder([launcher.absolutePath, '--workspace-root', workspaceRoot.absolutePath, 'status'])
        if (python.isFile()) builder.environment().put('AI_MEMORY_SERVICE_PYTHON', python.absolutePath)
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        Process process = builder.start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }
}
