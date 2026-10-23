package com.example.mcp_api.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void buildMessageBodyWrapsTextMessageUnderWhatsappMessages() {
        Map<String, Object> body = WhatsAppService.buildMessageBody(
                "+919876500001", "+919876543210", "text",
                null, null, null, "Hello from Exotel!");

        Map<String, Object> whatsapp = (Map<String, Object>) body.get("whatsapp");
        assertNotNull(whatsapp, "body must be wrapped under 'whatsapp'");
        List<Map<String, Object>> messages = (List<Map<String, Object>>) whatsapp.get("messages");
        assertEquals(1, messages.size());

        Map<String, Object> msg = messages.get(0);
        assertEquals("+919876500001", msg.get("from"));
        assertEquals("+919876543210", msg.get("to"));

        Map<String, Object> content = (Map<String, Object>) msg.get("content");
        assertEquals("individual", content.get("recipient_type"));
        assertEquals("text", content.get("type"));
        assertEquals("Hello from Exotel!", ((Map<String, Object>) content.get("text")).get("body"));
        assertNull(content.get("template"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildMessageBodyBuildsTemplateWithLanguageAndBodyParams() {
        Map<String, Object> body = WhatsAppService.buildMessageBody(
                "+919876500001", "+919876543210", "template",
                "order_confirmation", "en_US", List.of("Rahul", "#ORD-12345"), null);

        Map<String, Object> content = (Map<String, Object>)
                ((List<Map<String, Object>>) ((Map<String, Object>) body.get("whatsapp")).get("messages"))
                        .get(0).get("content");

        assertEquals("template", content.get("type"));
        Map<String, Object> template = (Map<String, Object>) content.get("template");
        assertEquals("order_confirmation", template.get("name"));

        Map<String, Object> language = (Map<String, Object>) template.get("language");
        assertEquals("en_US", language.get("code"));
        assertEquals("deterministic", language.get("policy"));

        List<Map<String, Object>> components = (List<Map<String, Object>>) template.get("components");
        assertEquals(1, components.size());
        assertEquals("body", components.get(0).get("type"));
        List<Map<String, Object>> params = (List<Map<String, Object>>) components.get(0).get("parameters");
        assertEquals(2, params.size());
        assertEquals("text", params.get(0).get("type"));
        assertEquals("Rahul", params.get(0).get("text"));
        assertEquals("#ORD-12345", params.get(1).get("text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildMessageBodyDefaultsTemplateLanguageToEnWhenBlank() {
        Map<String, Object> body = WhatsAppService.buildMessageBody(
                "+919876500001", "+919876543210", "template",
                "hello_world", null, null, null);

        Map<String, Object> template = (Map<String, Object>)
                ((Map<String, Object>) ((List<Map<String, Object>>)
                        ((Map<String, Object>) body.get("whatsapp")).get("messages")).get(0).get("content"))
                        .get("template");
        assertEquals("en", ((Map<String, Object>) template.get("language")).get("code"));
        assertNull(template.get("components"), "no components when no params supplied");
    }
}
