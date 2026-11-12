package com.samwan.kibetipay.search.internal;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OpenSearch connection and naming.
 *
 * @param enabled     false = no search beans at all (tests, environments without a cluster)
 * @param url         cluster endpoint, e.g. http://localhost:9200
 * @param username    optional basic-auth user (a secured cluster); blank = no auth
 * @param password    optional basic-auth password
 * @param indexPrefix every alias and index starts with this, so several apps or tests can share a cluster
 */
@Validated
@ConfigurationProperties(prefix = "app.search")
public record SearchProperties(
        boolean enabled,
        @NotBlank String url,
        String username,
        String password,
        @NotBlank String indexPrefix) {}
