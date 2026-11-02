package com.acme.coderag.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level authentication.
 *
 * <p>This is the inner of two gates. The outer one is Cloud Run IAM
 * ({@code --no-allow-unauthenticated}), which is the stronger control but also the
 * one most easily widened by a hurried {@code gcloud} command. A token checked in
 * the application means widening ingress alone does not expose the corpus.
 *
 * @param enabled   set false only for local development
 * @param readToken bearer token clients must present. Injected from Secret Manager.
 */
@ConfigurationProperties(prefix = "coderag.security")
public record SecurityProperties(boolean enabled, String readToken) {

    /**
     * Fails fast on the dangerous combination: enabled with nothing to check.
     * A service that accepts every request because its token was never wired up
     * looks healthy, which is precisely why it must refuse to start.
     */
    public void validate() {
        if (enabled && (readToken == null || readToken.isBlank())) {
            throw new IllegalStateException(
                    "coderag.security.read-token is required when security is enabled; "
                    + "set it from Secret Manager, or set coderag.security.enabled=false "
                    + "for local development only");
        }
    }
}
