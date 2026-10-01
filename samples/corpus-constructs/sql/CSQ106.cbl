      *----------------------------------------------------------------
      * CSQ106 -- constructs exercised:
      *   a multi-row FETCH NEXT ROWSET ... FOR 5 ROWS INTO host
      *   arrays with their own indicator arrays, backed by OCCURS 5
      *   host variables; a multi-row INSERT ... FOR :WS-INS-N ROWS
      *   sourced from OCCURS host arrays.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ106.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-MEISAI-NO                PIC X(06).
       01  WS-ARR-AREA.
           05  WS-ARR-GYOBAN           PIC S9(4) COMP
                                        OCCURS 5 TIMES.
       01  WS-ARR-SURYO-AREA.
           05  WS-ARR-SURYO            PIC S9(4) COMP
                                        OCCURS 5 TIMES.
       01  WS-ARR-SURYO-IND-AREA.
           05  WS-ARR-SURYO-IND        PIC S9(4) COMP
                                        OCCURS 5 TIMES.
       01  WS-ARR-BIKO-AREA.
           05  WS-ARR-BIKO             PIC X(20)
                                        OCCURS 5 TIMES.
       01  WS-ARR-BIKO-IND-AREA.
           05  WS-ARR-BIKO-IND         PIC S9(4) COMP
                                        OCCURS 5 TIMES.
       01  WS-INS-AREA.
           05  WS-INS-GYOBAN           PIC S9(4) COMP
                                        OCCURS 5 TIMES.
       01  WS-INS-SURYO-AREA.
           05  WS-INS-SURYO            PIC S9(4) COMP
                                        OCCURS 5 TIMES.
       01  WS-INS-BIKO-AREA.
           05  WS-INS-BIKO             PIC X(20)
                                        OCCURS 5 TIMES.
       01  WS-INS-BIKO-IND-AREA.
           05  WS-INS-BIKO-IND         PIC S9(4) COMP
                                        OCCURS 5 TIMES.
       01  WS-INS-N                    PIC S9(4) COMP.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-IX                       PIC S9(04) COMP.
       01  WS-ROWSET-N                 PIC S9(04) COMP VALUE ZERO.
       01  WS-MEISAI-EOF               PIC X(01) VALUE 'N'.
           88  MEISAI-EOF                  VALUE 'Y'.
       01  WS-GOKEI-SURYO               PIC S9(07) COMP-3 VALUE ZERO.

           EXEC SQL
               DECLARE CSR-MEISAI CURSOR WITH ROWSET POSITIONING FOR
                   SELECT GYOBAN, SURYO, BIKO
                     FROM CSQDB.MEISAI
                    WHERE MEISAI_NO = :WS-MEISAI-NO
                    ORDER BY GYOBAN
                    FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '000001' TO WS-MEISAI-NO
           PERFORM 1000-ROWSET-FETCH
           PERFORM 2000-MULTIROW-INSERT
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ106 COMMIT異常 SQLCODE=' SQLCODE
           END-IF
           STOP RUN.

       1000-ROWSET-FETCH.
           EXEC SQL OPEN CSR-MEISAI END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ106 OPEN異常 SQLCODE=' SQLCODE
               MOVE 'Y' TO WS-MEISAI-EOF
           END-IF
           PERFORM 1100-FETCH-ROWSET UNTIL MEISAI-EOF
           EXEC SQL CLOSE CSR-MEISAI END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ106 CLOSE異常 SQLCODE=' SQLCODE
           END-IF.

       1100-FETCH-ROWSET.
      *    the first NEXT ROWSET on a freshly opened cursor lands on
      *    the first rowset by itself, so no FIRST ROWSET fetch is
      *    needed (CSR-MEISAI is not declared SCROLL, and FIRST ROWSET
      *    is valid only on a SCROLL cursor)
           EXEC SQL
               FETCH NEXT ROWSET FROM CSR-MEISAI FOR 5 ROWS
                INTO :WS-ARR-GYOBAN,
                     :WS-ARR-SURYO :WS-ARR-SURYO-IND,
                     :WS-ARR-BIKO :WS-ARR-BIKO-IND
           END-EXEC
           MOVE SQLERRD(3) TO WS-ROWSET-N
           EVALUATE SQLCODE
               WHEN 0
                   PERFORM 1200-SUM-ROWSET
               WHEN +100
      *            a short final rowset still holds SQLERRD(3) rows
                   IF WS-ROWSET-N > 0
                       PERFORM 1200-SUM-ROWSET
                   END-IF
                   MOVE 'Y' TO WS-MEISAI-EOF
               WHEN OTHER
                   DISPLAY 'CSQ106 FETCH異常 SQLCODE=' SQLCODE
                   MOVE 'Y' TO WS-MEISAI-EOF
           END-EVALUATE.

      *    WS-ROWSET-N (SQLERRD(3) from the last FETCH) bounds the
      *    loop to the rows actually returned, including a short
      *    final rowset
       1200-SUM-ROWSET.
           PERFORM VARYING WS-IX FROM 1 BY 1 UNTIL WS-IX > WS-ROWSET-N
               IF WS-ARR-SURYO-IND(WS-IX) >= ZERO
                   ADD WS-ARR-SURYO(WS-IX) TO WS-GOKEI-SURYO
               END-IF
           END-PERFORM.

       2000-MULTIROW-INSERT.
           MOVE 3    TO WS-INS-N
           MOVE 10   TO WS-INS-GYOBAN(1)
           MOVE 20   TO WS-INS-GYOBAN(2)
           MOVE 30   TO WS-INS-GYOBAN(3)
           MOVE 5    TO WS-INS-SURYO(1)
           MOVE 8    TO WS-INS-SURYO(2)
           MOVE 2    TO WS-INS-SURYO(3)
           MOVE '追加分1' TO WS-INS-BIKO(1)
           MOVE 0    TO WS-INS-BIKO-IND(1)
           MOVE SPACE TO WS-INS-BIKO(2)
           MOVE -1   TO WS-INS-BIKO-IND(2)
           MOVE '追加分3' TO WS-INS-BIKO(3)
           MOVE 0    TO WS-INS-BIKO-IND(3)
           EXEC SQL
               INSERT INTO CSQDB.MEISAI
                      (MEISAI_NO, GYOBAN, SURYO, BIKO)
               VALUES (:WS-MEISAI-NO, :WS-INS-GYOBAN,
                       :WS-INS-SURYO,
                       :WS-INS-BIKO :WS-INS-BIKO-IND)
               FOR :WS-INS-N ROWS
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ106 INSERT異常 SQLCODE=' SQLCODE
           END-IF.
