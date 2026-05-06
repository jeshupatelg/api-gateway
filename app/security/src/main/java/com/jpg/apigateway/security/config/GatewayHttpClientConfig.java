package com.jpg.apigateway.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.server.firewall.ServerWebExchangeFirewall;
import org.springframework.security.web.server.firewall.StrictServerWebExchangeFirewall;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.List;

@Configuration
public class GatewayHttpClientConfig {

    @Value("${api-gateway.exchange.logging.enabled:false}")
    private boolean loggingEnabled;

    // Defaulting to empty if the property is not provided
    @Value("${api-gateway.firewall.relaxed-patterns:}")
    private List<String> relaxedPatterns;

    @Bean
    public HttpClient customHttpClient() {
        HttpClient client = HttpClient.create();
        if(loggingEnabled) {
            // Fix: Reassign the client because Reactor Netty's HttpClient is immutable
            client = client.doOnRequest((req, conn) -> {
                System.out.println("[APIGW-FORWARD] -> " + req.method().name() + " " + req.uri());
                req.requestHeaders().forEach(h ->
                        System.out.println("  " + h.getKey() + ": " + h.getValue()));
            }).doOnResponse((res, conn) -> {
                System.out.println("[APIGW-FORWARD-RESP] <- " + res.status());
                res.responseHeaders().forEach(h ->
                        System.out.println("  " + h.getKey() + ": " + h.getValue()));
            });
        }
        return client;
    }

    @Bean
    public ServerWebExchangeFirewall serverWebExchangeFirewall() {
        // 1. Create a strict firewall for normal traffic
        StrictServerWebExchangeFirewall strictFirewall = new StrictServerWebExchangeFirewall();

        // 2. Create a relaxed firewall for specific traffic
        StrictServerWebExchangeFirewall relaxedFirewall = new StrictServerWebExchangeFirewall();
        relaxedFirewall.setAllowUrlEncodedDoubleSlash(true);
        relaxedFirewall.setAllowUrlEncodedSlash(true);
        relaxedFirewall.setAllowUrlEncodedPercent(true);

        // 3. Return a delegating firewall that chooses between them
        return new ServerWebExchangeFirewall() {
            @Override
            public Mono<ServerWebExchange> getFirewalledExchange(ServerWebExchange exchange) {
                String rawPath = exchange.getRequest().getURI().getRawPath();

                // If the path starts with our target pattern, use the relaxed rules
                // Otherwise, enforce strict security rules
                boolean useRelaxed = rawPath != null && 
                                     relaxedPatterns != null && 
                                     relaxedPatterns.stream().filter(p -> !p.isEmpty()).anyMatch(rawPath::startsWith);

                return useRelaxed ? relaxedFirewall.getFirewalledExchange(exchange) 
                                  : strictFirewall.getFirewalledExchange(exchange);
            }
        };
    }

}