package io.conddo.studio.web.dto;

import io.conddo.studio.domain.DesignStandard;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Wire shape for the admin Design Standard Library endpoints. */
public record DesignStandardDto(UUID id, String vertical, String kind, String name, String description,
                                Map<String, Object> content, boolean active,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static DesignStandardDto from(DesignStandard standard) {
        return new DesignStandardDto(standard.getId(), standard.getVertical(), standard.getKind(),
                standard.getName(), standard.getDescription(), standard.getContent(),
                standard.isActive(), standard.getCreatedAt(), standard.getUpdatedAt());
    }
}
