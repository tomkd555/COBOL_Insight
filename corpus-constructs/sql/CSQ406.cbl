      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ406                                      *
      *  構成       : SQL問い合わせ構文の検証コーパス             *
      *  対象       : CAST、COALESCE、VALUE、NULLIF、             *
      *               ラベル付き期間による日時演算、              *
      *               DAYS()・DATE()・TIMESTAMP()、               *
      *               文字列関数群（CHAR・DECIMAL・DIGITS・       *
      *               SUBSTR・STRIP・TRIM・LEFT・RIGHT・          *
      *               LOCATE・POSSTR・REPLACE・TRANSLATE・        *
      *               UPPER・LENGTH・HEX）                        *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ406.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL INCLUDE CSD402 END-EXEC.

       01  WS-INPUT-VARS.
           05  WS-JUCHU-NO-IN          PIC X(10) VALUE 'J000000001'.
           05  WS-SHOHIN-CD-IN         PIC X(8)  VALUE 'S0000001'.
           05  WS-KINGAKU-IN           PIC S9(7)V99 USAGE COMP-3.

       01  WS-CAST-VARS.
           05  WS-KINGAKU-CAST         PIC S9(7)V9(2) USAGE COMP-3.
           05  WS-SURYO-INT            PIC S9(9) COMP.
           05  WS-TANTO-COALESCE       PIC X(5).
           05  WS-TANTO-VALUE          PIC X(5).
           05  WS-SURYO-NULLIF         PIC S9(9) COMP.
           05  WS-SURYO-NULLIF-IND     PIC S9(4) COMP.
           05  WS-DATE-MINUS30         PIC X(10).
           05  WS-JUCHU-YMD-PLUS1M     PIC X(10).
           05  WS-DAY-DIFF             PIC S9(9) COMP.
           05  WS-JUCHU-DATE           PIC X(10).
           05  WS-JUCHU-TS             PIC X(26).

       01  WS-STRING-VARS.
           05  WS-CHAR-TANKA           PIC X(20).
           05  WS-DEC-LITERAL          PIC S9(7)V9(2) USAGE COMP-3.
           05  WS-DIGITS-TANKA         PIC X(9).
           05  WS-SUBSTR-MEI           PIC X(10).
           05  WS-STRIP-MEI            PIC X(40).
           05  WS-TRIM-MEI             PIC X(40).
           05  WS-LEFT-MEI             PIC X(10).
           05  WS-RIGHT-MEI            PIC X(10).
           05  WS-LOCATE-POS           PIC S9(9) COMP.
           05  WS-POSSTR-POS           PIC S9(9) COMP.
           05  WS-REPLACE-MEI          PIC X(40).
           05  WS-TRANSLATE-MEI        PIC X(40).
           05  WS-UPPER-MEI            PIC X(40).
           05  WS-LENGTH-MEI           PIC S9(9) COMP.
           05  WS-HEX-CD               PIC X(16).

       01  WS-ERR-MSG                  PIC X(60) VALUE SPACE.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE 1000.00 TO WS-KINGAKU-IN
           PERFORM 1000-CAST-COALESCE
           PERFORM 2000-STRING-FUNCTIONS
           STOP RUN.

      *    CAST、COALESCE、VALUE、NULLIF、ラベル付き期間による日時
      *    演算、DAYS()・DATE()・TIMESTAMP()を一度に列挙する。
       1000-CAST-COALESCE.
           EXEC SQL
               SELECT CAST(:WS-KINGAKU-IN AS DECIMAL(9,2)),
                      CAST(SURYO AS INTEGER),
                      COALESCE(TANTO_CD, 'T9999'),
                      VALUE(TANTO_CD, 'T9999'),
                      NULLIF(SURYO, 0),
                      CURRENT DATE - 30 DAYS,
                      JUCHU_YMD + 1 MONTH,
                      DAYS(CURRENT DATE) - DAYS(JUCHU_YMD),
                      DATE(JUCHU_YMD),
                      TIMESTAMP(JUCHU_YMD, '00.00.00')
                 INTO :WS-KINGAKU-CAST, :WS-SURYO-INT,
                      :WS-TANTO-COALESCE, :WS-TANTO-VALUE,
                      :WS-SURYO-NULLIF :WS-SURYO-NULLIF-IND,
                      :WS-DATE-MINUS30, :WS-JUCHU-YMD-PLUS1M,
                      :WS-DAY-DIFF, :WS-JUCHU-DATE, :WS-JUCHU-TS
                 FROM FLDB.JUCHU
                WHERE JUCHU_NO = :WS-JUCHU-NO-IN
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   DISPLAY 'CSQ406 CAST/日時演算 取得完了'
               WHEN +100
                   DISPLAY 'CSQ406 該当する受注がありません'
               WHEN OTHER
                   MOVE 'CAST/日時演算の実行に失敗しました'
                        TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

      *    文字列関数群（CHAR・DECIMAL・DIGITS・SUBSTR・STRIP・
      *    TRIM・LEFT・RIGHT・LOCATE・POSSTR・REPLACE・
      *    TRANSLATE・UPPER・LENGTH・HEX）を一度に列挙する。
       2000-STRING-FUNCTIONS.
           EXEC SQL
               SELECT CHAR(TANKA),
                      DECIMAL('1234.50', 9, 2),
                      DIGITS(TANKA),
                      SUBSTR(SHOHIN_MEI, 1, 10),
                      STRIP(SHOHIN_MEI),
                      TRIM(SHOHIN_MEI),
                      LEFT(SHOHIN_MEI, 10),
                      RIGHT(SHOHIN_MEI, 10),
                      LOCATE('用', SHOHIN_MEI),
                      POSSTR(SHOHIN_MEI, '用'),
                      REPLACE(SHOHIN_MEI, '用', ' '),
                      TRANSLATE(SHOHIN_MEI),
                      UPPER(SHOHIN_MEI),
                      LENGTH(SHOHIN_MEI),
                      HEX(SHOHIN_CD)
                 INTO :WS-CHAR-TANKA, :WS-DEC-LITERAL,
                      :WS-DIGITS-TANKA, :WS-SUBSTR-MEI,
                      :WS-STRIP-MEI, :WS-TRIM-MEI, :WS-LEFT-MEI,
                      :WS-RIGHT-MEI, :WS-LOCATE-POS,
                      :WS-POSSTR-POS, :WS-REPLACE-MEI,
                      :WS-TRANSLATE-MEI, :WS-UPPER-MEI,
                      :WS-LENGTH-MEI, :WS-HEX-CD
                 FROM FLDB.SHOHIN
                WHERE SHOHIN_CD = :WS-SHOHIN-CD-IN
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   DISPLAY 'CSQ406 文字列関数 取得完了'
               WHEN +100
                   DISPLAY 'CSQ406 該当する商品がありません'
               WHEN OTHER
                   MOVE '文字列関数の実行に失敗しました' TO WS-ERR-MSG
                   PERFORM 9900-SQL-ERROR
           END-EVALUATE.

       9900-SQL-ERROR.
           DISPLAY 'CSQ406 SQL ERROR SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           MOVE 12 TO RETURN-CODE
           GOBACK.
