------------------------------------------------------------------------
-- CCPTAB.sql -- constructs exercised:
--   CREATE TABLE backing the Db2 shared stubs corpus-constructs/cobol/
--   CCP011.cbl through CCP020.cbl. CCP_VAL is NOT NULL, so the SELECT
--   INTO host variable in those stubs needs no indicator variable, and
--   its PICTURE (S9(09) COMP-3) matches DECIMAL(9, 0) here.
-- Every statement ends with a semicolon; corpus/defect-free style.
------------------------------------------------------------------------

-- CCP011〜CCP020が1件検索する参照テーブル。
CREATE TABLE CCPDB.CCPTAB
    ( CCP_KEY       CHAR(8)       NOT NULL,
      CCP_VAL       DECIMAL(9, 0) NOT NULL,
      PRIMARY KEY (CCP_KEY)
    );
