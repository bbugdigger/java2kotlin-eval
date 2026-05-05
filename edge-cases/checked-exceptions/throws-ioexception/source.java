package edge.checked_exceptions.throws_ioexception;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileLoader {
    public String loadAll(Path path) throws IOException {
        return Files.readString(path);
    }
}
