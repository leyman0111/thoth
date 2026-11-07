-- =====================================================================
-- Доля 0.00 допустима: гость, которому выставили 0, остаётся на позиции.
-- Инвариант «сумма долей по позиции = 1.00» проверяет BillSplitterService
-- (агрегатный CHECK в PostgreSQL не выразить).
-- =====================================================================

ALTER TABLE item_assignments
    DROP CONSTRAINT chk_assignments_share;

ALTER TABLE item_assignments
    ADD CONSTRAINT chk_assignments_share CHECK (share_ratio >= 0 AND share_ratio <= 1);
