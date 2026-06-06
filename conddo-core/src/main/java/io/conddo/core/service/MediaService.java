package io.conddo.core.service;

import io.conddo.core.common.NotFoundException;
import io.conddo.core.domain.MediaAsset;
import io.conddo.core.repository.MediaAssetRepository;
import io.conddo.core.storage.ObjectStorage;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tenant media library (§11.12 + SOCIAL_AND_CREATIVE_SERVICES_SPEC §4):
 * upload/list/get/delete over {@link ObjectStorage} (Cloudinary) plus a
 * tenant-scoped index ({@link MediaAsset}). Every method binds the tenant
 * first so RLS scopes the index — uploads land under the tenant, and one
 * tenant can never list or delete another's files.
 *
 * <p>Phase 2a adds: video uploads (image/* + video/* + application/pdf),
 * width/height tracking for the social composer, the {@code uploaded_by}
 * audit field, plan-tier storage caps via the {@code media_storage_mb}
 * plan feature, and the {@link #usage()} read for the FE's usage bar.
 */
@Service
public class MediaService {

    private static final long DEFAULT_MAX_BYTES = 25L * 1024 * 1024;   // 25 MB per file
    private static final long UNLIMITED = -1L;

    private final MediaAssetRepository repository;
    private final ObjectStorage storage;
    private final TenantSession tenantSession;
    private final BillingService billingService;
    private final long maxBytes;

    public MediaService(MediaAssetRepository repository, ObjectStorage storage, TenantSession tenantSession,
                        BillingService billingService,
                        @Value("${conddo.media.max-bytes:" + DEFAULT_MAX_BYTES + "}") long maxBytes) {
        this.repository = repository;
        this.storage = storage;
        this.tenantSession = tenantSession;
        this.billingService = billingService;
        this.maxBytes = maxBytes;
    }

    /** Legacy upload path — no dimensions, no uploadedBy. Kept for back-compat. */
    @Transactional
    public MediaView upload(String originalName, String contentType, long size, InputStream data, String kind) {
        return upload(originalName, contentType, size, data, kind, null, null, null);
    }

    /**
     * Stores an uploaded file under the current tenant and indexes it. Enforces
     * the plan-tier storage cap before persisting — if the tenant is already at
     * cap, throws {@link MediaStorageCapException} so the controller can return
     * a 413 with an explicit code.
     */
    @Transactional
    public MediaView upload(String originalName, String contentType, long size, InputStream data,
                            String kind, Integer width, Integer height, UUID uploadedBy) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        validate(contentType, size);
        enforceStorageCap(tenantId, size);

        ObjectStorage.Stored stored = storage.put(keyFor(tenantId), contentType, size, data);
        MediaAsset asset = repository.save(new MediaAsset(
                tenantId, stored.id(), stored.url(), contentType, size, originalName,
                normalizeKind(kind, contentType), width, height, uploadedBy));
        return view(asset);
    }

    @Transactional(readOnly = true)
    public Page<MediaView> list(String kind, Pageable pageable) {
        tenantSession.bind();
        Page<MediaAsset> page = (kind == null || kind.isBlank())
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByKindOrderByCreatedAtDesc(kind.trim().toLowerCase(), pageable);
        return page.map(this::view);
    }

    @Transactional(readOnly = true)
    public MediaView get(UUID id) {
        tenantSession.bind();
        return view(require(id));
    }

    @Transactional
    public void delete(UUID id) {
        tenantSession.bind();
        MediaAsset asset = require(id);
        storage.delete(asset.getStorageKey());
        repository.delete(asset);
    }

    /**
     * Current storage usage for the bound tenant — drives the FE's usage bar.
     * {@code capBytes} is {@code -1} for the unlimited (Scaler) tier.
     */
    @Transactional(readOnly = true)
    public Usage usage() {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();
        long usedBytes = repository.sumSizeBytes();
        long capBytes = capBytesFor(tenantId);
        return new Usage(usedBytes, capBytes);
    }

    // ----- internals ----------------------------------------------------------

    private MediaView view(MediaAsset a) {
        return new MediaView(a.getId(), a.getUrl(), a.getContentType(), a.getSizeBytes(),
                a.getOriginalName(), a.getKind(), a.getWidth(), a.getHeight(),
                a.getUploadedBy(), a.getCreatedAt());
    }

    private MediaAsset require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Media not found"));
    }

    private void validate(String contentType, long size) {
        if (size <= 0) {
            throw new IllegalArgumentException("File is empty");
        }
        if (size > maxBytes) {
            throw new IllegalArgumentException("File exceeds the " + (maxBytes / (1024 * 1024)) + "MB limit");
        }
        if (contentType == null || !(contentType.startsWith("image/")
                || contentType.startsWith("video/")
                || contentType.equals("application/pdf"))) {
            throw new IllegalArgumentException("Unsupported file type: " + contentType
                    + " (allowed: images, videos, PDF)");
        }
    }

    private void enforceStorageCap(UUID tenantId, long incomingBytes) {
        long capBytes = capBytesFor(tenantId);
        if (capBytes == UNLIMITED) {
            return;
        }
        long used = repository.sumSizeBytes();
        if (used + incomingBytes > capBytes) {
            throw new MediaStorageCapException(
                    "Upload would exceed the " + (capBytes / (1024 * 1024))
                            + "MB media-storage cap on your plan.");
        }
    }

    /** Resolve the tenant's {@code media_storage_mb} feature value into bytes. */
    private long capBytesFor(UUID tenantId) {
        int mb = billingService.featureLimit(tenantId, "media_storage_mb");
        if (mb == Integer.MAX_VALUE) {
            return UNLIMITED;
        }
        if (mb <= 0) {
            return 0L;
        }
        return (long) mb * 1024L * 1024L;
    }

    /** A per-tenant, collision-free key (used as the Cloudinary public_id base). */
    private static String keyFor(UUID tenantId) {
        return "tenants/" + tenantId + "/" + UUID.randomUUID();
    }

    /** Default {@code kind} from the contentType when the caller didn't supply one. */
    private static String normalizeKind(String kind, String contentType) {
        if (kind != null && !kind.isBlank()) {
            return kind.trim().toLowerCase();
        }
        if (contentType == null) {
            return "other";
        }
        if (contentType.startsWith("video/")) {
            return "video";
        }
        if (contentType.startsWith("image/")) {
            return "image";
        }
        return "other";
    }

    // ----- records + exceptions ----------------------------------------------

    /** A media asset plus its public URL ({@code size} in bytes). Phase-2a adds dimensions + uploadedBy. */
    public record MediaView(UUID id, String url, String contentType, long size,
                            String originalName, String kind, Integer width, Integer height,
                            UUID uploadedBy, OffsetDateTime createdAt) {
    }

    /** Usage snapshot. {@code capBytes = -1} means the plan has no cap (Scaler tier). */
    public record Usage(long usedBytes, long capBytes) {
        public boolean unlimited() {
            return capBytes < 0;
        }
    }

    public static class MediaStorageCapException extends RuntimeException {
        public MediaStorageCapException(String msg) {
            super(msg);
        }
    }
}
