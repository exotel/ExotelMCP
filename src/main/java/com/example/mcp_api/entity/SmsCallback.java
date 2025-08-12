package com.example.mcp_api.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sms_callbacks")
public class SmsCallback {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id")
    private String userId;
    
    @Column(name = "sms_sid")
    private String smsSid;
    
    @Column(name = "to_number")
    private String toNumber;
    
    @Column(name = "status")
    private String status;
    
    @Column(name = "detailed_status")
    private String detailedStatus;
    
    @Column(name = "detailed_status_code")
    private String detailedStatusCode;
    
    @Column(name = "sms_units")
    private String smsUnits;
    
    @Column(name = "date_sent")
    private String dateSent;
    
    // Default constructor
    public SmsCallback() {}
    
    // Constructor
    public SmsCallback(String userId, String smsSid, String toNumber, String status, 
                      String detailedStatus, String detailedStatusCode, String smsUnits, String dateSent) {
        this.userId = userId;
        this.smsSid = smsSid;
        this.toNumber = toNumber;
        this.status = status;
        this.detailedStatus = detailedStatus;
        this.detailedStatusCode = detailedStatusCode;
        this.smsUnits = smsUnits;
        this.dateSent = dateSent;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getSmsSid() { return smsSid; }
    public void setSmsSid(String smsSid) { this.smsSid = smsSid; }
    
    public String getToNumber() { return toNumber; }
    public void setToNumber(String toNumber) { this.toNumber = toNumber; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getDetailedStatus() { return detailedStatus; }
    public void setDetailedStatus(String detailedStatus) { this.detailedStatus = detailedStatus; }
    
    public String getDetailedStatusCode() { return detailedStatusCode; }
    public void setDetailedStatusCode(String detailedStatusCode) { this.detailedStatusCode = detailedStatusCode; }
    
    public String getSmsUnits() { return smsUnits; }
    public void setSmsUnits(String smsUnits) { this.smsUnits = smsUnits; }
    
    public String getDateSent() { return dateSent; }
    public void setDateSent(String dateSent) { this.dateSent = dateSent; }
    
    @Override
    public String toString() {
        return String.format("{" +
            "\"id\": %d, " +
            "\"userId\": \"%s\", " +
            "\"smsSid\": \"%s\", " +
            "\"toNumber\": \"%s\", " +
            "\"status\": \"%s\", " +
            "\"detailedStatus\": \"%s\", " +
            "\"detailedStatusCode\": \"%s\", " +
            "\"smsUnits\": \"%s\", " +
            "\"dateSent\": \"%s\"" +
            "}",
            id,
            userId != null ? userId : "",
            smsSid != null ? smsSid : "",
            toNumber != null ? toNumber : "",
            status != null ? status : "",
            detailedStatus != null ? detailedStatus : "",
            detailedStatusCode != null ? detailedStatusCode : "",
            smsUnits != null ? smsUnits : "",
            dateSent != null ? dateSent : ""
        );
    }
}