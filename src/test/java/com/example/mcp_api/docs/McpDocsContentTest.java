package com.example.mcp_api.docs;

import com.example.mcp_api.service.VoiceBotAdminTools;
import com.example.mcp_api.service.VoiceBotBuilderTools;
import com.example.mcp_api.service.VoiceBotGuideTools;
import com.example.mcp_api.service.VoiceBotService;
import com.example.mcp_api.service.VoiceBotToolsService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guide pages ship in a public repository. These checks fail the build when a page names an internal host, an
 * account, a ticket, a real object id or an internal component, or refers to a tool that does not exist.
 */
class McpDocsContentTest {

    private static final Path DOCS = Path.of("src/main/resources/mcp-docs");

    private static final Map<String, Pattern> FORBIDDEN = new LinkedHashMap<>();
    static {
        FORBIDDEN.put("non-production stamp", Pattern.compile("(?i)\\buat\\d|\\bpreprod\\b|\\bpre-prod\\b"));
        FORBIDDEN.put("private IP", Pattern.compile("\\b(10|172|192)\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"));
        FORBIDDEN.put("ticket key", Pattern.compile("\\b(?!ISO-|RFC-)[A-Z]{2,6}-\\d{2,6}\\b"));
        FORBIDDEN.put("wiki or tracker link", Pattern.compile("(?i)/wiki/|/browse/|/confluence/"));
        FORBIDDEN.put("secret material", Pattern.compile("-----BEGIN|AIza[0-9A-Za-z_-]{10}|AKIA[0-9A-Z]{12}|ghp_[0-9A-Za-z]|github_pat_"));
        FORBIDDEN.put("angle-bracket placeholder", Pattern.compile("<[A-Za-z_/!?][^>\\n]{0,80}>"));
    }

    /** Any domain name. Only public Exotel API hosts and example domains may appear in a page. */
    private static final Pattern HOST = Pattern.compile("(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|in|net|org|io|ai|co|cloud)\\b");
    private static final Set<String> ALLOWED_HOSTS = Set.of("voicebot.in.exotel.com", "api.in.exotel.com", "developer.exotel.com");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern TOOL_REF = Pattern.compile("\\bexotel_(voicebot|tools_server)_[a-z_]+[a-z]\\b");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /**
     * SHA-256 of lower-case words that must never appear in a page: internal account names, customer names,
     * internal component and domain names. Stored hashed so this public test does not publish the list itself.
     * A page is checked word by word and by adjacent word pairs joined without a space.
     */
    private static final Set<String> PRIVATE_WORD_HASHES = Set.of(
            "13ed00fdc909b37b86539905b8da44cb7a4e93f874514ab82ea3e7718bb2d422",
            "8bc8cdb2825d8c13b1a63ac79e7763faaeefada2b79e89565e64d44feca5fd0e",
            "6fa37e0c65194a4a72b1481924185cbb2693b8bbe35e7ee5757c8eb3883c9b67",
            "eb505db28e1787c44099b46472d7d9046ba717ff6429c622c79334cbcaeaa846",
            "e8dae8541462a4f122df8a15c0901d0ca84665cfab72331080d7fc0fa6d47cc7",
            "d97c565face3314ddb298972d241ccbd7e794c7beeaa5fface02085124f7d489",
            "39f1b8989a0394e097f7081fbac47af0a7d537abf50406d3b21bb3eb81f6dd40",
            "5144eee98881491b9ef49cab0ffb313e28cbdfcc519e962e7de918657f83ce3b",
            "9c788aebab84ddf9d740e2717bb8433812ab0ae2f5648f33d4bdbfa9907490f3",
            "444b759c5264422ea582403ae2083d2447fd226a2e40795968dd740e9202cb97",
            "d5f6a22d14757778f04d5e3c8a92754e51909d78c9530630850541e4a4b7ae78",
            "b14a6bb205e7b7ac7ea0b175cce38d60498112a51b4995046194dea144b8ab07",
            "d63f4a0cdaab52726d1ce895e4ac8c3507049e617b0a97d0a0af508bdad34a8d",
            "d959b638663f9a7de663a1be37e39bc5d820e3dbb3a31b0af1bad43e0c39fc69",
            "d1030cf6a090d4b2a5c6dd7ca462d910eac5c4c7298528fbffb25f8a231338cc",
            "3c51d7e084c4b3325327c23504344ca74708a5632ac1e3264829a4f26608bd77",
            "112c682c880489f5aa2e5c7322641d5668623091e6dbc3c3a19107a073a54957"
    );

    private static List<Path> pages() throws IOException {
        if (!Files.isDirectory(DOCS)) return List.of();
        try (Stream<Path> s = Files.walk(DOCS)) {
            return s.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    private static Set<String> toolNames() {
        Set<String> names = new HashSet<>();
        for (Class<?> c : List.of(VoiceBotService.class, VoiceBotAdminTools.class, VoiceBotBuilderTools.class,
                VoiceBotToolsService.class, VoiceBotGuideTools.class)) {
            for (Method m : c.getDeclaredMethods()) {
                Tool t = m.getAnnotation(Tool.class);
                if (t != null) names.add(t.name());
            }
        }
        return names;
    }

    @Test
    void pagesContainNothingThatMustNotBePublic() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path page : pages()) {
            String text = Files.readString(page, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                for (Map.Entry<String, Pattern> rule : FORBIDDEN.entrySet()) {
                    Matcher m = rule.getValue().matcher(line);
                    if (m.find()) problems.add(page + ":" + (i + 1) + " " + rule.getKey() + ": " + m.group());
                }
                Matcher host = HOST.matcher(line);
                while (host.find()) {
                    String h = host.group().toLowerCase(Locale.ROOT);
                    boolean example = h.equals("example.com") || h.endsWith(".example.com") || h.equals("example.org")
                            || h.endsWith(".example.org") || h.equals("example.net") || h.endsWith(".example.net");
                    if (!example && !ALLOWED_HOSTS.contains(h)) problems.add(page + ":" + (i + 1) + " host not allowed: " + host.group());
                }
                String privateWord = privateWord(line);
                if (privateWord != null) problems.add(page + ":" + (i + 1) + " private name (word starting '" + privateWord.charAt(0) + "')");
                Matcher uuid = UUID.matcher(line);
                while (uuid.find()) {
                    if (!uuid.group().startsWith("00000000-0000-"))
                        problems.add(page + ":" + (i + 1) + " real-looking id: " + uuid.group());
                }
            }
        }
        assertTrue(problems.isEmpty(), "Guide pages must stay public-safe:\n" + String.join("\n", problems));
    }

    @Test
    void pagesParseAndReferOnlyToRealTools() throws IOException {
        Set<String> tools = toolNames();
        Set<String> topics = new HashSet<>();
        List<String> problems = new ArrayList<>();
        for (Path page : pages()) {
            String text = Files.readString(page, StandardCharsets.UTF_8);
            McpDocsRegistry.Doc doc = McpDocsRegistry.parse(text, page.toString());
            String expected = DOCS.relativize(page).toString().replace('\\', '/').replaceAll("\\.md$", "");
            if (!doc.topic().equals(expected)) problems.add(page + ": topic " + doc.topic() + " should be " + expected);
            if (!topics.add(doc.topic())) problems.add(page + ": duplicate topic " + doc.topic());
            if (text.length() > 12_000) problems.add(page + ": " + text.length() + " characters; keep pages under 12,000");
            for (String t : doc.tools()) if (!tools.contains(t)) problems.add(page + ": frontmatter names unknown tool " + t);
            Matcher ref = TOOL_REF.matcher(text);
            while (ref.find()) {
                if (!tools.contains(ref.group())) problems.add(page + ": refers to unknown tool " + ref.group());
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void parseRejectsMissingFrontmatter() {
        assertThrows(IllegalStateException.class, () -> McpDocsRegistry.parse("# no frontmatter", "x.md"));
        assertThrows(IllegalStateException.class, () -> McpDocsRegistry.parse("---\ntopic: Bad Topic\ntitle: t\ndescription: d\n---\nbody", "x.md"));
        McpDocsRegistry.Doc d = McpDocsRegistry.parse("---\ntopic: objects/bot\ntitle: T\ndescription: D\ntools: a, b\n---\nbody", "x.md");
        assertEquals(List.of("a", "b"), d.tools());
        assertEquals("exotel://voicebot/objects/bot", d.uri());
    }

    /** The first word (or adjacent pair joined) whose SHA-256 is on the private list, or null. */
    static String privateWord(String line) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(line.toLowerCase(Locale.ROOT));
        while (m.find()) words.add(m.group());
        for (int i = 0; i < words.size(); i++) {
            if (PRIVATE_WORD_HASHES.contains(sha256(words.get(i)))) return words.get(i);
            if (i + 1 < words.size() && PRIVATE_WORD_HASHES.contains(sha256(words.get(i) + words.get(i + 1)))) {
                return words.get(i) + words.get(i + 1);
            }
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void privateWordCheckWorksOnHashes() {
        assertNull(privateWord("Create a bot with exotel_voicebot_bot_create and read it back."));
        assertNotNull(privateWord("the " + "mum" + "1 region"));
    }
}
