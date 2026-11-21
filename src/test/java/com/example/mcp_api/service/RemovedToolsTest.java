package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SL-3868: tools that read server-local files for upload or hand out /audio/download links must stay removed. */
class RemovedToolsTest {

    private static final Set<String> REMOVED = Set.of(
            "exotel_aiassist_attach_attachment_from_file",
            "exotel_audio_download_from_url",
            "exotel_audio_play_from_url",
            "audio_client_download_file");

    @Test
    void removedToolsAreNotRegistered() {
        Stream.of(AiAssistService.class, QuickAudioService.class, ClientAudioService.class)
                .flatMap(c -> Stream.of(c.getDeclaredMethods()))
                .map(m -> m.getAnnotation(Tool.class))
                .filter(t -> t != null)
                .forEach(t -> assertFalse(REMOVED.contains(t.name()), "Removed tool is back: " + t.name()));
    }

    @Test
    void audioCodeDoesNotFetchUrls() throws Exception {
        assertFalse(java.util.Arrays.stream(AudioPlayerService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("downloadAudioFromUrl")), "downloadAudioFromUrl is back");
        for (String file : new String[] {"service/AudioPlayerService.java", "controller/AudioController.java"}) {
            String source = Files.readString(Path.of("src/main/java/com/example/mcp_api", file));
            assertFalse(source.contains("downloadAudioFromUrl"), file);
            assertFalse(source.contains("java.net.URL"), file);
            assertFalse(source.contains("openConnection"), file);
        }
    }

    @Test
    void playAudioRejectsUrls() {
        for (String value : new String[] {"https://example.com/a.mp3", "http://169.254.169.254/x", "file:///etc/passwd",
                "//example.com/a.mp3"}) {
            var result = new AudioPlayerService().playAudio(value);
            assertTrue(String.valueOf(result.get("message")).contains("URLs are not supported"), value);
        }
    }
}
