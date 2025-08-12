package com.example.mcp_api.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "voice_callbacks")
public class VoiceCallback {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private String userId;
    
    @Column(name = "sid")
    private String sid;
    
    @Column(name = "parent_call_sid")
    private String parentCallSid;
    
    @Column(name = "date_created")
    private String dateCreated;
    
    @Column(name = "date_updated")
    private String dateUpdated;
    
    @Column(name = "account_sid")
    private String accountSid;
    
    @Column(name = "to_number")
    private String toNumber;
    
    @Column(name = "from_number")
    private String fromNumber;
    
    @Column(name = "phone_number_sid")
    private String phoneNumberSid;
    
    @Column(name = "start_time")
    private String startTime;
    
    @Column(name = "end_time")
    private String endTime;
    
    @Column(name = "duration")
    private String duration;
    
    @Column(name = "price")
    private String price;
    
    @Column(name = "direction")
    private String direction;
    
    @Column(name = "answered_by")
    private String answeredBy;
    
    @Column(name = "forwarded_from")
    private String forwardedFrom;
    
    @Column(name = "caller_name")
    private String callerName;
    
    @Column(name = "uri")
    private String uri;
    
    @Column(name = "recording_url")
    private String recordingUrl;
    
    @Column(name = "call_sid")
    private String callSid;
    
    @Column(name = "status")
    private String status;
    
    // Default constructor
    public VoiceCallback() {}
    
    // Constructor
    public VoiceCallback(String userId, String sid, String parentCallSid, String dateCreated, 
                        String dateUpdated, String accountSid, String toNumber, String fromNumber,
                        String phoneNumberSid, String startTime, String endTime, String duration,
                        String price, String direction, String answeredBy, String forwardedFrom,
                        String callerName, String uri, String recordingUrl, String callSid, String status) {
        this.userId = userId;
        this.sid = sid;
        this.parentCallSid = parentCallSid;
        this.dateCreated = dateCreated;
        this.dateUpdated = dateUpdated;
        this.accountSid = accountSid;
        this.toNumber = toNumber;
        this.fromNumber = fromNumber;
        this.phoneNumberSid = phoneNumberSid;
        this.startTime = startTime;
        this.endTime = endTime;
        this.duration = duration;
        this.price = price;
        this.direction = direction;
        this.answeredBy = answeredBy;
        this.forwardedFrom = forwardedFrom;
        this.callerName = callerName;
        this.uri = uri;
        this.recordingUrl = recordingUrl;
        this.callSid = callSid;
        this.status = status;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getSid() { return sid; }
    public void setSid(String sid) { this.sid = sid; }
    
    public String getParentCallSid() { return parentCallSid; }
    public void setParentCallSid(String parentCallSid) { this.parentCallSid = parentCallSid; }
    
    public String getDateCreated() { return dateCreated; }
    public void setDateCreated(String dateCreated) { this.dateCreated = dateCreated; }
    
    public String getDateUpdated() { return dateUpdated; }
    public void setDateUpdated(String dateUpdated) { this.dateUpdated = dateUpdated; }
    
    public String getAccountSid() { return accountSid; }
    public void setAccountSid(String accountSid) { this.accountSid = accountSid; }
    
    public String getToNumber() { return toNumber; }
    public void setToNumber(String toNumber) { this.toNumber = toNumber; }
    
    public String getFromNumber() { return fromNumber; }
    public void setFromNumber(String fromNumber) { this.fromNumber = fromNumber; }
    
    public String getPhoneNumberSid() { return phoneNumberSid; }
    public void setPhoneNumberSid(String phoneNumberSid) { this.phoneNumberSid = phoneNumberSid; }
    
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
    
    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }
    
    public String getPrice() { return price; }
    public void setPrice(String price) { this.price = price; }
    
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    
    public String getAnsweredBy() { return answeredBy; }
    public void setAnsweredBy(String answeredBy) { this.answeredBy = answeredBy; }
    
    public String getForwardedFrom() { return forwardedFrom; }
    public void setForwardedFrom(String forwardedFrom) { this.forwardedFrom = forwardedFrom; }
    
    public String getCallerName() { return callerName; }
    public void setCallerName(String callerName) { this.callerName = callerName; }
    
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    
    public String getRecordingUrl() { return recordingUrl; }
    public void setRecordingUrl(String recordingUrl) { this.recordingUrl = recordingUrl; }
    
    public String getCallSid() { return callSid; }
    public void setCallSid(String callSid) { this.callSid = callSid; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    @Override
    public String toString() {
        return String.format("{" +
            "\"id\": %d, " +
            "\"userId\": \"%s\", " +
            "\"sid\": \"%s\", " +
            "\"parentCallSid\": \"%s\", " +
            "\"dateCreated\": \"%s\", " +
            "\"dateUpdated\": \"%s\", " +
            "\"accountSid\": \"%s\", " +
            "\"toNumber\": \"%s\", " +
            "\"fromNumber\": \"%s\", " +
            "\"phoneNumberSid\": \"%s\", " +
            "\"startTime\": \"%s\", " +
            "\"endTime\": \"%s\", " +
            "\"duration\": \"%s\", " +
            "\"price\": \"%s\", " +
            "\"direction\": \"%s\", " +
            "\"answeredBy\": \"%s\", " +
            "\"forwardedFrom\": \"%s\", " +
            "\"callerName\": \"%s\", " +
            "\"uri\": \"%s\", " +
            "\"recordingUrl\": \"%s\", " +
            "\"callSid\": \"%s\", " +
            "\"status\": \"%s\"" +
            "}",
            id,
            userId != null ? userId : "",
            sid != null ? sid : "",
            parentCallSid != null ? parentCallSid : "",
            dateCreated != null ? dateCreated : "",
            dateUpdated != null ? dateUpdated : "",
            accountSid != null ? accountSid : "",
            toNumber != null ? toNumber : "",
            fromNumber != null ? fromNumber : "",
            phoneNumberSid != null ? phoneNumberSid : "",
            startTime != null ? startTime : "",
            endTime != null ? endTime : "",
            duration != null ? duration : "",
            price != null ? price : "",
            direction != null ? direction : "",
            answeredBy != null ? answeredBy : "",
            forwardedFrom != null ? forwardedFrom : "",
            callerName != null ? callerName : "",
            uri != null ? uri : "",
            recordingUrl != null ? recordingUrl : "",
            callSid != null ? callSid : "",
            status != null ? status : ""
        );
    }
}