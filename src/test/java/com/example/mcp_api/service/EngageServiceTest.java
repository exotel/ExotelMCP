package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngageServiceTest {

    @Test
    void buildSmsCampaignBodyIncludesRequiredFieldsAndOmitsBlankOptionals() {
        Map<String, Object> body = EngageService.buildSmsCampaignBody(
                "July promo",
                List.of("list_sid"),
                "EXOTEL",
                "transactional",
                "Hello from Engage https://example.com/promo",
                "123456",
                "789012",
                null,
                "  ",
                "",
                null,
                null,
                null,
                null,
                null);

        assertEquals("sms", body.get("channel"));
        assertEquals("static", body.get("content_type"));
        assertEquals("July promo", body.get("name"));
        assertEquals(List.of("list_sid"), body.get("lists"));
        assertEquals("EXOTEL", body.get("from"));
        assertEquals("transactional", body.get("message_type"));
        assertEquals("Hello from Engage https://example.com/promo", body.get("template"));
        assertEquals("123456", body.get("dlt_entity_id"));
        assertEquals("789012", body.get("template_id"));

        assertFalse(body.containsKey("schedule"));
        assertFalse(body.containsKey("status_callback"));
        assertFalse(body.containsKey("message_status_callback"));
        assertFalse(body.containsKey("sms_url_shortening"));
        assertFalse(body.containsKey("shorten_url_header"));
        assertFalse(body.containsKey("click_url_event_enabled"));
        assertFalse(body.containsKey("click_tracking_callback_url"));
    }

    @Test
    void buildSmsCampaignBodyIncludesScheduleAndOptionalFlags() {
        Map<String, Object> body = EngageService.buildSmsCampaignBody(
                "July promo",
                List.of("list_a", "list_b"),
                "EXOTEL",
                "transactional",
                "Hello",
                "123456",
                "789012",
                "2026-08-10T18:00:00+05:30",
                "2026-08-10T20:00:00+05:30",
                "https://your.app/webhooks/campaign-status",
                "https://your.app/webhooks/message-status",
                true,
                "EXOTEL",
                true,
                "https://your.app/webhooks/clicks");

        @SuppressWarnings("unchecked")
        Map<String, Object> schedule = (Map<String, Object>) body.get("schedule");
        assertNotNull(schedule);
        assertEquals("2026-08-10T18:00:00+05:30", schedule.get("start_time"));
        assertEquals("2026-08-10T20:00:00+05:30", schedule.get("end_time"));
        assertEquals("https://your.app/webhooks/campaign-status", body.get("status_callback"));
        assertEquals("https://your.app/webhooks/message-status", body.get("message_status_callback"));
        assertEquals(true, body.get("sms_url_shortening"));
        assertEquals("EXOTEL", body.get("shorten_url_header"));
        assertEquals(true, body.get("click_url_event_enabled"));
        assertEquals("https://your.app/webhooks/clicks", body.get("click_tracking_callback_url"));
        assertEquals(List.of("list_a", "list_b"), body.get("lists"));
    }
}
