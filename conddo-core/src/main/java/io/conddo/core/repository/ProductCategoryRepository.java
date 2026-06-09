package io.conddo.core.repository;

import io.conddo.core.domain.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {

    List<ProductCategory> findAllByOrderByName();

    Optional<ProductCategory> findByName(String name);

    /** Case-insensitive name lookup for rename-conflict checks (HANDOFF_2026-06-09 §2.1). */
    Optional<ProductCategory> findByNameIgnoreCase(String name);

    Optional<ProductCategory> findBySlug(String slug);
}
