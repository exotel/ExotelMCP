package com.example.mcp_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import com.example.mcp_api.auth.AuthFilter;

@SpringBootApplication
public class McpApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(McpApiApplication.class, args);
	}

	@Bean
	public FilterRegistrationBean<AuthFilter> authFilterRegistration(AuthFilter authFilter) {
		FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>();
		registration.setFilter(authFilter);
		registration.addUrlPatterns("/mcp", "/mcp/*");
		registration.setOrder(1);
		return registration;
	}

	@Bean
	public WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerCustomizer() {
		return container -> {
			container.addConnectorCustomizers(connector -> {
				connector.setProperty("maxHttpResponseHeaderSize", "65536");
				connector.setProperty("maxSwallowSize", "10485760");
				connector.setProperty("maxHttpPostSize", "10485760");
				connector.setProperty("connectionTimeout", "30000");
			});
		};
	}
}
