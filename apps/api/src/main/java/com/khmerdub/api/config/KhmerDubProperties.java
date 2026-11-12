package com.khmerdub.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings bound from {@code khmerdub.*}. Values come from application.yml,
 * which reads the repo-root .env file.
 */
@Validated
@ConfigurationProperties(prefix = "khmerdub")
public record KhmerDubProperties(
        @NotBlank String version,
        @NotNull Path storageRoot,
        @NotBlank @Size(min = 32, message = "must be at least 32 characters (set KHMERDUB_INTERNAL_TOKEN in .env)")
        String internalToken,
        boolean cloudEnabled,
        @Min(1) long minFreeDiskGb,
        @NotEmpty List<String> allowedOrigins,
        @NotEmpty List<String> allowedHosts,
        List<String> internalAllowedHosts,
        @Valid @NotNull AiService aiService) {

    public record AiService(@NotNull URI url, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {
    }

    /** Never print the token, even by accident. */
    @Override
    public String toString() {
        return "KhmerDubProperties[version=%s, storageRoot=%s, cloudEnabled=%s, aiService=%s]"
                .formatted(version, storageRoot, cloudEnabled, aiService);
    }
}
