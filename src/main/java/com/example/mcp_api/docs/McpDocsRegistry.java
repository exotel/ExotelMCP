package com.example.mcp_api.docs;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * VoiceBot / Tools Server guide pages, loaded once from classpath:mcp-docs/**.md.
 * Each page starts with frontmatter (topic, title, description, tools) and is served three ways:
 * the exotel_voicebot_guide tool, MCP resources (exotel://voicebot/{topic}), and "Guide:" pointers in tool descriptions.
 */
@Component
public class McpDocsRegistry {

    private static final Logger logger = LoggerFactory.getLogger(McpDocsRegistry.class);
    public static final String URI_PREFIX = "exotel://voicebot/";
    static final Pattern TOPIC_PATTERN = Pattern.compile("^[a-z0-9-]+(/[a-z0-9-]+)*$");
    private static final String LOCATION = "classpath*:mcp-docs/**/*.md";
    /** Pages listed first in the index and never used as a tool's "Guide:" pointer when a more specific page exists. */
    private static final List<String> GENERAL_TOPICS = List.of("start", "tools", "safe-changes", "workflows", "errors");

    public record Doc(String topic, String title, String description, List<String> tools, String body) {
        public String uri() { return URI_PREFIX + topic; }
        public String markdown() { return "# " + title + "\n\n" + body.strip() + "\n"; }
    }

    private final Map<String, Doc> docs = new LinkedHashMap<>();

    @PostConstruct
    void load() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources(LOCATION);
        List<Doc> loaded = new ArrayList<>();
        for (Resource file : files) {
            try (InputStream in = file.getInputStream()) {
                loaded.add(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), file.getFilename()));
            }
        }
        loaded.sort(Comparator.comparingInt((Doc d) -> {
            int i = GENERAL_TOPICS.indexOf(d.topic());
            return i < 0 ? GENERAL_TOPICS.size() : i;
        }).thenComparing(Doc::topic));
        for (Doc doc : loaded) {
            if (docs.putIfAbsent(doc.topic(), doc) != null) {
                throw new IllegalStateException("Duplicate guide topic: " + doc.topic());
            }
        }
        logger.info("Loaded {} guide pages", docs.size());
    }

    /** Parses one page; throws if the frontmatter is missing or malformed so a bad page fails startup and tests. */
    static Doc parse(String text, String source) {
        String t = text.replace("\r\n", "\n");
        if (!t.startsWith("---\n")) throw new IllegalStateException(source + ": missing frontmatter");
        int end = t.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalStateException(source + ": unterminated frontmatter");
        Map<String, String> fm = new HashMap<>();
        for (String line : t.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) fm.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String topic = fm.getOrDefault("topic", "");
        if (!TOPIC_PATTERN.matcher(topic).matches()) throw new IllegalStateException(source + ": bad topic '" + topic + "'");
        String title = fm.getOrDefault("title", "");
        String description = fm.getOrDefault("description", "");
        if (title.isBlank() || description.isBlank()) throw new IllegalStateException(source + ": title and description are required");
        List<String> tools = new ArrayList<>();
        for (String tool : fm.getOrDefault("tools", "").split(",")) {
            if (!tool.isBlank()) tools.add(tool.trim());
        }
        return new Doc(topic, title, description, List.copyOf(tools), t.substring(end + 5));
    }

    public Collection<Doc> all() {
        return Collections.unmodifiableCollection(docs.values());
    }

    /** Accepts a topic ("objects/bot"), a full URI ("exotel://voicebot/objects/bot") or a topic with ".md". */
    public Optional<Doc> find(String topicOrUri) {
        if (topicOrUri == null) return Optional.empty();
        String key = topicOrUri.trim();
        if (key.startsWith(URI_PREFIX)) key = key.substring(URI_PREFIX.length());
        if (key.endsWith(".md")) key = key.substring(0, key.length() - 3);
        key = key.replaceAll("^/+|/+$", "").toLowerCase(Locale.ROOT);
        return Optional.ofNullable(docs.get(key));
    }

    /** The most specific page that lists this tool in its frontmatter, or null. */
    public String topicForTool(String toolName) {
        String general = null;
        for (Doc doc : docs.values()) {
            if (!doc.tools().contains(toolName)) continue;
            if (!GENERAL_TOPICS.contains(doc.topic())) return doc.topic();
            if (general == null) general = doc.topic();
        }
        return general;
    }

    /** Markdown list of every page: what exotel_voicebot_guide returns for an empty or unknown topic. */
    public String index() {
        StringBuilder sb = new StringBuilder("# VoiceBot guide index\n\n")
                .append("Read a page with exotel_voicebot_guide(topic) or the resource ").append(URI_PREFIX).append("{topic}.\n\n")
                .append("| topic | what it answers |\n|---|---|\n");
        for (Doc doc : docs.values()) {
            sb.append("| ").append(doc.topic()).append(" | ").append(doc.description().replace("|", "/")).append(" |\n");
        }
        return sb.toString();
    }
}
