package cc.royalty;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanSchema() {
        List<String> tables = jdbc.queryForList("""
                select tablename from pg_tables where schemaname = current_schema()
                and tablename <> 'flyway_schema_history' order by tablename
                """, String.class);
        for (String t : tables) {
            jdbc.execute("truncate table " + t + " restart identity cascade");
        }
    }

    protected static String sample(String name) {
        try {
            return new String(AbstractIntegrationTest.class.getResourceAsStream("/samples/" + name)
                    .readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
