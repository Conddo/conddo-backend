-- V57 — Add optimistic-lock column that V56 forgot.
--
-- TenantCreditAccount has @Version private Long version, but V56 didn't
-- create the backing column. Hibernate's schema-validation refuses to
-- boot until the column exists. Default 0 for existing rows; Hibernate
-- increments on every save.
ALTER TABLE tenant_credit_accounts
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
