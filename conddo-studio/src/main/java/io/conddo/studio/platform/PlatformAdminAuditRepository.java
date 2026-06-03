package io.conddo.studio.platform;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlatformAdminAuditRepository extends JpaRepository<PlatformAdminAudit, UUID> {
}
