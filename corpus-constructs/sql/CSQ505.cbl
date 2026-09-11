      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ505                                      *
      *  構成       : SQLコンテナ構文の検証コーパス                *
      *  対象       : EXEC SQLとEXEC CICSを併用する疑似会話。      *
      *               WITH HOLDを付けたカーソルをEXEC CICS         *
      *               SYNCPOINTをまたいで開いたまま保持し、        *
      *               EXEC CICS RETURNの前に必ずCLOSEする。        *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ505.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           COPY DFHBMSCA.
           COPY CSM501.

           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-KOZA-NO                   PIC X(10).
       01  WS-ZANDAKA                   PIC S9(11) COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-RESP                      PIC S9(8) COMP.
       01  WS-ERR-FLAG                  PIC X(1) VALUE 'N'.
           88  WS-ERR                       VALUE 'Y'.
       01  WS-GYO                       PIC S9(4) COMP VALUE ZERO.
       01  WS-ZANDAKA-HYOJI             PIC -(11)9.

       01  WS-MEI-ALL.
           05  WS-MEI-LINE OCCURS 10 TIMES  PIC X(40).

       01  WS-COMMAREA.
           05  WS-CA-STATUS             PIC X(1).

           EXEC SQL
               DECLARE CSR-BROWSE CURSOR WITH HOLD FOR
                   SELECT KOZA_NO, ZANDAKA
                     FROM CSDB.CSQKOZA
                    WHERE KOZA_NO >= :WS-KOZA-NO
                    ORDER BY KOZA_NO
                    FOR FETCH ONLY
           END-EXEC.

       PROCEDURE DIVISION.
       0000-MAIN.
           IF EIBCALEN = 0
               PERFORM 1000-SHOKAI
           ELSE
               PERFORM 2000-UKETSUKE
           END-IF.

       1000-SHOKAI.
           MOVE LOW-VALUES TO CSM01O
           MOVE DFHBMBRY TO MSGA
           MOVE '検索開始の口座番号を入力してください。' TO MSGO
           EXEC CICS
               SEND MAP('CSM01') MAPSET('CSM501')
                   FROM(CSM01O) ERASE
                   RESP(WS-RESP)
           END-EXEC
           IF WS-RESP NOT = DFHRESP(NORMAL)
               DISPLAY 'CSQ505 SEND MAPエラー RESP=' WS-RESP
           END-IF
           MOVE SPACES TO WS-COMMAREA
           EXEC CICS
               RETURN TRANSID('CS55')
                   COMMAREA(WS-COMMAREA)
                   LENGTH(LENGTH OF WS-COMMAREA)
           END-EXEC.

       2000-UKETSUKE.
           EXEC CICS
               HANDLE CONDITION MAPFAIL(8100-MAPFAIL)
           END-EXEC
           EXEC CICS
               RECEIVE MAP('CSM01') MAPSET('CSM501')
                   INTO(CSM01I)
           END-EXEC
           MOVE KOZANOI TO WS-KOZA-NO
           PERFORM 3000-BROWSE
           IF WS-ERR
               DISPLAY 'CSQ505 検索処理でエラーが発生しました。'
           END-IF
           EXEC CICS
               RETURN
           END-EXEC.

       3000-BROWSE.
           EXEC SQL
               OPEN CSR-BROWSE
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'Y' TO WS-ERR-FLAG
           ELSE
               MOVE SPACES TO WS-MEI-ALL
               MOVE 1 TO WS-GYO
               PERFORM 3100-FETCH-1KEN
                   UNTIL SQLCODE NOT = 0 OR WS-GYO > 10
               EXEC CICS
                   SYNCPOINT
                       RESP(WS-RESP)
               END-EXEC
               IF WS-RESP NOT = DFHRESP(NORMAL)
                   DISPLAY 'CSQ505 SYNCPOINTエラー RESP=' WS-RESP
               END-IF
               EXEC SQL
                   CLOSE CSR-BROWSE
               END-EXEC
               IF SQLCODE NOT = 0
                   DISPLAY 'CSQ505 カーソルCLOSEエラー SQLCODE='
                           SQLCODE
               END-IF
               EXEC CICS
                   SEND TEXT FROM(WS-MEI-ALL) LENGTH(400) ERASE
                       RESP(WS-RESP)
               END-EXEC
               IF WS-RESP NOT = DFHRESP(NORMAL)
                   DISPLAY 'CSQ505 SEND TEXTエラー RESP=' WS-RESP
               END-IF
           END-IF.

       3100-FETCH-1KEN.
           EXEC SQL
               FETCH CSR-BROWSE
                    INTO :WS-KOZA-NO, :WS-ZANDAKA
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   MOVE WS-ZANDAKA TO WS-ZANDAKA-HYOJI
                   STRING WS-KOZA-NO ' ' WS-ZANDAKA-HYOJI
                       DELIMITED BY SIZE INTO WS-MEI-LINE(WS-GYO)
                       ON OVERFLOW
                           DISPLAY 'CSQ505 明細行の編集で桁あふれが'
                                   '発生しました。'
                   END-STRING
                   ADD 1 TO WS-GYO
               WHEN 100
                   CONTINUE
               WHEN OTHER
                   MOVE 'Y' TO WS-ERR-FLAG
                   DISPLAY 'CSQ505 カーソルFETCHエラー SQLCODE='
                           SQLCODE
           END-EVALUATE.

       8100-MAPFAIL.
           MOVE 'Y' TO WS-ERR-FLAG
           DISPLAY 'CSQ505 RECEIVE MAPエラー MAPFAIL'
           EXEC CICS
               RETURN
           END-EXEC.
