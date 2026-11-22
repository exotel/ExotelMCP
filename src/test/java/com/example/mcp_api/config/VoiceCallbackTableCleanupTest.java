package com.example.mcp_api.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** VO-6235: call records stored by earlier versions are dropped at startup. */
class VoiceCallbackTableCleanupTest {

    private static int tableCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'VOICE_CALLBACKS'", Integer.class);
    }

    @Test
    void dropsTheOldTableAndToleratesItsAbsence() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:vo6235;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE voice_callbacks (id BIGINT PRIMARY KEY, to_number VARCHAR(20))");
        jdbc.execute("INSERT INTO voice_callbacks VALUES (1, '09999999999')");
        assertEquals(1, tableCount(jdbc));

        VoiceCallbackTableCleanup cleanup = new VoiceCallbackTableCleanup(jdbc);
        cleanup.run(null);
        assertEquals(0, tableCount(jdbc));

        assertDoesNotThrow(() -> cleanup.run(null));
    }
}
