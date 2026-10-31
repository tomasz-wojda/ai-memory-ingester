import groovy.sql.Sql

/**
 * Cross-process lock that keeps one journal write and its prompt.log entry together.
 * The lock is an EXCLUSIVE SQLite transaction on a lock file; the operating system
 * releases it when the holder process stops.
 */
class MemoryLock {

    /**
     * Reports whether locking is on. Set AI_MEMORY_LOCK=0 to turn it off.
     *
     * @return true when the lock is used
     */
    static boolean enabled() {
        return System.getenv('AI_MEMORY_LOCK') != '0'
    }

    /**
     * Resolves the lock file of a workspace.
     *
     * @param workspaceRoot Workspace root
     * @return Lock file
     */
    static File lockFile(File workspaceRoot) {
        return new File(workspaceRoot, Config.MEMORY_LOCK_RELATIVE_PATH)
    }

    /**
     * Runs a closure while holding the lock.
     *
     * @param workspaceRoot Workspace root
     * @param body Work to run under the lock
     * @return Result of the closure
     * @throws IllegalStateException when the lock stays busy after one retry
     */
    static <T> T withLock(File workspaceRoot, Closure<T> body) {
        if (!enabled()) return body.call()
        File file = lockFile(workspaceRoot)
        file.parentFile.mkdirs()
        def driver = Thread.currentThread().contextClassLoader
            .loadClass('org.sqlite.JDBC').getDeclaredConstructor().newInstance()
        Sql sql = new Sql(driver.connect("jdbc:sqlite:${file.absolutePath}", new Properties()))
        try {
            sql.execute("PRAGMA busy_timeout=${Config.MEMORY_LOCK_TIMEOUT_MS}".toString())
            acquire(sql)
            try {
                return body.call()
            } finally {
                sql.execute('ROLLBACK')
            }
        } finally {
            sql.close()
        }
    }

    private static void acquire(Sql sql) {
        try {
            sql.execute('BEGIN EXCLUSIVE')
        } catch (Exception first) {
            try {
                sql.execute('BEGIN EXCLUSIVE')
            } catch (Exception second) {
                throw new IllegalStateException("Memory lock busy: ${second.message}", second)
            }
        }
    }
}
