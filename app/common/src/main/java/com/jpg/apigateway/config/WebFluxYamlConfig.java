package com.jpg.apigateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Configures Spring WebFlux HTTP codecs to support YAML media types ('text/yaml', 'application/x-yaml')
 * for seamless POJO serialization and deserialization in REST endpoints.
 */
@Configuration
public class WebFluxYamlConfig implements WebFluxConfigurer {

    private static final MediaType TEXT_YAML = new MediaType("text", "yaml");
    private static final MediaType APP_YAML = new MediaType("application", "x-yaml");

    /**
     * Registers Jackson YAML decoder and encoder with WebFlux server codecs.
     *
     * @param configurer server codec configurer
     */
    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        configurer.customCodecs().registerWithDefaultConfig(new Jackson2JsonDecoder(yamlMapper, TEXT_YAML, APP_YAML));
        configurer.customCodecs().registerWithDefaultConfig(new Jackson2JsonEncoder(yamlMapper, TEXT_YAML, APP_YAML));
    }
}
