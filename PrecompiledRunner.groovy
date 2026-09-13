class PrecompiledRunner {
    private static final Map<String, String> COMMANDS = [
        'analyze': '01_analyze_archives',
        '01': '01_analyze_archives',
        'ingest': '02_ingest_archive',
        '02': '02_ingest_archive',
        'query': '03_query_memory',
        '03': '03_query_memory',
        'recent': '03_query_memory',
        'resume': '03_query_memory',
        'context': '03_query_memory',
        'compress': '04_compress_memory',
        'decompress': '04_compress_memory',
        'uncompress': '04_compress_memory',
        '04': '04_compress_memory',
        'sets': '03_query_memory',
        'datasets': '03_query_memory',
        'set': '03_query_memory',
        'dataset': '03_query_memory',
        'use-set': '03_query_memory',
        'use-dataset': '03_query_memory',
        'create-set': '03_query_memory',
        'create-dataset': '03_query_memory',
        'delete-set': '03_query_memory',
        'delete-dataset': '03_query_memory',
        'rename-set': '03_query_memory',
        'rename-dataset': '03_query_memory',
        'add-db-to-set': '03_query_memory',
        'add-db-to-dataset': '03_query_memory',
        'remove-db-from-set': '03_query_memory',
        'remove-db-from-dataset': '03_query_memory',
        'dbs': '03_query_memory',
        'databases': '03_query_memory',
        'archives': '03_query_memory',
        'archs': '03_query_memory',
        'egest': '03_query_memory',
        'rename': '03_query_memory',
        'rename-archive': '03_query_memory',
        'stream': '02_ingest_archive',
        'ingest-stream': '02_ingest_archive',
        'append': '02_ingest_archive',
        'ingest-text': '02_ingest_archive',
        'chatlog': '05_ingest_chatlog',
        'ingest-chatlog': '05_ingest_chatlog',
        'worklogs': '06_ingest_worklogs',
        'ingest-worklogs': '06_ingest_worklogs',
        'sweep-memory': '07_sweep_memory',
        'ticket': '03_query_memory'
    ].asImmutable()

    private static final Set<String> AUGMENTED_COMMANDS = [
        'sets', 'datasets', 'set', 'dataset', 'use-set', 'use-dataset',
        'create-set', 'create-dataset', 'delete-set', 'delete-dataset',
        'rename-set', 'rename-dataset', 'add-db-to-set', 'add-db-to-dataset',
        'remove-db-from-set', 'remove-db-from-dataset', 'dbs', 'databases',
        'archives', 'archs', 'egest', 'rename', 'rename-archive', 'stream',
        'ingest-stream', 'append', 'ingest-text', 'recent', 'resume', 'context',
        'chatlog', 'ingest-chatlog', 'worklogs', 'ingest-worklogs', 'sweep-memory', 'ticket'
    ] as Set<String>

    static void main(String[] arguments) {
        List<String> raw = arguments as List<String>
        String customDb = null
        String customDataDir = null
        List<String> filtered = []
        int index = 0
        while (index < raw.size()) {
            String argument = raw[index]
            if ((argument == '--db' || argument == '-D') && index + 1 < raw.size()) {
                customDb = clean(raw[index + 1])
                index += 2
            } else if (argument.startsWith('--db=')) {
                customDb = clean(argument.substring('--db='.length()))
                index++
            } else if (argument == '--data-dir' && index + 1 < raw.size()) {
                customDataDir = clean(raw[index + 1])
                index += 2
            } else if (argument.startsWith('--data-dir=')) {
                customDataDir = clean(argument.substring('--data-dir='.length()))
                index++
            } else {
                filtered << argument
                index++
            }
        }
        if (!filtered || !COMMANDS.containsKey(filtered[0].toLowerCase())) {
            System.err.println('Usage: ai-memory-ingester <command> [args] [--db <name_or_path>] [--data-dir <path>]')
            System.exit(1)
        }

        String command = filtered.remove(0).toLowerCase()
        List<String> scriptArguments = new ArrayList<>(filtered)
        if (AUGMENTED_COMMANDS.contains(command)) scriptArguments.add(0, command)
        if (customDataDir) {
            Config.setDataDir(customDataDir)
            scriptArguments.addAll(['--data-dir', customDataDir])
        }
        if (customDb) {
            Config.DB_PATH = Config.resolveDatabase(customDb)
            scriptArguments.addAll(['--db', customDb])
        }

        Thread.currentThread().contextClassLoader = PrecompiledRunner.classLoader
        Class scriptClass = Class.forName(COMMANDS[command], true, PrecompiledRunner.classLoader)
        Script script = scriptClass.getDeclaredConstructor().newInstance() as Script
        script.binding = new Binding(args: scriptArguments as String[], commandName: command)
        script.run()
    }

    private static String clean(String value) {
        return value?.trim()?.replaceAll("^['\"]+|['\"]+\$", '')
    }
}
