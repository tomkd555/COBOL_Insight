      *----------------------------------------------------------------
      * CSQ101 -- constructs exercised:
      *   DECLARE CURSOR in WORKING-STORAGE; END-EXEC with a period;
      *   END-EXEC with no period inside IF ... ELSE ... END-IF;
      *   indicator forms :HV:IND, :HV :IND and :HV INDICATOR :IND
      *   on SELECT INTO and FETCH INTO.
      *----------------------------------------------------------------
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ101.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-KOKYAKU-NO              PIC X(08).
       01  WS-KOKYAKU-NO-IND          PIC S9(04) COMP.
       01  WS-KOKYAKU-MEI             PIC X(30).
       01  WS-KOKYAKU-MEI-IND         PIC S9(04) COMP.
       01  WS-DENWA-BANGO             PIC X(13).
       01  WS-DENWA-BANGO-IND         PIC S9(04) COMP.
       01  WS-FROM-YMD                PIC X(08).
       01  WS-TOROKU-YMD              PIC X(08).
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-MODE                    PIC X(01).
       01  WS-ERR-MSG                 PIC X(40).
       01  WS-COUNTERS.
           05  WS-READ-CNT            PIC 9(05) VALUE ZERO.

           EXEC SQL
               DECLARE CSR-KOKYAKU CURSOR FOR
                   SELECT KOKYAKU_NO, KOKYAKU_MEI, DENWA_BANGO
                     FROM CSQDB.KOKYAKU
                    WHERE TOROKU_YMD >= :WS-FROM-YMD
                    FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE '20260101' TO WS-FROM-YMD
           PERFORM 1000-SELECT-DEMO
           PERFORM 2000-KOKYAKU-LOOP
           PERFORM 3000-MODE-UPDATE
           EXEC SQL COMMIT END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ101 COMMIT異常 SQLCODE=' SQLCODE
           END-IF
           STOP RUN.

       1000-SELECT-DEMO.
      *    indicator form 1: host var and indicator run together;
      *    indicator form 3 (INDICATOR keyword) alongside it on the
      *    same statement
           MOVE '00000001' TO WS-KOKYAKU-NO
           EXEC SQL
               SELECT KOKYAKU_MEI, DENWA_BANGO
                 INTO :WS-KOKYAKU-MEI INDICATOR :WS-KOKYAKU-MEI-IND,
                      :WS-DENWA-BANGO:WS-DENWA-BANGO-IND
                 FROM CSQDB.KOKYAKU
                WHERE KOKYAKU_NO = :WS-KOKYAKU-NO
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ101 SELECT異常(1) SQLCODE=' SQLCODE
           END-IF
      *    indicator form 2: host var, a space, then the indicator
           MOVE '00000002' TO WS-KOKYAKU-NO
           EXEC SQL
               SELECT KOKYAKU_MEI, DENWA_BANGO
                 INTO :WS-KOKYAKU-MEI,
                      :WS-DENWA-BANGO :WS-DENWA-BANGO-IND
                 FROM CSQDB.KOKYAKU
                WHERE KOKYAKU_NO = :WS-KOKYAKU-NO
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ101 SELECT異常(2) SQLCODE=' SQLCODE
           END-IF.

       2000-KOKYAKU-LOOP.
           EXEC SQL OPEN CSR-KOKYAKU END-EXEC
           IF SQLCODE NOT = 0
               MOVE '顧客カーソルの開始に失敗しました' TO WS-ERR-MSG
               DISPLAY 'CSQ101 OPEN異常 SQLCODE=' SQLCODE
           END-IF
           PERFORM 2100-FETCH-LOOP UNTIL SQLCODE NOT = 0
           EXEC SQL CLOSE CSR-KOKYAKU END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ101 CLOSE異常 SQLCODE=' SQLCODE
           END-IF.

       2100-FETCH-LOOP.
      *    all three indicator forms on one FETCH INTO: form 2 (host
      *    var, a space, the indicator) on KOKYAKU_NO, form 1 (host
      *    var and indicator run together) on KOKYAKU_MEI, form 3
      *    (the INDICATOR keyword) on DENWA_BANGO
           EXEC SQL
               FETCH CSR-KOKYAKU
                INTO :WS-KOKYAKU-NO :WS-KOKYAKU-NO-IND,
                     :WS-KOKYAKU-MEI:WS-KOKYAKU-MEI-IND,
                     :WS-DENWA-BANGO INDICATOR :WS-DENWA-BANGO-IND
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   ADD 1 TO WS-READ-CNT
               WHEN +100
                   CONTINUE
               WHEN OTHER
                   DISPLAY 'CSQ101 FETCH異常 SQLCODE=' SQLCODE
           END-EVALUATE.

       3000-MODE-UPDATE.
           MOVE '00000001' TO WS-KOKYAKU-NO
           MOVE 'A' TO WS-MODE
           IF WS-MODE = 'A'
               EXEC SQL
                   UPDATE CSQDB.KOKYAKU
                      SET TOROKU_YMD = :WS-FROM-YMD
                    WHERE KOKYAKU_NO = :WS-KOKYAKU-NO
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'UPDATE異常(A)' TO WS-ERR-MSG
                   DISPLAY 'CSQ101 ' WS-ERR-MSG ' SQLCODE=' SQLCODE
               END-IF
           ELSE
               MOVE '20260101' TO WS-TOROKU-YMD
               EXEC SQL
                   UPDATE CSQDB.KOKYAKU
                      SET TOROKU_YMD = :WS-TOROKU-YMD
                    WHERE KOKYAKU_NO = :WS-KOKYAKU-NO
               END-EXEC
               IF SQLCODE NOT = 0
                   MOVE 'UPDATE異常(B)' TO WS-ERR-MSG
                   DISPLAY 'CSQ101 ' WS-ERR-MSG ' SQLCODE=' SQLCODE
               END-IF
           END-IF.
