      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ502                                      *
      *  構成       : SQLコンテナ構文の検証コーパス                *
      *  対象       : COBOL格納プロシージャ。連絡節のパラメーター  *
      *               と標識パラメーター、WITH RETURN TO CLIENT を *
      *               付けたカーソルの宣言、結果集合を返すために   *
      *               OPENのままCLOSEしないカーソル、GOBACK。      *
      *  用途       : 誤検出計測用（defect無し。ただしOPENのまま   *
      *               のカーソルはR019の既知の誤検出）             *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ502.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD501 END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-KOKYAKU-NO                PIC X(8).
           EXEC SQL END DECLARE SECTION END-EXEC.

           EXEC SQL
               DECLARE CSR-KOZA CURSOR WITH RETURN TO CLIENT FOR
                   SELECT KOZA_NO, KOZA_MEI, ZANDAKA
                     FROM CSDB.CSQKOZA
                    WHERE KOKYAKU_NO = :WS-KOKYAKU-NO
                    ORDER BY KOZA_NO
                    FOR FETCH ONLY
           END-EXEC.

       LINKAGE SECTION.
           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  LK-KOKYAKU-NO                PIC X(8).
       01  LK-KENSU                     PIC S9(9) COMP.
       01  LK-KOKYAKU-NO-IND            PIC S9(4) COMP.
       01  LK-KENSU-IND                 PIC S9(4) COMP.
           EXEC SQL END DECLARE SECTION END-EXEC.

       PROCEDURE DIVISION USING LK-KOKYAKU-NO LK-KENSU
               LK-KOKYAKU-NO-IND LK-KENSU-IND.
       0000-MAIN.
           IF LK-KOKYAKU-NO-IND < 0
               MOVE -1 TO LK-KENSU-IND
               MOVE ZERO TO LK-KENSU
               GOBACK
           END-IF
           MOVE ZERO TO LK-KENSU-IND
           MOVE LK-KOKYAKU-NO TO WS-KOKYAKU-NO
           EXEC SQL
               SELECT COUNT(*)
                 INTO :LK-KENSU
                 FROM CSDB.CSQKOZA
                WHERE KOKYAKU_NO = :WS-KOKYAKU-NO
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE -1 TO LK-KENSU-IND
               MOVE ZERO TO LK-KENSU
               GOBACK
           END-IF
           EXEC SQL
               OPEN CSR-KOZA
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE -1 TO LK-KENSU-IND
               MOVE ZERO TO LK-KENSU
           END-IF
           GOBACK.
