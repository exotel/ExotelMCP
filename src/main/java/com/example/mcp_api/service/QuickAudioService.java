package com.example.mcp_api.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.*;

@Service
public class QuickAudioService {
    
    private static final Logger logger = LoggerFactory.getLogger(QuickAudioService.class);
    
    @Value("${exotel.base.url:http://localhost:8085}")
    private String baseUrl;
    
    @Tool(name = "quickPlayAudio", 
          description = "🎵 Quick Play Audio - One-click audio playback! Provide any audio URL and get a direct clickable link to play it instantly in your browser. No copy-paste needed!")
    public Map<String, Object> quickPlayAudio(String audioUrl) {
        logger.info("Quick play audio requested for: {}", audioUrl);
        
        try {
            if (!isValidUrl(audioUrl)) {
                throw new IllegalArgumentException("Please provide a valid audio URL (starting with http:// or https://)");
            }
            
            String webPlayerUrl = baseUrl + "/audio/player?url=" + java.net.URLEncoder.encode(audioUrl, "UTF-8");
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "ready_to_play");
            result.put("message", "🎵 Audio ready for playback!");
            result.put("audio_url", audioUrl);
            result.put("click_to_play", webPlayerUrl);
            result.put("instructions", "👆 Click the link above to start playing audio instantly!");
            result.put("note", "Audio will play on YOUR computer through your browser");
            
            logger.info("Generated quick play link: {}", webPlayerUrl);
            return result;
            
        } catch (Exception e) {
            logger.error("Error in quick play audio: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("example", "Try: quickPlayAudio('https://example.com/audio.mp3')");
            return error;
        }
    }
    
    @Tool(name = "openAudioPlayer", 
          description = "🎛️ Open Audio Player - Get a clickable link to open the web audio player interface where you can play any audio URL or manage playback.")
    public Map<String, Object> openAudioPlayer() {
        String webPlayerUrl = baseUrl + "/audio/player";
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "player_ready");
        result.put("message", "🎛️ Audio Player ready to use!");
        result.put("click_to_open", webPlayerUrl);
        result.put("instructions", "👆 Click the link above to open the audio player interface");
        result.put("features", Arrays.asList(
            "🎵 Play any audio URL directly",
            "🎛️ Full audio controls (play, pause, volume, seek)",
            "📱 Works on desktop and mobile",
            "🔄 Real-time playback on your device"
        ));
        result.put("note", "The player opens in your browser for client-side audio playback");
        
        logger.info("Generated audio player link: {}", webPlayerUrl);
        return result;
    }
    
    @Tool(name = "downloadAudioQuick", 
          description = "💾 Quick Download Audio - Get a direct download link for any audio URL. Click to download the audio file to your computer.")
    public Map<String, Object> downloadAudioQuick(String audioUrl) {
        logger.info("Quick download requested for: {}", audioUrl);
        
        try {
            if (!isValidUrl(audioUrl)) {
                throw new IllegalArgumentException("Please provide a valid audio URL (starting with http:// or https://)");
            }
            
            String downloadUrl = baseUrl + "/audio/download?url=" + java.net.URLEncoder.encode(audioUrl, "UTF-8");
            String filename = getFilenameFromUrl(audioUrl);
            if (filename == null) {
                filename = "audio_" + System.currentTimeMillis() + ".mp3";
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "download_ready");
            result.put("message", "💾 Audio ready for download!");
            result.put("source_url", audioUrl);
            result.put("click_to_download", downloadUrl);
            result.put("filename", filename);
            result.put("instructions", "👆 Click the link above to download the audio file to your computer");
            result.put("note", "File will be saved to your default Downloads folder");
            
            logger.info("Generated download link: {}", downloadUrl);
            return result;
            
        } catch (Exception e) {
            logger.error("Error in quick download: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("example", "Try: downloadAudioQuick('https://example.com/audio.mp3')");
            return error;
        }
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
}