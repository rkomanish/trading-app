package com.niftyautotrader.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Cloud platforms (Railway, Render, Heroku, Fly) expose the database connection as a
 * single {@code DATABASE_URL} in the form {@code postgres://user:pass@host:port/dbname}.
 * Spring/JDBC needs a {@code jdbc:postgresql://host:port/dbname} URL plus a separate
 * username and password.
 *
 * This post-processor runs before the datasource is created: if {@code DATABASE_URL}
 * (or a {@code SPRING_DATASOURCE_URL} that uses the postgres:// scheme) is present, it
 * parses it and registers the equivalent Spring datasource properties. With no such
 * variable set (local dev), it does nothing and the application.yml defaults apply.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String raw = firstNonBlank(
            env.getProperty("DATABASE_URL"),
            env.getProperty("SPRING_DATASOURCE_URL"));
        if (raw == null) return;

        String scheme = raw.contains("://") ? raw.substring(0, raw.indexOf("://")) : "";
        if (!scheme.equals("postgres") && !scheme.equals("postgresql")) {
            return; // already a jdbc: URL or something we shouldn't touch
        }

        try {
            URI uri = URI.create(raw);
            String userInfo = uri.getUserInfo(); // user:pass (may be null)
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();

            String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + path + query;

            Map<String, Object> props = new HashMap<>();
            props.put("spring.datasource.url", jdbcUrl);
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                props.put("spring.datasource.username", parts[0]);
                if (parts.length > 1) props.put("spring.datasource.password", parts[1]);
            }

            env.getPropertySources().addFirst(
                new MapPropertySource("databaseUrlDerived", props));
            // Logging isn't initialised this early — use stdout so it shows in deploy logs.
            System.out.println("[DatabaseUrlEnvironmentPostProcessor] Using JDBC url " + jdbcUrl);
        } catch (Exception e) {
            // Leave the defaults in place rather than failing startup on a malformed URL.
            System.err.println("Could not parse DATABASE_URL: " + e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
