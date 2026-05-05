package edge.checked_exceptions.throws_multiple

import java.io.IOException
import java.sql.SQLException

class DataLayer {
    @Throws(IOException::class, SQLException::class)
    fun fetch(id: Int): String {
        if (id < 0) throw IOException("bad id")
        if (id == 0) throw SQLException("empty key")
        return "row:$id"
    }
}
