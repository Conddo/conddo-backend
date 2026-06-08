package io.conddo.core.repository;

import io.conddo.core.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartIdOrderByAddedAt(UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, UUID productId);

    @Modifying
    @Query("delete from CartItem ci where ci.cartId = :cartId")
    void deleteByCartId(@Param("cartId") UUID cartId);

    @Modifying
    @Query("delete from CartItem ci where ci.cartId = :cartId and ci.productId = :productId")
    int deleteByCartIdAndProductId(@Param("cartId") UUID cartId, @Param("productId") UUID productId);
}
