package com.example.mcp_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.mcp_api.entity.VoiceCallback;
import java.util.List;
import java.util.Optional;

@Repository
public interface VoiceCallbackRepository extends JpaRepository<VoiceCallback, Long> {
    List<VoiceCallback> findByToNumberAndUserId(String toNumber, String userId);
    List<VoiceCallback> findByFromNumber(String fromNumber);
    Optional<VoiceCallback> findByCallSidAndUserId(String callSid, String userId);
    Optional<VoiceCallback> findByCallSid(String callSid);
    
    // Custom query with explicit logging for debugging
    @Query("SELECT vc FROM VoiceCallback vc WHERE vc.toNumber = :toNumber AND vc.userId = :userId")
    List<VoiceCallback> findByToNumberAndUserIdWithLogging(@Param("toNumber") String toNumber, @Param("userId") String userId);
    
    // Debug query to find all records with specific toNumber regardless of userId
    @Query("SELECT vc FROM VoiceCallback vc WHERE vc.toNumber = :toNumber")
    List<VoiceCallback> findByToNumberOnly(@Param("toNumber") String toNumber);
    
    // Debug query to find all records with specific userId regardless of toNumber
    @Query("SELECT vc FROM VoiceCallback vc WHERE vc.userId = :userId")
    List<VoiceCallback> findByUserIdOnly(@Param("userId") String userId);
    
    // Enhanced search: Find by phone number in EITHER to_number OR from_number with user_id security
    @Query("SELECT vc FROM VoiceCallback vc WHERE (vc.toNumber = :phoneNumber OR vc.fromNumber = :phoneNumber) AND vc.userId = :userId")
    List<VoiceCallback> findByPhoneNumberInToOrFromAndUserId(@Param("phoneNumber") String phoneNumber, @Param("userId") String userId);
    
    // Enhanced search: Find by from_number with user_id security
    @Query("SELECT vc FROM VoiceCallback vc WHERE vc.fromNumber = :fromNumber AND vc.userId = :userId")
    List<VoiceCallback> findByFromNumberAndUserId(@Param("fromNumber") String fromNumber, @Param("userId") String userId);
}