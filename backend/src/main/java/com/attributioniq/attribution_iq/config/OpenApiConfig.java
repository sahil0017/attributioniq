package com.attributioniq.attribution_iq.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI attributionIqOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AttributionIQ API")
                        .version("1.0.0")
                        .description("""
                                First-party ingestion and marketing attribution API.
                                Ingestion requests are accepted asynchronously; reports may lag while
                                recomputation jobs are pending. Monetary reporting is in INR.
                                """));
    }
}
