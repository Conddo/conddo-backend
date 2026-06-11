package io.conddo.core.service;

import io.conddo.core.domain.Product;
import io.conddo.core.domain.StockMovement;
import io.conddo.core.repository.ProductRepository;
import io.conddo.core.tenant.TenantContext;
import io.conddo.core.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk stock upload (Pharmacy Spec v2 — supplemental). Lets a tenant
 * upload a CSV that becomes the new ground-truth for their inventory:
 * existing SKUs have their stock set absolute (logged as an ADJUSTMENT
 * via {@link StockMovementService#setAbsolute}), new SKUs create a
 * Product + RESTOCK movement.
 *
 * <p>Dry-run mode parses, validates, and returns the same summary
 * shape without persisting anything — the FE can preview the outcome
 * before the pharmacist commits.
 *
 * <p>Required columns: {@code sku}, {@code stock}. Optional:
 * {@code name} (required when creating), {@code price},
 * {@code reorder_threshold}, {@code batch_number}, {@code expiry_date}
 * (ISO yyyy-MM-dd). Header row is required; column order is
 * flexible.
 */
@Service
public class BulkStockUploadService {

    private static final Logger log = LoggerFactory.getLogger(BulkStockUploadService.class);

    private static final List<String> REQUIRED_HEADERS = List.of("sku", "stock");

    private final ProductRepository productRepository;
    private final StockMovementService movementService;
    private final TenantSession tenantSession;

    public BulkStockUploadService(ProductRepository productRepository,
                                  StockMovementService movementService,
                                  TenantSession tenantSession) {
        this.productRepository = productRepository;
        this.movementService = movementService;
        this.tenantSession = tenantSession;
    }

    @Transactional
    public Summary upload(InputStream csvBody, boolean dryRun, UUID actingUserId) {
        tenantSession.bind();
        UUID tenantId = TenantContext.require();

        List<Row> rows;
        try {
            rows = parse(csvBody);
        } catch (UploadException ex) {
            // Parse-time errors are file-level — return a summary with
            // a single row-0 error rather than 500-ing the request.
            return failed(ex.getMessage(), dryRun);
        } catch (IOException ex) {
            log.warn("Bulk upload read failed: {}", ex.getMessage());
            return failed("Could not read upload: " + ex.getMessage(), dryRun);
        }

        int created = 0, updated = 0, skipped = 0;
        UUID importBatch = UUID.randomUUID();
        List<RowError> errors = new ArrayList<>();
        List<Map<String, Object>> preview = new ArrayList<>();

        for (Row row : rows) {
            if (row.parseError != null) {
                errors.add(new RowError(row.lineNumber, row.sku, row.parseError));
                skipped++;
                continue;
            }
            try {
                List<Product> matches = productRepository.findBySku(row.sku);
                if (matches.size() > 1) {
                    errors.add(new RowError(row.lineNumber, row.sku,
                            "Multiple existing products share this SKU; resolve manually"));
                    skipped++;
                    continue;
                }
                if (matches.size() == 1) {
                    Product existing = matches.get(0);
                    int before = existing.getStock();
                    if (!dryRun) {
                        applyOptionalFields(existing, row);
                        productRepository.save(existing);
                        movementService.setAbsolute(existing.getId(), row.stock,
                                "BULK_IMPORT", noteFor(row, importBatch), actingUserId);
                    }
                    updated++;
                    preview.add(previewRow("update", row, before));
                } else {
                    if (row.name == null || row.name.isBlank()) {
                        errors.add(new RowError(row.lineNumber, row.sku,
                                "name is required when creating a new SKU"));
                        skipped++;
                        continue;
                    }
                    if (!dryRun) {
                        Product fresh = new Product(tenantId, row.name, row.sku, null,
                                row.price == null ? BigDecimal.ZERO : row.price,
                                0, row.reorderThreshold == null ? 0 : row.reorderThreshold);
                        if (row.batchNumber != null) {
                            fresh.setBatchNumber(row.batchNumber);
                        }
                        if (row.expiryDate != null) {
                            fresh.setExpiryDate(row.expiryDate);
                        }
                        fresh = productRepository.save(fresh);
                        if (row.stock > 0) {
                            movementService.recordMovement(fresh.getId(),
                                    StockMovement.Type.RESTOCK, row.stock,
                                    importBatch, "BULK_IMPORT",
                                    noteFor(row, importBatch), actingUserId);
                        }
                    }
                    created++;
                    preview.add(previewRow("create", row, 0));
                }
            } catch (RuntimeException ex) {
                errors.add(new RowError(row.lineNumber, row.sku, ex.getMessage()));
                skipped++;
            }
        }

        return new Summary(rows.size(), created, updated, skipped, errors, preview, dryRun);
    }

    // ----- parsing -----------------------------------------------------------

    private List<Row> parse(InputStream body) throws IOException {
        List<Row> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new UploadException("File is empty");
            }
            headerLine = stripBom(headerLine);
            List<String> headers = splitCsv(headerLine).stream()
                    .map(h -> h.trim().toLowerCase().replace(' ', '_')).toList();
            for (String required : REQUIRED_HEADERS) {
                if (!headers.contains(required)) {
                    throw new UploadException("Missing required column: " + required);
                }
            }
            Map<String, Integer> columnIndex = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                columnIndex.put(headers.get(i), i);
            }

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = splitCsv(line);
                try {
                    out.add(toRow(cells, columnIndex, lineNumber));
                } catch (RuntimeException ex) {
                    Row bad = new Row();
                    bad.lineNumber = lineNumber;
                    bad.sku = cell(cells, columnIndex, "sku");
                    bad.parseError = ex.getMessage();
                    out.add(bad);
                }
            }
        }
        return out;
    }

    private Row toRow(List<String> cells, Map<String, Integer> idx, int lineNumber) {
        Row row = new Row();
        row.lineNumber = lineNumber;
        row.sku = cell(cells, idx, "sku");
        if (row.sku == null || row.sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        row.sku = row.sku.trim();
        String stockRaw = cell(cells, idx, "stock");
        if (stockRaw == null || stockRaw.isBlank()) {
            throw new IllegalArgumentException("stock is required");
        }
        try {
            row.stock = Integer.parseInt(stockRaw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("stock must be a whole number: '" + stockRaw + "'");
        }
        if (row.stock < 0) {
            throw new IllegalArgumentException("stock must be >= 0");
        }
        row.name = cell(cells, idx, "name");
        if (row.name != null) {
            row.name = row.name.trim();
        }
        String priceRaw = cell(cells, idx, "price");
        if (priceRaw != null && !priceRaw.isBlank()) {
            try {
                row.price = new BigDecimal(priceRaw.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("price must be numeric: '" + priceRaw + "'");
            }
        }
        String thresholdRaw = cell(cells, idx, "reorder_threshold");
        if (thresholdRaw != null && !thresholdRaw.isBlank()) {
            try {
                row.reorderThreshold = Integer.parseInt(thresholdRaw.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "reorder_threshold must be a whole number: '" + thresholdRaw + "'");
            }
        }
        row.batchNumber = cell(cells, idx, "batch_number");
        if (row.batchNumber != null) {
            row.batchNumber = row.batchNumber.trim();
            if (row.batchNumber.isEmpty()) {
                row.batchNumber = null;
            }
        }
        String expiryRaw = cell(cells, idx, "expiry_date");
        if (expiryRaw != null && !expiryRaw.isBlank()) {
            try {
                row.expiryDate = LocalDate.parse(expiryRaw.trim());
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                        "expiry_date must be ISO yyyy-MM-dd: '" + expiryRaw + "'");
            }
        }
        return row;
    }

    private static String cell(List<String> cells, Map<String, Integer> idx, String header) {
        Integer i = idx.get(header);
        if (i == null || i >= cells.size()) {
            return null;
        }
        return cells.get(i);
    }

    /**
     * Minimal RFC-4180 split: handles double-quoted fields with commas
     * inside, and escaped double-quotes ({@code ""}). Anything fancier
     * (multi-line cells, alternate delimiters) is intentionally out of
     * scope for v1.
     */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String stripBom(String s) {
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            return s.substring(1);
        }
        return s;
    }

    private static void applyOptionalFields(Product product, Row row) {
        if (row.name != null && !row.name.isBlank()) {
            product.rename(row.name);
        }
        if (row.price != null) {
            product.setPrice(row.price);
        }
        if (row.reorderThreshold != null) {
            product.setReorderThreshold(row.reorderThreshold);
        }
        if (row.batchNumber != null) {
            product.setBatchNumber(row.batchNumber);
        }
        if (row.expiryDate != null) {
            product.setExpiryDate(row.expiryDate);
        }
    }

    private static String noteFor(Row row, UUID batch) {
        return "Bulk import batch " + batch + " (line " + row.lineNumber + ")";
    }

    private static Map<String, Object> previewRow(String action, Row row, int stockBefore) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("line", row.lineNumber);
        r.put("sku", row.sku);
        r.put("action", action);
        r.put("stockBefore", stockBefore);
        r.put("stockAfter", row.stock);
        return r;
    }

    private static Summary failed(String message, boolean dryRun) {
        return new Summary(0, 0, 0, 0,
                List.of(new RowError(0, null, message)), List.of(), dryRun);
    }

    // ----- DTOs --------------------------------------------------------------

    private static class Row {
        int lineNumber;
        String sku;
        String name;
        BigDecimal price;
        int stock;
        Integer reorderThreshold;
        String batchNumber;
        LocalDate expiryDate;
        String parseError;
    }

    public record RowError(int line, String sku, String message) {
    }

    public record Summary(int totalRows, int created, int updated, int skipped,
                          List<RowError> errors, List<Map<String, Object>> preview,
                          boolean dryRun) {
    }

    private static class UploadException extends RuntimeException {
        UploadException(String message) {
            super(message);
        }
    }
}
