import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class SemanticFixtureBuilder {

    static Map build(File root, Class memoryEngine) {
        File data = new File(root, 'data')
        data.mkdirs()
        List<Map> corpus = new JsonSlurper().parse(new File('tests/fixtures/semantic/corpus.json')) as List<Map>
        corpus.groupBy { it.db }.each { String db, List<Map> docs ->
            def engine = memoryEngine.getDeclaredConstructor(String).newInstance(new File(data, db).absolutePath)
            try {
                engine.beginBatch()
                docs.each { Map doc ->
                    engine.insertDocument(doc.archive.toString(), doc.path.toString(), doc.name.toString(), '.md', doc.content.toString().length(), doc.content.toString())
                }
                engine.endBatch()
            } finally {
                engine.close()
            }
        }
        new File(data, 'datasets.json').setText(JsonOutput.toJson([
            schema_version: 1,
            active_dataset: 'worklog-chat',
            datasets: ['worklog-chat': [created_at: '2026-01-01T00:00:00', description: 'semantic fixture', databases: ['convo.db', 'worklogs.db']]]
        ]), 'UTF-8')
        return [data: data, corpus: corpus]
    }
}
