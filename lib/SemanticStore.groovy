import groovy.json.JsonOutput
import groovy.sql.Sql
import java.time.Instant

/**
 * The derived-data database `semantic.db`. Phase 0 keeps only the stored evaluation runs.
 * It never touches convo.db, worklogs.db, artifacts.db, dross.db or journal.db.
 */
class SemanticStore {

    static final String FILE_NAME = 'semantic.db'

    /**
     * @param dataDir Data directory
     * @return The semantic.db file
     */
    static File path(File dataDir) {
        return new File(dataDir, FILE_NAME)
    }

    /**
     * Stores one evaluation run.
     *
     * @param dataDir Data directory
     * @param run Map with run_id, config, metrics and per_query
     */
    static void saveRun(File dataDir, Map run) {
        dataDir.mkdirs()
        def driver = Thread.currentThread().contextClassLoader.loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
        Sql sql = new Sql(driver.connect("jdbc:sqlite:${path(dataDir).absolutePath}", new Properties()))
        try {
            sql.execute('''
                CREATE TABLE IF NOT EXISTS eval_runs (
                    run_id TEXT PRIMARY KEY,
                    started_at TEXT NOT NULL,
                    git_head TEXT,
                    config_json TEXT NOT NULL,
                    metrics_json TEXT NOT NULL,
                    per_query_json TEXT NOT NULL
                )
            ''')
            sql.execute('''
                INSERT OR REPLACE INTO eval_runs (run_id, started_at, git_head, config_json, metrics_json, per_query_json)
                VALUES (?, ?, ?, ?, ?, ?)
            ''', [
                run.run_id, run.started_at ?: Instant.now().toString(), run.git_head,
                JsonOutput.toJson(run.config ?: [:]), JsonOutput.toJson(run.metrics ?: [:]), JsonOutput.toJson(run.per_query ?: [])
            ])
        } finally {
            sql.close()
        }
    }
}
