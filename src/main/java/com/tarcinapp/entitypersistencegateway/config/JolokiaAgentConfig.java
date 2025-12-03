package com.tarcinapp.entitypersistencegateway.config;

import org.jolokia.jvmagent.JolokiaServer;
import org.jolokia.jvmagent.JolokiaServerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
@ConditionalOnProperty(name = "app.jolokia.enabled", havingValue = "true", matchIfMissing = false)
public class JolokiaAgentConfig {

    @Value("${app.jolokia.host:0.0.0.0}")
    private String host;

    @Value("${app.jolokia.port:8778}")
    private String port;

    @Value("${app.jolokia.discoveryEnabled:true}")
    private String discoveryEnabled;

    private JolokiaServer server;

    @Bean
    public JolokiaServer startJolokiaServer() {
        try {
            Map<String, String> config = new HashMap<>();
            config.put("host", host);
            config.put("port", port);
            
            // config.put("restrictorClass", "org.jolokia.jvmagent.security.PolicyRestrictor");
            // config.put("policyLocation", "classpath:/jolokia-access.xml");

            // for hawtio compatibility
            config.put("discoveryEnabled", discoveryEnabled);

            JolokiaServerConfig serverConfig = new JolokiaServerConfig(config);
            
            // Use the 2-argument constructor (config, lazy) to ensure compatibility.
            // 'false' means we don't want lazy initialization; we want it ready immediately.
            server = new JolokiaServer(serverConfig, false);
            server.start();

            log.info("Jolokia JMX Agent started on {}:{}", host, port);
            return server;
        } catch (Exception e) {
            log.error("Failed to start Jolokia Agent", e);
            return null;
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}