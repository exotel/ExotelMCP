package com.example.mcp_api.service;

import com.example.mcp_api.auth.AuthCredentials;
import com.example.mcp_api.controller.ExotelController;
import com.example.mcp_api.dto.AuthData;
import com.example.mcp_api.dto.Message;
import com.example.mcp_api.entity.SmsCallback;
import com.example.mcp_api.repository.SmsCallbackRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** MSG-2804: SMS status is read live from Exotel by SmsSid; no callback route; form bodies are URL-encoded. */
class SmsToolsTest {

    private static final String TOKEN = "a2V5OnRvaw==";
    private static final String HEADER = "{\"token\":\"" + TOKEN + "\",\"account_sid\":\"acme1\",\"from_number\":\"08000000000\"}";
    private static final String SMS = "https://api.exotel.com/v1/Accounts/acme1/SMS/Messages/";
    private static final String INJECTED = "Hi&To=+911111111111&StatusCallback=https://evil";
    private static final String PLAIN = "Tom & Jerry 50% off";

    /** Stubs Exotel: records every request and answers GETs from a map keyed by URL. */
    static class StubbedExotelService extends ExotelService {
        final List<String> gets = new ArrayList<>();
        final List<Map<String, ?>> posts = new ArrayList<>();
        final Map<String, String> answers = new LinkedHashMap<>();
        String postAnswer = "{\"SMSMessage\":{\"Sid\":\"SM1\",\"To\":\"+919999999999\",\"Status\":\"queued\"}}";

        @Override
        String makeGetRequest(String url, AuthData authData) {
            gets.add(url);
            String body = answers.get(url);
            if (body == null) throw new IllegalArgumentException("Client error: HTTP 500 for GET " + url);
            return body;
        }

        @Override
        String makeHttpRequest(String url, Map<String, ?> data, AuthData authData) {
            posts.add(data);
            return postAnswer;
        }
    }

    private StubbedExotelService service;
    private SmsCallbackRepository repo;

    @BeforeEach
    void bind() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", HEADER);
        request.setAttribute(AuthCredentials.REQUEST_ATTRIBUTE, AuthCredentials.parse(HEADER));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        service = new StubbedExotelService();
        repo = mock(SmsCallbackRepository.class);
        ReflectionTestUtils.setField(service, "smsCallbackRepository", repo);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static SmsCallback row(String sid) {
        SmsCallback c = new SmsCallback();
        c.setSmsSid(sid);
        c.setToNumber("09999999999");
        return c;
    }

    private static String sms(String sid, String status) {
        return "{\"SMSMessage\":{\"Sid\":\"" + sid + "\",\"AccountSid\":\"acme1\",\"To\":\"09999999999\",\"Status\":\""
                + status + "\",\"DetailedStatus\":\"x\",\"DateSent\":\"2026-11-22 10:00:00\"}}";
    }

    /** What Exotel's form decoder sees: key to every value it was given. */
    private static Map<String, List<String>> decode(Map<String, ?> data) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String pair : ExotelService.buildFormData(data).split("&")) {
            String[] kv = pair.split("=", -1);
            assertEquals(2, kv.length, pair);
            out.computeIfAbsent(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), k -> new ArrayList<>())
               .add(URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }

    // T-1 (AC-1)
    @Test
    void noControllerMapsTheSmsStatusCallback() {
        for (Method m : ExotelController.class.getDeclaredMethods()) {
            List<String> paths = new ArrayList<>();
            if (m.isAnnotationPresent(PostMapping.class)) paths.addAll(Arrays.asList(m.getAnnotation(PostMapping.class).value()));
            if (m.isAnnotationPresent(GetMapping.class)) paths.addAll(Arrays.asList(m.getAnnotation(GetMapping.class).value()));
            if (m.isAnnotationPresent(PutMapping.class)) paths.addAll(Arrays.asList(m.getAnnotation(PutMapping.class).value()));
            if (m.isAnnotationPresent(PatchMapping.class)) paths.addAll(Arrays.asList(m.getAnnotation(PatchMapping.class).value()));
            if (m.isAnnotationPresent(DeleteMapping.class)) paths.addAll(Arrays.asList(m.getAnnotation(DeleteMapping.class).value()));
            if (m.isAnnotationPresent(RequestMapping.class)) paths.addAll(Arrays.asList(m.getAnnotation(RequestMapping.class).value()));
            for (String p : paths) assertFalse(p.contains("sms-status-callback"), m.getName() + " maps " + p);
        }
    }

    // T-2 (AC-2)
    @Test
    void sendToolsSendNoStatusCallback() {
        service.sendSmsToUser("+919999999999", "hello", "T1", "E1");
        service.postAnswer = "[" + sms("SM2", "queued") + "]";
        service.sendMessageToBulkNumbers(List.of("+919999999999"), "hello");
        service.sendDynamicBulkSms(List.of(new Message("hello", "+919999999999")));
        assertEquals(3, service.posts.size());
        for (Map<String, ?> data : service.posts) {
            assertFalse(data.containsKey("StatusCallback"), data.toString());
            assertFalse(data.containsKey("StatusCallbackContentType"), data.toString());
        }
    }

    // T-3 (AC-3)
    @Test
    void singleSendBodyWithInjectedParametersArrivesIntact() {
        service.sendSmsToUser("+919999999999", INJECTED, "T&1", "E1");
        Map<String, List<String>> form = decode(service.posts.get(0));
        assertEquals(List.of(INJECTED), form.get("Body"));
        assertEquals(List.of("+919999999999"), form.get("To"));
        assertEquals(List.of("T&1"), form.get("DltTemplateId"));
        assertEquals(List.of("08000000000"), form.get("From"));
        assertFalse(form.containsKey("StatusCallback"), form.toString());
    }

    @Test
    void bulkSendsKeepAmpersandAndPercentInTheBody() {
        service.postAnswer = "[" + sms("SM2", "queued") + "]";
        service.sendMessageToBulkNumbers(List.of("+919999999999", "+918888888888"), PLAIN);
        service.sendDynamicBulkSms(List.of(new Message(INJECTED, "+919999999999"), new Message(PLAIN, "+918888888888")));
        Map<String, List<String>> bulk = decode(service.posts.get(0));
        assertEquals(List.of(PLAIN), bulk.get("Body"));
        assertEquals(List.of("+919999999999"), bulk.get("To[0]"));
        assertEquals(List.of("+918888888888"), bulk.get("To[1]"));
        assertFalse(bulk.containsKey("To"), bulk.toString());
        Map<String, List<String>> dynamic = decode(service.posts.get(1));
        assertEquals(List.of(INJECTED), dynamic.get("Message[0][Body]"));
        assertEquals(List.of(PLAIN), dynamic.get("Message[1][Body]"));
        assertFalse(dynamic.containsKey("To"), dynamic.toString());
        assertFalse(dynamic.containsKey("StatusCallback"), dynamic.toString());
    }

    // T-4 (AC-4)
    @Test
    void singleSendIndexesTheMessageForItsOwner() throws Exception {
        service.sendSmsToUser("+919999999999", "hello", "T1", "E1");
        ArgumentCaptor<SmsCallback> saved = ArgumentCaptor.forClass(SmsCallback.class);
        verify(repo, timeout(2000)).save(saved.capture());
        assertEquals(md5(TOKEN), saved.getValue().getUserId());
        assertEquals("SM1", saved.getValue().getSmsSid());
        assertEquals("09999999999", saved.getValue().getToNumber());
    }

    @Test
    void bulkSendIndexesEveryMessage() throws Exception {
        service.postAnswer = "[" + sms("SM2", "queued") + "," + sms("SM3", "queued") + "]";
        service.sendMessageToBulkNumbers(List.of("+919999999999", "+919999999999"), "hello");
        ArgumentCaptor<SmsCallback> saved = ArgumentCaptor.forClass(SmsCallback.class);
        verify(repo, times(2)).save(saved.capture());
        assertEquals(List.of("SM2", "SM3"), saved.getAllValues().stream().map(SmsCallback::getSmsSid).toList());
        for (SmsCallback c : saved.getAllValues()) assertEquals(md5(TOKEN), c.getUserId());
    }

    @Test
    void noInboundCallbackWriterRemains() {
        for (Method m : ExotelService.class.getDeclaredMethods()) {
            assertNotEquals("saveSmsCallback", m.getName());
        }
    }

    // T-5 (AC-5)
    @Test
    @SuppressWarnings("unchecked")
    void deliveryStatusReadsTheCallersOwnMessagesLive() throws Exception {
        when(repo.findTop20ByUserIdAndToNumberOrderByIdDesc(anyString(), anyString())).thenReturn(List.of(row("SM1"), row("SM2")));
        service.answers.put(SMS + "SM1.json", sms("SM1", "delivered"));
        service.answers.put(SMS + "SM2.json", sms("SM2", "failed"));
        Map<String, Object> out = service.getSmsCallbacks("9999999999");
        verify(repo).findTop20ByUserIdAndToNumberOrderByIdDesc(md5(TOKEN), "09999999999");
        assertEquals(List.of(SMS + "SM1.json", SMS + "SM2.json"), service.gets);
        List<Map<String, Object>> data = (List<Map<String, Object>>) out.get("status_data");
        assertEquals(List.of("delivered", "failed"), data.stream().map(m -> m.get("Status")).toList());
        for (Map<String, Object> m : data) assertFalse(m.containsKey("userId"), m.toString());
        Map<String, Object> info = (Map<String, Object>) out.get("search_info");
        assertFalse(info.containsKey("user_id"), info.toString());
        assertEquals(2, info.get("records_found"));
    }

    // T-6 (AC-6)
    @Test
    void unsafeNumbersAreRefusedWithoutAnyLookup() {
        for (String bad : new String[] {"9999999999&To=1", "../x", "%26"}) {
            Map<String, Object> out = service.getSmsCallbacks(bad);
            assertTrue(String.valueOf(out.get("status_data")).contains("must be a phone number"), bad + " -> " + out);
        }
        verifyNoInteractions(repo);
        assertTrue(service.gets.isEmpty(), service.gets.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unsafeStoredSidsAndUnknownSidsAreNotReturned() {
        when(repo.findTop20ByUserIdAndToNumberOrderByIdDesc(anyString(), anyString()))
                .thenReturn(List.of(row("../x"), row("SMgone"), row("SM1")));
        service.answers.put(SMS + "SMgone.json", "No matching results");
        service.answers.put(SMS + "SM1.json", sms("SM1", "delivered"));
        Map<String, Object> out = service.getSmsCallbacks("9999999999");
        assertEquals(List.of(SMS + "SMgone.json", SMS + "SM1.json"), service.gets);
        List<Map<String, Object>> data = (List<Map<String, Object>>) out.get("status_data");
        assertEquals(List.of("SM1"), data.stream().map(m -> m.get("Sid")).toList());
        Map<String, Object> info = (Map<String, Object>) out.get("search_info");
        assertEquals(2, info.get("not_found"));
    }

    // T-7 (AC-7)
    @Test
    void exotelErrorIsReportedAsAnError() {
        when(repo.findTop20ByUserIdAndToNumberOrderByIdDesc(anyString(), anyString())).thenReturn(List.of(row("SM1")));
        Map<String, Object> out = service.getSmsCallbacks("9999999999");
        assertTrue(String.valueOf(out.get("status_data")).startsWith("Not found because"), out.toString());
    }

    // T-8 (AC-8)
    @Test
    void existingRowsAreKept() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(f).toLowerCase();
                assertFalse(text.contains("drop table if exists sms_callbacks"), f.toString());
                assertFalse(text.contains("smscallbackrepository.delete"), f.toString());
            }
        }
        verify(repo, never()).deleteAll();
        verify(repo, never()).delete(any());
    }
}
