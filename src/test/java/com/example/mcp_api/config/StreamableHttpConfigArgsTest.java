package com.example.mcp_api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamableHttpConfigArgsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void objectForStringParameterBecomesJsonText() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("instruction", "Be brief");
        data.put("agents", List.of(Map.of("name", "main")));
        assertEquals("{\"instruction\":\"Be brief\",\"agents\":[{\"name\":\"main\"}]}",
            StreamableHttpConfig.asStringArg(data, mapper));
    }

    @Test
    void arrayForStringParameterBecomesJsonText() throws Exception {
        assertEquals("[{\"id\":1}]", StreamableHttpConfig.asStringArg(List.of(Map.of("id", 1)), mapper));
    }

    @Test
    void scalarsForStringParameterBecomeTheirText() throws Exception {
        assertEquals("false", StreamableHttpConfig.asStringArg(Boolean.FALSE, mapper));
        assertEquals("20", StreamableHttpConfig.asStringArg(20, mapper));
        assertEquals("2.5", StreamableHttpConfig.asStringArg(2.5, mapper));
    }
}
