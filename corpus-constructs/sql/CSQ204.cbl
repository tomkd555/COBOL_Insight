      *----------------------------------------------------------------*
      * PROGRAM-ID : CSQ204
      * Constructs : PROCEDURE DIVISION USING, a program shaped as a
      *   Db2 stored procedure; DECLARE CURSOR WITH RETURN TO CALLER,
      *   opened and left open at GOBACK so Db2 returns the result
      *   set to the caller (the correct idiom for this clause is to
      *   NOT close the cursor before returning).
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.    CSQ204.
       AUTHOR.        CSQ-CONSTRUCTS-DEV.

       ENVIRONMENT DIVISION.
       CONFIGURATION SECTION.
       SOURCE-COMPUTER. IBM-370.
       OBJECT-COMPUTER. IBM-370.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD201 END-EXEC.

       LINKAGE SECTION.
       01  LK-TORIATSUKAI-KBN         PIC X(01).
       01  LK-RETURN-SQLCODE          PIC S9(9) COMP.

       PROCEDURE DIVISION USING LK-TORIATSUKAI-KBN, LK-RETURN-SQLCODE.

       0000-MAIN.
           EXEC SQL
               DECLARE CSR-RS CURSOR WITH RETURN TO CALLER FOR
                   SELECT SHOHIN_CD, SHOHIN_MEI, TANKA
                     FROM CSQDB.SHOHIN
                    WHERE TORIATSUKAI_KBN = :LK-TORIATSUKAI-KBN
                    ORDER BY SHOHIN_CD
                    FOR READ ONLY
           END-EXEC
           EXEC SQL OPEN CSR-RS END-EXEC
           MOVE SQLCODE TO LK-RETURN-SQLCODE
           GOBACK.
