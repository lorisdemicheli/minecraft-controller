package it.lorisdemicheli.minecraft_servers_controller.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import it.lorisdemicheli.minecraft_servers_controller.annotation.Api;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	@Override
	public void configurePathMatch(PathMatchConfigurer configurer) {
		configurer.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(Api.class));
	}
}