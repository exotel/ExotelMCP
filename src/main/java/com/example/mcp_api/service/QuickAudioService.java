package com.example.mcp_api.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@Service
public class QuickAudioService {
    
    private static final Logger logger = LoggerFactory.getLogger(QuickAudioService.class);
    
    @Value("${exotel.base.url:http://localhost:8085}")
    private String baseUrl;
    
    @Tool(name = "exotel_audio_open_player", 
          description = "Open Audio Player - Get a clickable link to open the web audio player interface where you can play any audio URL or manage playback.")
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
}
