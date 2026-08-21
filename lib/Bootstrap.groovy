/**
 * Bootstrap.groovy
 *
 * Shared bootstrap loader for the Archive Memory Context Engine.
 * Compiles and loads all library modules from the lib/ directory
 * into the provided GroovyClassLoader, making Config, ArchiveHandler,
 * ContentExtractor, MemoryEngine, and ArchiveEntry available.
 *
 * Usage: Include at the top of every executable script:
 *   new GroovyClassLoader(this.class.classLoader).with { cl ->
 *       cl.parseClass(new File('lib/Bootstrap.groovy'))
 *   }
 *
 * Or use the static method directly:
 *   Bootstrap.load(this)
 */

// Define load order: Config must come first (others depend on it),
// then ArchiveHandler (defines ArchiveEntry), then the rest.
List<String> loadOrder = [
    'Config.groovy',
    'ArchiveHandler.groovy',
    'ContentExtractor.groovy',
    'MemoryEngine.groovy',
]

File libDir = new File('lib')

// Use the binding's classloader (the calling script's classloader)
GroovyClassLoader gcl
if (this.class.classLoader instanceof GroovyClassLoader) {
    gcl = (GroovyClassLoader) this.class.classLoader
} else {
    gcl = new GroovyClassLoader(this.class.classLoader)
}

loadOrder.each { String fileName ->
    File sourceFile = new File(libDir, fileName)
    if (!sourceFile.exists()) {
        throw new FileNotFoundException("Required library not found: ${sourceFile.absolutePath}")
    }
    gcl.parseClass(sourceFile)
}
