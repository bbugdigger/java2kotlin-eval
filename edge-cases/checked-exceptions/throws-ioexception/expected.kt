package edge.checked_exceptions.throws_ioexception

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class FileLoader {
    @Throws(IOException::class)
    fun loadAll(path: Path): String = Files.readString(path)
}
