package com.example.mcp_api.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ClientAudioService {
    
    private static final Logger logger = LoggerFactory.getLogger(ClientAudioService.class);
    
    @Value("${audio.player.directory:/Users/ankitkhantwal/Downloads/audio-player-mcp/Music}")
    private String audioDirectoryPath;
    
    @Value("${exotel.base.url:http://localhost:8085}")
    private String baseUrl;
    
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
        ".wav", ".mp3", ".ogg", ".flac", ".aiff", ".au", ".m4a"
    );
    
    @Tool(name = "playAudioOnClient", 
          description = "Play audio on client side. For URLs, provides direct URL for client playback. For local files, provides streaming URL. Returns playback instructions for client-side audio.")
    public Map<String, Object> playAudioOnClient(String filenameOrUrl) {
        logger.info("Preparing client-side audio playback for: {}", filenameOrUrl);
        
        try {
            boolean isUrl = isValidUrl(filenameOrUrl);
            Map<String, Object> result = new HashMap<>();
            
            if (isUrl) {
                // For URLs, provide direct URL for client-side playback
                String webPlayerUrl = baseUrl + "/audio/player?url=" + java.net.URLEncoder.encode(filenameOrUrl, "UTF-8");
                
                result.put("status", "ready_for_client_playback");
                result.put("type", "direct_url");
                result.put("audio_url", filenameOrUrl);
                result.put("web_player_url", webPlayerUrl);
                result.put("action_required", "🎵 CLICK TO PLAY: " + webPlayerUrl);
                result.put("instructions", Arrays.asList(
                    "Click the link above to open the audio player in your browser",
                    "Audio will play on YOUR machine (client-side)",
                    "No copy-paste needed - just click the link!"
                ));
                
                logger.info("Prepared direct URL playback: {}", filenameOrUrl);
            } else {
                // For local files, provide streaming URL
                Path audioDir = Paths.get(audioDirectoryPath);
                Path audioFile = audioDir.resolve(Paths.get(filenameOrUrl).getFileName());
                
                if (!Files.exists(audioFile)) {
                    throw new FileNotFoundException("Audio file not found: " + filenameOrUrl);
                }
                
                // Security check
                if (!audioFile.toAbsolutePath().startsWith(audioDir.toAbsolutePath())) {
                    throw new SecurityException("File must be in the audio directory");
                }
                
                String streamingUrl = baseUrl + "/audio/stream/" + audioFile.getFileName().toString();
                String webPlayerUrl = baseUrl + "/audio/player?file=" + java.net.URLEncoder.encode(audioFile.getFileName().toString(), "UTF-8");
                
                result.put("status", "ready_for_client_playback");
                result.put("type", "local_file_stream");
                result.put("audio_url", streamingUrl);
                result.put("filename", audioFile.getFileName().toString());
                result.put("web_player_url", webPlayerUrl);
                result.put("action_required", "🎵 CLICK TO PLAY: " + webPlayerUrl);
                result.put("instructions", Arrays.asList(
                    "Click the link above to open the audio player in your browser",
                    "Audio will stream from server and play on YOUR machine",
                    "No copy-paste needed - just click the link!"
                ));
                
                logger.info("Prepared streaming URL for local file: {}", streamingUrl);
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error preparing client-side audio playback: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Error preparing client playback: " + e.getMessage());
            return error;
        }
    }
    
    @Tool(name = "downloadAudioToClient", 
          description = "Download audio file from URL to a local client directory for offline playback. Useful for caching audio files locally.")
    public Map<String, Object> downloadAudioToClient(String url, String clientDirectory) {
        logger.info("Preparing audio download from URL: {} to client directory: {}", url, clientDirectory);
        
        try {
            if (!isValidUrl(url)) {
                throw new IllegalArgumentException("Invalid URL provided");
            }
            
            // Get filename from URL
            String filename = getFilenameFromUrl(url);
            if (filename == null || !hasAudioExtension(filename)) {
                filename = "downloaded_audio_" + System.currentTimeMillis() + ".mp3";
            }
            
            // Provide download instructions
            Map<String, Object> result = new HashMap<>();
            result.put("status", "download_ready");
            result.put("download_url", baseUrl + "/audio/download?url=" + java.net.URLEncoder.encode(url, "UTF-8"));
            result.put("suggested_filename", filename);
            result.put("client_directory", clientDirectory != null ? clientDirectory : "~/Downloads");
            result.put("instructions", Arrays.asList(
                "1. Use the download_url to download the file to your client machine",
                "2. Save it as: " + filename,
                "3. Play using your local audio player",
                "Command: curl -o '" + filename + "' '" + result.get("download_url") + "'"
            ));
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error preparing audio download: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Error preparing download: " + e.getMessage());
            return error;
        }
    }
    
    @Tool(name = "listClientAudioFiles", 
          description = "List available audio files with client-side streaming URLs. Returns files that can be played on client side.")
    public Map<String, Object> listClientAudioFiles() {
        logger.info("Listing audio files with client streaming URLs from directory: {}", audioDirectoryPath);
        
        try {
            Path audioDir = Paths.get(audioDirectoryPath);
            
            if (!Files.exists(audioDir)) {
                logger.info("Audio directory doesn't exist: {}", audioDir);
                Files.createDirectories(audioDir);
            }
            
            List<Map<String, Object>> audioFiles = Files.list(audioDir)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String filename = path.getFileName().toString().toLowerCase();
                    return SUPPORTED_FORMATS.stream().anyMatch(filename::endsWith);
                })
                .map(path -> {
                    String filename = path.getFileName().toString();
                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("filename", filename);
                    fileInfo.put("streaming_url", baseUrl + "/audio/stream/" + filename);
                    fileInfo.put("web_player_url", baseUrl + "/audio/player?file=" + filename);
                    try {
                        fileInfo.put("size_mb", String.format("%.2f", Files.size(path) / (1024.0 * 1024.0)));
                    } catch (IOException e) {
                        fileInfo.put("size_mb", "unknown");
                    }
                    return fileInfo;
                })
                .sorted((a, b) -> ((String) a.get("filename")).compareToIgnoreCase((String) b.get("filename")))
                .collect(Collectors.toList());
            
            logger.info("Found {} audio files with streaming URLs", audioFiles.size());
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("files", audioFiles);
            result.put("count", audioFiles.size());
            result.put("directory", audioDirectoryPath);
            result.put("web_player_main", baseUrl + "/audio/player");
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error listing audio files", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Error listing audio files: " + e.getMessage());
            return error;
        }
    }
    
    @Tool(name = "getClientPlayerUrl", 
          description = "Get the URL for the web-based audio player that runs in browser for client-side playback. Can be used to play any audio file or URL.")
    public Map<String, Object> getClientPlayerUrl() {
        Map<String, Object> result = new HashMap<>();
        String webPlayerUrl = baseUrl + "/audio/player";
        
        result.put("status", "success");
        result.put("web_player_url", webPlayerUrl);
        result.put("description", "Open this URL in your browser for client-side audio playback");
        result.put("features", Arrays.asList(
            "Play local files via streaming",
            "Play remote URLs directly",
            "Volume control",
            "Pause/Resume/Stop controls",
            "Progress tracking"
        ));
        result.put("usage", Arrays.asList(
            "1. Open " + webPlayerUrl + " in your browser",
            "2. Use the interface to play audio files",
            "3. Audio will play on your CLIENT machine (where browser runs)"
        ));
        
        return result;
    }
    
    // Helper methods
    
    private boolean isValidUrl(String url) {
        try {
            new URL(url);
            return url.startsWith("http://") || url.startsWith("https://");
        } catch (Exception e) {
            return false;
        }
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
    
    private boolean hasAudioExtension(String filename) {
        String lowerFilename = filename.toLowerCase();
        return SUPPORTED_FORMATS.stream().anyMatch(lowerFilename::endsWith);
    }
}