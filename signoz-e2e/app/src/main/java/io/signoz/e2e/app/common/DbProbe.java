package io.signoz.e2e.app.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;


/** SQL against an in-memory H2 database, for slow query detection. */
@Component
public class DbProbe {

    private final JdbcTemplate jdbc;

    public DbProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void init() {
        jdbc.execute("CREATE ALIAS IF NOT EXISTS SLEEP FOR 'java.lang.Thread.sleep(long)'");
    }

    public void slow(long ms) {
        init();
        jdbc.execute("CALL SLEEP(" + ms + ")");
    }

    public Integer fast() {
        return jdbc.queryForObject("SELECT 1", Integer.class);
    }
}
