package edge.checked_exceptions.throws_multiple;

import java.io.IOException;
import java.sql.SQLException;

public class DataLayer {
    public String fetch(int id) throws IOException, SQLException {
        if (id < 0) {
            throw new IOException("bad id");
        }
        if (id == 0) {
            throw new SQLException("empty key");
        }
        return "row:" + id;
    }
}
