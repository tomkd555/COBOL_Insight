------------------------------------------------------------------------
-- CSL501.sql -- constructs exercised:
--   CREATE TABLE with a GENERATED ALWAYS AS IDENTITY column and a
--   NOT NULL WITH DEFAULT column; CREATE INDEX; CREATE VIEW;
--   CREATE SEQUENCE; CREATE OR REPLACE PROCEDURE ... LANGUAGE SQL
--   with BEGIN / DECLARE / IF ... END IF / END; CREATE TRIGGER
--   AFTER UPDATE ... REFERENCING ... FOR EACH ROW with BEGIN ATOMIC;
--   CREATE FUNCTION ... RETURNS DECIMAL(9,2) LANGUAGE SQL RETURN;
--   GRANT SELECT ... TO PUBLIC; COMMENT ON TABLE.
-- Every statement ends with a semicolon (the CREATE TRIGGER and CREATE
-- PROCEDURE bodies close their own compound statement before it).
-- corpus/defect-free style.
------------------------------------------------------------------------

-- 入出金明細（口座番号ごとの入出金履歴）。MEISAI_NOはID列で自動採番
-- し、SHORI_KBNとTOROKU_YMDには既定値を与える。
CREATE TABLE CSDB.CSQ_MEISAI
    ( MEISAI_NO     INTEGER GENERATED ALWAYS AS IDENTITY
                     (START WITH 1, INCREMENT BY 1),
      KOZA_NO       CHAR(10)      NOT NULL,
      SHORI_KBN     CHAR(1)       NOT NULL WITH DEFAULT '1',
      GAKU          DECIMAL(11, 0) NOT NULL,
      TOROKU_YMD    DATE          NOT NULL WITH DEFAULT CURRENT DATE,
      BIKO          VARCHAR(60),
      PRIMARY KEY (MEISAI_NO)
    );

-- 口座番号・登録日で明細を絞り込む検索を支える索引。
CREATE INDEX CSDB.CSQ_MEISAI_IX1
    ON CSDB.CSQ_MEISAI (KOZA_NO, TOROKU_YMD);

-- 口座マスタ（CSD501.cpyがDCLGENする表と同じ列構成）。
CREATE TABLE CSDB.CSQKOZA
    ( KOZA_NO       CHAR(10)       NOT NULL,
      KOKYAKU_NO    CHAR(8)        NOT NULL,
      ZANDAKA       DECIMAL(11, 0) NOT NULL,
      KOZA_MEI      VARCHAR(30)    NOT NULL,
      BIKO          VARCHAR(60),
      KOSHIN_YMD    CHAR(8),
      PRIMARY KEY (KOZA_NO)
    );

-- 口座マスタの残高だけを見せる参照ビュー。
CREATE VIEW CSDB.CSQ_V_ZANDAKA AS
    SELECT KOZA_NO, KOKYAKU_NO, ZANDAKA
      FROM CSDB.CSQKOZA;

-- 明細番号を払い出す採番専用の順序オブジェクト。
CREATE SEQUENCE CSDB.CSQ_SEQ
    AS INTEGER
    START WITH 1
    INCREMENT BY 1
    NO MAXVALUE
    NO CYCLE
    CACHE 20;

-- 残高更新の監査ログ。トリガーの挿入先。
CREATE TABLE CSDB.CSQ_AUDIT
    ( AUDIT_NO      INTEGER GENERATED ALWAYS AS IDENTITY,
      KOZA_NO       CHAR(10)  NOT NULL,
      ZANDAKA_OLD   DECIMAL(11, 0),
      ZANDAKA_NEW   DECIMAL(11, 0),
      KOSHIN_TS     TIMESTAMP NOT NULL
                     WITH DEFAULT CURRENT TIMESTAMP,
      PRIMARY KEY (AUDIT_NO)
    );

-- 残高が更新されるたびに、更新前後の値を監査ログへ記録するトリガー。
CREATE TRIGGER CSDB.CSQ_TRG_ZANKOU
    AFTER UPDATE OF ZANDAKA ON CSDB.CSQKOZA
    REFERENCING OLD AS O NEW AS N
    FOR EACH ROW MODE DB2SQL
    BEGIN ATOMIC
        INSERT INTO CSDB.CSQ_AUDIT
               (KOZA_NO, ZANDAKA_OLD, ZANDAKA_NEW)
        VALUES (N.KOZA_NO, O.ZANDAKA, N.ZANDAKA);
    END;

-- 元金と利率から利息額を計算するSQLスカラー関数。
CREATE FUNCTION CSDB.CSQ_RISOKU
    (P_GANKIN DECIMAL(11, 0), P_RITSU DECIMAL(5, 4))
    RETURNS DECIMAL(9, 2)
    LANGUAGE SQL
    DETERMINISTIC
    NO EXTERNAL ACTION
    CONTAINS SQL
    RETURN DECIMAL(P_GANKIN * P_RITSU, 9, 2);

-- 指定口座の残高を増減させるネイティブSQL PLプロシージャ。該当口座が
-- ないときはP_SQLCODEに100を返し、更新は行わない。
CREATE OR REPLACE PROCEDURE CSDB.CSQ_UPD_ZANDAKA
    (IN P_KOZA_NO CHAR(10),
     IN P_GAKU DECIMAL(11, 0),
     OUT P_SQLCODE INTEGER)
    LANGUAGE SQL
    MODIFIES SQL DATA
    BEGIN
        DECLARE V_ZANDAKA DECIMAL(11, 0);
        DECLARE V_KENSU   INTEGER DEFAULT 0;

        SELECT ZANDAKA, 1
          INTO V_ZANDAKA, V_KENSU
          FROM CSDB.CSQKOZA
         WHERE KOZA_NO = P_KOZA_NO;

        IF V_KENSU = 0 THEN
            SET P_SQLCODE = 100;
        ELSE
            UPDATE CSDB.CSQKOZA
               SET ZANDAKA = V_ZANDAKA + P_GAKU,
                   KOSHIN_YMD = REPLACE(CHAR(CURRENT DATE, ISO), '-', '')
             WHERE KOZA_NO = P_KOZA_NO;
            SET P_SQLCODE = 0;
        END IF;
    END;

-- 残高参照ビューを全利用者に公開する。
GRANT SELECT ON TABLE CSDB.CSQ_V_ZANDAKA TO PUBLIC;

COMMENT ON TABLE CSDB.CSQ_MEISAI
    IS '入出金明細（口座番号ごとの入出金履歴）';
