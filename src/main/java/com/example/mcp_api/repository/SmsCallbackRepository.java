package com.example.mcp_api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.example.mcp_api.entity.SmsCallback;
import java.util.List;

@Repository
public interface SmsCallbackRepository extends JpaRepository<SmsCallback, Long> {
    List<SmsCallback> findByToNumberAndUserId(String toNumber, String userId);

    // MSG-2804: the caller's own sends to a number, newest first; status is then read live from Exotel by SmsSid
    List<SmsCallback> findTop20ByUserIdAndToNumberOrderByIdDesc(String userId, String toNumber);
}