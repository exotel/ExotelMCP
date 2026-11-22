package com.example.mcp_api.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.mcp_api.service.ExotelService;
import com.example.mcp_api.dto.BulkSMSRequest;
import com.example.mcp_api.dto.BulkDynamicSMS;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class ExotelController {
    
    private static final Logger logger = LoggerFactory.getLogger(ExotelController.class);
    
    @Autowired
    private ExotelService exotelService;
    

    
    @GetMapping("/send-sms-to-user")
    public ResponseEntity<?> sendSmsToUser(
            @RequestParam String toNumber,
            @RequestParam String message,
            @RequestParam String dltTemplateId,
            @RequestParam String dltEntityId,
            HttpServletRequest request) {
        
        logger.info("Sending SMS to: {}", toNumber);
        try {
            String response = exotelService.sendSmsToUserEndpoint(toNumber, message, dltTemplateId, dltEntityId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error sending SMS", e);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
    
    @GetMapping("/send-voice-call-to-user")
    public ResponseEntity<?> sendVoiceCallToUser(
            @RequestParam String toNumber,
            HttpServletRequest request) {
        
        logger.info("Sending voice call to: {}", toNumber);
        try {
            String response = exotelService.sendVoiceCallToUserEndpoint(toNumber);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error sending voice call", e);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
    
    @GetMapping("/outgoing-call-to-connect-number")
    public ResponseEntity<?> callConnect(
            @RequestParam String fromNumber,
            @RequestParam String toNumber,
            HttpServletRequest request) {
        
        logger.info("Connecting call from: {} to: {}", fromNumber, toNumber);
        try {
            String response = exotelService.outgoingCallToConnectNumber(fromNumber, toNumber);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error connecting call", e);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
    
    @GetMapping("/get-sms-callbacks")
    public ResponseEntity<?> getSmsCallbacks(
            @RequestParam String toNumber,
            HttpServletRequest request) {
        
        logger.info("Fetching SMS callbacks for: {}", toNumber);
        try {
            Map<String, Object> response = exotelService.getSmsCallbacks(toNumber);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching SMS callbacks", e);
            return ResponseEntity.badRequest().body(Map.of("status_data", "Not found because " + e.getMessage()));
        }
    }
    
    @GetMapping("/get-voice-call-callbacks")
    public ResponseEntity<?> getVoiceCallbacks(
            @RequestParam String toNumber,
            HttpServletRequest request) {
        
        logger.info("Fetching voice callbacks for: {}", toNumber);
        try {
            Map<String, Object> response = exotelService.getVoiceCallCallbacks(toNumber);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching voice callbacks", e);
            return ResponseEntity.badRequest().body(Map.of("status_data", "Not found because " + e.getMessage()));
        }
    }
    
    @GetMapping("/get-bulk-call-details")
    public ResponseEntity<?> getBulkCallDetails(
            @RequestParam String fromNumber,
            HttpServletRequest request) {
        
        logger.info("Fetching bulk voice call details...");
        try {
            String response = exotelService.getBulkCallDetails(fromNumber);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching bulk call details", e);
            return ResponseEntity.badRequest().body(Map.of("data", "Not able to fetch bulk call details due to " + e.getMessage()));
        }
    }
    
    @GetMapping("/get-number-metadata")
    public ResponseEntity<?> getNumberMetadata(
            @RequestParam String number,
            HttpServletRequest request) {
        
        logger.info("Fetching number metadata...");
        try {
            String response = exotelService.getNumberMetadata(number);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching number metadata", e);
            return ResponseEntity.badRequest().body("Not able to fetch number metadata due to " + e.getMessage());
        }
    }
    
    @PostMapping("/send-message-to-bulk-numbers")
    public ResponseEntity<?> sendBulkSms(
            @RequestBody BulkSMSRequest payload,
            HttpServletRequest request) {
        
        logger.info("Sending bulk SMS to: {}", payload.toNumber());
        try {
            String response = exotelService.sendMessageToBulkNumbers(payload.toNumber(), payload.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error sending bulk SMS", e);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
    
    @GetMapping("/connect-number-to-call-flow")
    public ResponseEntity<?> connectFlow(
            @RequestParam String appId,
            @RequestParam String fromNumber,
            HttpServletRequest request) {
        
        logger.info("Connecting to call flow: {}", appId);
        try {
            String response = exotelService.connectNumberToCallFlow(appId, fromNumber);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error connecting to call flow", e);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
    
    @GetMapping("/get-call-flow-callbacks")
    public ResponseEntity<?> getCallFlowCallbacks(@RequestParam String fromNumber) {
        logger.info("Fetching call flow callbacks for: {}", fromNumber);
        try {
            Map<String, Object> result = exotelService.getCallFlowCallbacks(fromNumber);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Database error: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status_data", "Not found because " + e.getMessage()));
        }
    }
    
    @PostMapping("/send-dynamic-bulk-sms")
    public ResponseEntity<?> sendDynamicBulkSms(
            @RequestBody BulkDynamicSMS payload,
            HttpServletRequest request) {
        
        logger.info("Sending dynamic bulk SMS with {} messages", payload.message().size());
        try {
            String response = exotelService.sendDynamicBulkSmsEndpoint(payload.message());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error sending dynamic bulk SMS", e);
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}