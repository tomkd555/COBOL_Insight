      *----------------------------------------------------------------*
      * CSI201 : EXEC SQL INCLUDE member carrying executable SQL,      *
      *          not only declarations: a DECLARE CURSOR and a         *
      *          SELECT INTO. Pulled in with                           *
      *          EXEC SQL INCLUDE CSI201 END-EXEC from the PROCEDURE   *
      *          DIVISION of the including program. Requires host      *
      *          variables SHOHIN-CD (search key, set by the caller    *
      *          before the INCLUDE runs) and SHOHIN-MEI, TANKA,       *
      *          ZAIKO-SU from the CSD201 DCLGEN.                      *
      *----------------------------------------------------------------*
           EXEC SQL
               DECLARE CSR-UPD CURSOR FOR
                   SELECT SHOHIN_CD, TANKA, ZAIKO_SU
                     FROM CSQDB.SHOHIN
                    WHERE SHOHIN_CD = :SHOHIN-CD
                    FOR UPDATE OF TANKA, ZAIKO_SU
           END-EXEC

           EXEC SQL
               SELECT SHOHIN_MEI
                 INTO :SHOHIN-MEI
                 FROM CSQDB.SHOHIN
                WHERE SHOHIN_CD = :SHOHIN-CD
           END-EXEC.
