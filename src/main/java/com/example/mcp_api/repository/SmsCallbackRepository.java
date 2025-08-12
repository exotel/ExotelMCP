package com.example.mcp_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.mcp_api.entity.SmsCallback;
import java.util.List;
import java.util.Optional;

@Repository
public interface SmsCallbackRepository extends JpaRepository<SmsCallback, Long> {
    List<SmsCallback> findByToNumberAndUserId(String toNumber, String userId);
    Optional<SmsCallback> findBySmsSid(String smsSid);
    
    // Enhanced search: Find SMS callbacks by phone number in to_number with user_id security
    // Note: SMS typically only has to_number (recipient), but keeping it consistent with voice callbacks
    @Query("SELECT sc FROM SmsCallback sc WHERE sc.toNumber = :phoneNumber AND sc.userId = :userId")
    List<SmsCallback> findByPhoneNumberAndUserId(@Param("phoneNumber") String phoneNumber, @Param("userId") String userId);
}