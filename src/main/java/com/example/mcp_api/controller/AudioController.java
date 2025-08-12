package com.example.mcp_api.controller;

import com.example.mcp_api.service.AudioPlayerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@RestController
@RequestMapping("/audio")
public class AudioController {
    
    private static final Logger logger = LoggerFactory.getLogger(AudioController.class);
    
    @Autowired
    private AudioPlayerService audioPlayerService;
    
    @Value("${audio.player.directory:/Users/ankitkhantwal/Downloads/audio-player-mcp/Music}")
    private String audioDirectoryPath;
    
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(audioPlayerService.getAudioStatus());
    }
    
    @PostMapping("/play")
    public ResponseEntity<Map<String, Object>> playAudio(@RequestBody Map<String, String> request) {
        String filenameOrUrl = request.get("filenameOrUrl");
        if (filenameOrUrl == null || filenameOrUrl.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "filenameOrUrl is required"));
        }
        
        Map<String, Object> result = audioPlayerService.playAudio(filenameOrUrl);
        return ResponseEntity.ok(result);
    }
    
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopAudio() {
        return ResponseEntity.ok(audioPlayerService.stopAudio());
    }
    
    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pauseAudio() {
        return ResponseEntity.ok(audioPlayerService.pauseAudio());
    }
    
    @PostMapping("/volume")
    public ResponseEntity<Map<String, Object>> setVolume(@RequestBody Map<String, Integer> request) {
        Integer volume = request.get("volume");
        if (volume == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "volume is required"));
        }
        
        Map<String, Object> result = audioPlayerService.setVolume(volume);
        return ResponseEntity.ok(result);
    }
    
    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> listFiles() {
        return ResponseEntity.ok(audioPlayerService.listAudioFiles());
    }
    
    @GetMapping("/system-check")
    public ResponseEntity<Map<String, Object>> checkSystem() {
        return ResponseEntity.ok(audioPlayerService.checkAudioSystem());
    }
    
    /**
     * Stream audio file for web-based clients
     * Usage: GET /audio/stream/filename.mp3
     */
    @GetMapping("/stream/{filename}")
    public ResponseEntity<Resource> streamAudio(@PathVariable String filename) {
        try {
            Path audioFile = Paths.get(audioDirectoryPath).resolve(filename);
            
            // Security check
            if (!audioFile.toAbsolutePath().startsWith(Paths.get(audioDirectoryPath).toAbsolutePath())) {
                return ResponseEntity.badRequest().build();
            }
            
            if (!Files.exists(audioFile)) {
                return ResponseEntity.notFound().build();
            }
            
            Resource resource = new FileSystemResource(audioFile);
            
            // Determine content type
            String contentType = Files.probeContentType(audioFile);
            if (contentType == null) {
                contentType = "audio/mpeg"; // default
            }
            
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("Accept-Ranges", "bytes")
                .body(resource);
                
        } catch (IOException e) {
            logger.error("Error streaming audio file: {}", filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Download audio from URL for client-side saving
     * Usage: GET /audio/download?url=https://example.com/audio.mp3
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadAudio(@RequestParam String url) {
        logger.info("Downloading audio from URL for client: {}", url);
        
        try {
            URL audioUrl = new URL(url);
            URLConnection connection = audioUrl.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            
            // Get filename from URL
            String filename = getFilenameFromUrl(url);
            if (filename == null) {
                filename = "downloaded_audio_" + System.currentTimeMillis() + ".mp3";
            }
            
            // Determine content type
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.startsWith("audio/")) {
                contentType = "audio/mpeg"; // default
            }
            
            InputStream inputStream = connection.getInputStream();
            InputStreamResource resource = new InputStreamResource(inputStream);
            
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(resource);
                
        } catch (Exception e) {
            logger.error("Error downloading audio from URL: {}", url, e);
            return ResponseEntity.internalServerError().build();
        }
    }
    
    /**
     * Web-based audio player interface
     * Usage: GET /audio/player or /audio/player?file=song.mp3&url=https://example.com/audio.mp3
     */
    @GetMapping("/player")
    public ResponseEntity<String> audioPlayer(
            @RequestParam(required = false) String file,
            @RequestParam(required = false) String url) {
        
        String html = generateAudioPlayerHTML(file, url);
        
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(html);
    }
    
    private String getFilenameFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String path = url.getPath();
            if (path != null && !path.isEmpty()) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    return path.substring(lastSlash + 1);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract filename from URL: {}", urlString);
        }
        return null;
    }
    
    private String generateAudioPlayerHTML(String file, String url) {
        StringBuilder html = new StringBuilder();
        
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>MCP Audio Player</title>\n");
        html.append("    <style>\n");
        html.append("        body { font-family: Arial, sans-serif; margin: 40px; background: #f5f5f5; }\n");
        html.append("        .container { max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n");
        html.append("        h1 { color: #333; text-align: center; margin-bottom: 30px; }\n");
        html.append("        .player-section { margin: 20px 0; padding: 20px; border: 1px solid #ddd; border-radius: 5px; }\n");
        html.append("        audio { width: 100%; margin: 10px 0; }\n");
        html.append("        input[type=\"text\"] { width: 70%; padding: 8px; margin: 5px; border: 1px solid #ddd; border-radius: 3px; }\n");
        html.append("        button { padding: 8px 15px; margin: 5px; background: #007cba; color: white; border: none; border-radius: 3px; cursor: pointer; }\n");
        html.append("        button:hover { background: #005a87; }\n");
        html.append("        .status { margin: 10px 0; padding: 10px; background: #e8f4f8; border-radius: 3px; }\n");
        html.append("        .file-list { max-height: 300px; overflow-y: auto; }\n");
        html.append("        .file-item { padding: 5px; margin: 2px 0; background: #f9f9f9; border-radius: 3px; cursor: pointer; }\n");
        html.append("        .file-item:hover { background: #e0e0e0; }\n");
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"container\">\n");
        html.append("        <h1>🎵 MCP Audio Player</h1>\n");
        html.append("        <p><strong>Client-Side Audio Playback</strong> - Audio plays on your local machine!</p>\n");
        
        // Current audio player
        html.append("        <div class=\"player-section\">\n");
        html.append("            <h3>Current Audio</h3>\n");
        html.append("            <audio id=\"mainPlayer\" controls style=\"width: 100%;\">\n");
        html.append("                Your browser does not support the audio element.\n");
        html.append("            </audio>\n");
        html.append("            <div id=\"currentStatus\" class=\"status\">Ready to play audio</div>\n");
        html.append("        </div>\n");
        
        // URL input
        html.append("        <div class=\"player-section\">\n");
        html.append("            <h3>Play from URL</h3>\n");
        html.append("            <input type=\"text\" id=\"urlInput\" placeholder=\"Enter audio URL (http://...)\" value=\"").append(url != null ? url : "").append("\">\n");
        html.append("            <button onclick=\"playFromUrl()\">Play URL</button>\n");
        html.append("        </div>\n");
        
        // Instructions section
        html.append("        <div class=\"player-section\">\n");
        html.append("            <h3>Instructions</h3>\n");
        html.append("            <p>This player supports:</p>\n");
        html.append("            <ul>\n");
        html.append("                <li>🌐 Any public audio URL (MP3, WAV, OGG, etc.)</li>\n");
        html.append("                <li>🎵 Streaming audio from various sources</li>\n");
        html.append("                <li>🎛️ Full browser audio controls</li>\n");
        html.append("                <li>📱 Works on desktop and mobile browsers</li>\n");
        html.append("            </ul>\n");
        html.append("            <p><strong>Tip:</strong> Use the MCP tools in Claude Desktop for easier audio management!</p>\n");
        html.append("        </div>\n");
        
        // JavaScript
        html.append("        <script>\n");
        html.append("            const player = document.getElementById('mainPlayer');\n");
        html.append("            const status = document.getElementById('currentStatus');\n");
        html.append("            \n");
        html.append("            function updateStatus(message) {\n");
        html.append("                status.textContent = message;\n");
        html.append("            }\n");
        html.append("            \n");
        html.append("            function playFromUrl() {\n");
        html.append("                const url = document.getElementById('urlInput').value;\n");
        html.append("                if (url) {\n");
        html.append("                    player.src = url;\n");
        html.append("                    updateStatus('Loading: ' + url);\n");
        html.append("                    player.load();\n");
        html.append("                }\n");
        html.append("            }\n");
        html.append("            \n");
        html.append("            function playFile(filename) {\n");
        html.append("                const streamUrl = '/audio/stream/' + encodeURIComponent(filename);\n");
        html.append("                player.src = streamUrl;\n");
        html.append("                updateStatus('Playing: ' + filename);\n");
        html.append("                player.load();\n");
        html.append("            }\n");
        html.append("            \n");
        html.append("            function loadFileList() {\n");
        html.append("                fetch('/audio/files')\n");
        html.append("                    .then(response => response.json())\n");
        html.append("                    .then(data => {\n");
        html.append("                        const fileList = document.getElementById('fileList');\n");
        html.append("                        if (data.status === 'success' && data.files.length > 0) {\n");
        html.append("                            fileList.innerHTML = data.files.map(file => \n");
        html.append("                                `<div class=\"file-item\" onclick=\"playFile('${file.filename}')\">\n");
        html.append("                                    🎵 ${file.filename} (${file.size_mb} MB)\n");
        html.append("                                </div>`\n");
        html.append("                            ).join('');\n");
        html.append("                        } else {\n");
        html.append("                            fileList.innerHTML = '<p>No audio files found</p>';\n");
        html.append("                        }\n");
        html.append("                    })\n");
        html.append("                    .catch(error => {\n");
        html.append("                        document.getElementById('fileList').innerHTML = '<p>Error loading files</p>';\n");
        html.append("                    });\n");
        html.append("            }\n");
        html.append("            \n");
        html.append("            // Event listeners\n");
        html.append("            player.addEventListener('loadstart', () => updateStatus('Loading audio...'));\n");
        html.append("            player.addEventListener('canplay', () => updateStatus('Ready to play'));\n");
        html.append("            player.addEventListener('play', () => updateStatus('Playing...'));\n");
        html.append("            player.addEventListener('pause', () => updateStatus('Paused'));\n");
        html.append("            player.addEventListener('ended', () => updateStatus('Finished playing'));\n");
        html.append("            player.addEventListener('error', () => updateStatus('Error loading audio'));\n");
        html.append("            \n");
        html.append("            // Auto-load file list on page load\n");
        html.append("            window.onload = function() {\n");
        html.append("                loadFileList();\n");
        
                // Auto-play if URL specified
                if (url != null) {
                    html.append("                playFromUrl();\n");
                }
        
        html.append("            };\n");
        html.append("        </script>\n");
        html.append("    </div>\n");
        html.append("</body>\n");
        html.append("</html>\n");
        
        return html.toString();
    }
}