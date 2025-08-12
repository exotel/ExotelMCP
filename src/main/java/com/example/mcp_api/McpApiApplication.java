package com.example.mcp_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;

import com.example.mcp_api.service.ExotelService;
import com.example.mcp_api.service.AudioPlayerService;
import com.example.mcp_api.service.ClientAudioService;
import com.example.mcp_api.service.QuickAudioService;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.example.mcp_api.config.McpAuthInterceptor;
import java.util.List;
import java.util.ArrayList;

@SpringBootApplication
public class McpApiApplication implements WebMvcConfigurer {

	public static void main(String[] args) {
		SpringApplication.run(McpApiApplication.class, args);
	}
	
	@Override
	public void addInterceptors(@org.springframework.lang.NonNull InterceptorRegistry registry) {
		registry.addInterceptor(mcpAuthInterceptor())
				.addPathPatterns("/sse", "/mcp/**");
	}
	
	@Bean
	public McpAuthInterceptor mcpAuthInterceptor() {
		return new McpAuthInterceptor();
	}
	
	@Bean
	public List<ToolCallback> tools(ExotelService exotelService, AudioPlayerService audioPlayerService, ClientAudioService clientAudioService, QuickAudioService quickAudioService) {
		List<ToolCallback> tools = new ArrayList<>();

		tools.addAll(List.of(ToolCallbacks.from(exotelService)));
		// tools.addAll(List.of(ToolCallbacks.from(audioPlayerService))); // Server-side audio
		// tools.addAll(List.of(ToolCallbacks.from(clientAudioService))); // Client-side audio
		tools.addAll(List.of(ToolCallbacks.from(quickAudioService))); // Quick one-click audio
		return tools;
	}
	
	@Bean
	public WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerCustomizer() {
		return container -> {
			// Configure Tomcat for better HTTP handling (adapted from SSE config)
			container.addConnectorCustomizers(connector -> {
				connector.setProperty("maxHttpResponseHeaderSize", "65536");
				connector.setProperty("maxSwallowSize", "10485760"); // 10MB
				connector.setProperty("maxHttpPostSize", "10485760"); // 10MB
				connector.setProperty("connectionTimeout", "30000"); // 30 seconds
			});
		};
	}

}
