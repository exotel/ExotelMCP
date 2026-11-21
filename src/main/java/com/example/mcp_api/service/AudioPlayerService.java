package com.example.mcp_api.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.awt.GraphicsEnvironment;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class AudioPlayerService {
    
    private static final Logger logger = LoggerFactory.getLogger(AudioPlayerService.class);
    
    @Value("${audio.player.directory:#{systemProperties['user.home']}/Music}")
    private String audioDirectoryPath;
    
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private volatile Clip currentClip;
    private volatile String currentlyPlaying;
    private volatile int volume = 5; // 0-10 scale
    
    // Temporary files cleanup tracking
    private final Set<Path> temporaryFiles = Collections.synchronizedSet(new HashSet<>());
    
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
        ".wav", ".mp3", ".ogg", ".flac", ".aiff", ".au"
    );
    
    @Tool(name = "audio_player_list_local_files", 
          description = "List all available audio files in the configured audio directory. Returns a list of audio files with supported formats (wav, mp3, ogg, flac, aiff, au).")
    public Map<String, Object> listAudioFiles() {
        logger.info("Listing audio files from directory: {}", audioDirectoryPath);
        
        try {
            Path audioDir = Paths.get(audioDirectoryPath);
            
            // Create directory if it doesn't exist
            if (!Files.exists(audioDir)) {
                logger.info("Audio directory doesn't exist, creating: {}", audioDir);
                Files.createDirectories(audioDir);
            }
            
            List<String> audioFiles = Files.list(audioDir)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String filename = path.getFileName().toString().toLowerCase();
                    return SUPPORTED_FORMATS.stream().anyMatch(filename::endsWith);
                })
                .map(path -> path.getFileName().toString())
                .sorted()
                .collect(Collectors.toList());
            
            logger.info("Found {} audio files", audioFiles.size());
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("files", audioFiles);
            result.put("count", audioFiles.size());
            result.put("directory", audioDirectoryPath);
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error listing audio files", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Error listing audio files: " + e.getMessage());
            return error;
        }
    }
    
    @Tool(name = "audio_player_play_file",
          description = "Play an audio file by plain filename from the configured audio directory (see audio_player_list_local_files). URLs are not supported. Example: 'song.mp3'. Note: Audio plays on the server machine in SSE mode.")
    public Map<String, Object> playAudio(String filenameOrUrl) {
        if (filenameOrUrl == null || filenameOrUrl.isBlank()) {
            return errorResult("filename is required");
        }
        if (looksLikeUrl(filenameOrUrl)) {
            return errorResult("URLs are not supported; provide a plain filename from the audio directory");
        }
        logger.info("Attempting to play audio: {}", filenameOrUrl);
        
        // Check if audio system is available
        Map<String, Object> systemCheck = checkAudioSystemAvailability();
        if (!"available".equals(systemCheck.get("status"))) {
            return systemCheck;
        }
        
        try {
            // Stop any current playback
            if (isPlaying.get()) {
                stopCurrentPlayback();
            }
            
            Path audioDir = Paths.get(audioDirectoryPath);
            Path audioFile = audioDir.resolve(Paths.get(filenameOrUrl).getFileName());
            String displayName = audioFile.getFileName().toString();

            if (!Files.exists(audioFile)) {
                throw new FileNotFoundException("Audio file not found: " + displayName);
            }

            // Security check - ensure file is within audio directory
            if (!audioFile.toAbsolutePath().normalize().startsWith(audioDir.toAbsolutePath().normalize())) {
                throw new SecurityException("File must be in the audio directory");
            }

            // Load and play the audio
            playAudioFile(audioFile, displayName);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "playing");
            result.put("file", displayName);
            result.put("volume", volume);
            result.put("source", "local");
            result.put("server_info", "Audio playing on server machine: " + getServerInfo());
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error playing audio: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Playback error: " + e.getMessage());
            return error;
        }
    }
    
    @Tool(name = "audio_player_stop_playback",
          description = "Stop current audio playback and clean up any temporary files.")
    public Map<String, Object> stopAudio() {
        logger.info("Stopping audio playback");
        
        try {
            stopCurrentPlayback();
            cleanupTemporaryFiles();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "stopped");
            result.put("message", "Audio playback stopped and temporary files cleaned up");
            
            return result;
            
        } catch (Exception e) {
            logger.error("Error stopping audio", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Stop error: " + e.getMessage());
            return error;
        }
    }
    
    @Tool(name = "audio_player_pause_resume",
          description = "Pause or resume current audio playback.")
    public Map<String, Object> pauseAudio() {
        logger.info("Toggling pause state");
        
        try {
            if (currentClip == null || !isPlaying.get()) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "no_audio");
                result.put("message", "No audio currently playing");
                return result;
            }
            
            if (isPaused.get()) {
                // Resume
                currentClip.start();
                isPaused.set(false);
                logger.info("Resumed audio playback");
                
                Map<String, Object> result = new HashMap<>();
                result.put("status", "resumed");
                result.put("file", currentlyPlaying);
                return result;
            } else {
                // Pause
                currentClip.stop();
                isPaused.set(true);
                logger.info("Paused audio playback");
                
                Map<String, Object> result = new HashMap<>();
                result.put("status", "paused");
                result.put("file", currentlyPlaying);
                return result;
            }
            
        } catch (Exception e) {
            logger.error("Error pausing/resuming audio", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Pause/resume error: " + e.getMessage());
            return error;
        }
    }
    
    @Tool(name = "audio_player_set_volume",
          description = "Set the audio volume (0-10 scale). Volume changes apply to new audio playback.")
    public Map<String, Object> setVolume(int newVolume) {
        logger.info("Setting volume to: {}", newVolume);
        
        if (newVolume < 0 || newVolume > 10) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", "Volume must be between 0 and 10");
            return error;
        }
        
        this.volume = newVolume;
        
        // Apply volume to current clip if playing
        if (currentClip != null && isPlaying.get()) {
            applyVolumeToClip(currentClip, newVolume);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("volume", volume);
        result.put("message", "Volume set to " + volume + "/10");
        
        return result;
    }
    
    @Tool(name = "audio_player_get_current_status",
          description = "Get current audio player status including playback state, current file, and volume.")
    public Map<String, Object> getAudioStatus() {
        Map<String, Object> status = new HashMap<>();
        
        if (isPlaying.get()) {
            status.put("status", isPaused.get() ? "paused" : "playing");
            status.put("file", currentlyPlaying);
        } else {
            status.put("status", "stopped");
            status.put("file", null);
        }
        
        status.put("volume", volume);
        status.put("temporary_files", temporaryFiles.size());
        status.put("server_info", getServerInfo());
        status.put("audio_system", checkAudioSystemAvailability());
        
        return status;
    }
    
    @Tool(name = "audio_player_check_system_health",
          description = "Check if the server has audio system capabilities for playback. Useful for diagnosing audio issues in remote/headless environments.")
    public Map<String, Object> checkAudioSystem() {
        return checkAudioSystemAvailability();
    }
    
    // Helper methods
    
    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("message", message);
        return error;
    }

    private static boolean looksLikeUrl(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.contains("://") || v.startsWith("//") || v.matches("^[a-z][a-z0-9+.-]*:.*");
    }

    private void playAudioFile(Path audioFile, String displayName) throws Exception {
        logger.info("Loading audio file: {}", audioFile);
        
        try (AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFile.toFile())) {
            AudioFormat format = audioInputStream.getFormat();
            
            // Convert to PCM if needed
            AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                format.getSampleRate(),
                16,
                format.getChannels(),
                format.getChannels() * 2,
                format.getSampleRate(),
                false
            );
            
            AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, audioInputStream);
            
            // Create and configure clip
            currentClip = AudioSystem.getClip();
            currentClip.open(pcmStream);
            
            // Apply volume
            applyVolumeToClip(currentClip, volume);
            
            // Set up completion listener
            currentClip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    if (!isPaused.get()) {
                        // Natural completion, not paused
                        isPlaying.set(false);
                        currentlyPlaying = null;
                        logger.info("Audio playback completed naturally");
                    }
                }
            });
            
            // Start playback
            currentClip.start();
            isPlaying.set(true);
            isPaused.set(false);
            currentlyPlaying = displayName;
            
            logger.info("Started playing: {} at volume {}/10", displayName, volume);
        }
    }
    
    private void applyVolumeToClip(Clip clip, int volumeLevel) {
        try {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl volumeControl = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float min = volumeControl.getMinimum();
                float max = volumeControl.getMaximum();
                
                // Convert 0-10 scale to decibel range
                float volumeDb;
                if (volumeLevel == 0) {
                    volumeDb = min; // Minimum volume (mute)
                } else {
                    // Linear interpolation between min and max
                    volumeDb = min + (max - min) * (volumeLevel / 10.0f);
                }
                
                volumeControl.setValue(volumeDb);
                logger.debug("Applied volume: {} dB (level {}/10)", volumeDb, volumeLevel);
            }
        } catch (Exception e) {
            logger.warn("Could not apply volume control: {}", e.getMessage());
        }
    }
    
    private void stopCurrentPlayback() {
        if (currentClip != null) {
            if (currentClip.isRunning()) {
                currentClip.stop();
            }
            currentClip.close();
            currentClip = null;
        }
        isPlaying.set(false);
        isPaused.set(false);
        currentlyPlaying = null;
        logger.info("Stopped current playback");
    }
    
    private void cleanupTemporaryFiles() {
        logger.info("Cleaning up {} temporary files", temporaryFiles.size());
        
        for (Path tempFile : new HashSet<>(temporaryFiles)) {
            try {
                if (Files.exists(tempFile)) {
                    Files.delete(tempFile);
                    logger.debug("Deleted temporary file: {}", tempFile);
                }
                temporaryFiles.remove(tempFile);
            } catch (Exception e) {
                logger.warn("Failed to delete temporary file: {}", tempFile, e);
            }
        }
        
        // Also try to clean up the temp directory if empty
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "audio_player_mcp");
            if (Files.exists(tempDir) && Files.list(tempDir).findAny().isEmpty()) {
                Files.delete(tempDir);
                logger.debug("Deleted empty temporary directory: {}", tempDir);
            }
        } catch (Exception e) {
            logger.debug("Could not clean up temporary directory: {}", e.getMessage());
        }
    }
    
    // Helper method to check audio system availability
    private Map<String, Object> checkAudioSystemAvailability() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // Check if we're in a headless environment
            boolean isHeadless = GraphicsEnvironment.isHeadless();
            
            // Check if audio system is available
            boolean audioSystemAvailable = AudioSystem.getMixerInfo().length > 0;
            
            // Check for audio output devices
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            int outputDevices = 0;
            for (Mixer.Info mixerInfo : mixers) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.getTargetLineInfo().length > 0) {
                    outputDevices++;
                }
            }
            
            if (!audioSystemAvailable || outputDevices == 0) {
                result.put("status", "unavailable");
                result.put("message", "No audio output devices available on server");
                result.put("headless", isHeadless);
                result.put("mixers_count", mixers.length);
                result.put("output_devices", outputDevices);
                return result;
            }
            
            result.put("status", "available");
            result.put("message", "Audio system ready");
            result.put("headless", isHeadless);
            result.put("mixers_count", mixers.length);
            result.put("output_devices", outputDevices);
            
            // List available mixers for debugging
            List<String> mixerNames = new ArrayList<>();
            for (Mixer.Info mixerInfo : mixers) {
                mixerNames.add(mixerInfo.getName());
            }
            result.put("available_mixers", mixerNames);
            
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "Error checking audio system: " + e.getMessage());
            logger.error("Error checking audio system", e);
        }
        
        return result;
    }
    
    // Helper method to get server information
    private String getServerInfo() {
        try {
            String hostname = java.net.InetAddress.getLocalHost().getHostName();
            String os = System.getProperty("os.name");
            return hostname + " (" + os + ")";
        } catch (Exception e) {
            return "Unknown server";
        }
    }
    
    // Cleanup on bean destruction
    @jakarta.annotation.PreDestroy
    public void cleanup() {
        logger.info("AudioPlayerService shutting down");
        stopCurrentPlayback();
        cleanupTemporaryFiles();
    }
}