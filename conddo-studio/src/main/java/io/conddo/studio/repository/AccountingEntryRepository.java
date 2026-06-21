package io.conddo.studio.repository;

import io.conddo.studio.domain.AccountingEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AccountingEntryRepository extends JpaRepository<AccountingEntry, Long> {

    List<AccountingEntry> findByEntryDateBetweenOrderByEntryDateAsc(LocalDate start, LocalDate end);

    List<AccountingEntry> findByCategoryOrderByIdDesc(AccountingEntry.Category category);

    List<AccountingEntry> findByTypeOrderByIdDesc(AccountingEntry.EntryType type);

    List<AccountingEntry> findByStatusOrderByIdDesc(String status);

    @Query("SELECT ae FROM AccountingEntry ae WHERE ae.entryDate >= :start ORDER BY ae.entryDate DESC")
    List<AccountingEntry> findRecentEntries(LocalDate start);

    @Query("SELECT SUM(ae.amount) FROM AccountingEntry ae WHERE ae.type = :type AND ae.entryDate BETWEEN :start AND :end")
    java.math.BigDecimal sumByTypeAndDateRange(AccountingEntry.EntryType type, LocalDate start, LocalDate end);

    @Query("SELECT SUM(ae.amount) FROM AccountingEntry ae WHERE ae.category = :category AND ae.entryDate BETWEEN :start AND :end")
    java.math.BigDecimal sumByCategoryAndDateRange(AccountingEntry.Category category, LocalDate start, LocalDate end);
}
