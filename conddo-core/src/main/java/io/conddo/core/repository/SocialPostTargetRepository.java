package io.conddo.core.repository;

import io.conddo.core.domain.SocialPostTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** RLS-scoped. Webhook reconcile uses {@link #findByPostIdAndProvider}. */
public interface SocialPostTargetRepository extends JpaRepository<SocialPostTarget, UUID> {

    List<SocialPostTarget> findByPostId(UUID postId);

    Optional<SocialPostTarget> findByPostIdAndProvider(UUID postId, String provider);
}
