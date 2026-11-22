package com.example.mcp_api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * VO-6235: call status is read live from Exotel, so call records stored by earlier versions
 * (numbers, status, recording URLs) are dropped at startup. ddl-auto=update never drops a table.
 */
@Component
public class VoiceCallbackTableCleanup implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(VoiceCallbackTableCleanup.class);

    private final JdbcTemplate jdbc;

    public VoiceCallbackTableCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("DROP TABLE IF EXISTS voice_callbacks");
        logger.info("Dropped the voice_callbacks table if it existed (VO-6235)");
    }
}
