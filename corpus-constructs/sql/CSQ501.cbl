      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ501                                      *
      *  構成       : SQLコンテナ構文の検証コーパス                *
      *  対象       : EXEC CICS RECEIVE MAP、SQLCODEを検査する     *
      *               EXEC SQL SELECT/UPDATE、COMMIT/ROLLBACKの     *
      *               代わりのEXEC CICS SYNCPOINT・SYNCPOINT       *
      *               ROLLBACK、HANDLE CONDITION、各コマンドの     *
      *               RESP、COMMAREAを持つEXEC CICS RETURN         *
      *               TRANSID、IF EIBCALEN = 0による分岐。          *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ501.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           COPY DFHBMSCA.
           COPY CSM501.

           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD501 END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-KOZA-NO                   PIC X(10).
       01  WS-ZANDAKA                   PIC S9(11) COMP-3.
       01  WS-ZANDAKA-SHIN              PIC S9(11) COMP-3.
       01  WS-GAKU                      PIC S9(11) COMP-3.
       01  WS-DATE                      PIC X(8).
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-RESP                      PIC S9(8) COMP.
       01  WS-MSG                       PIC X(60).
       01  WS-ERR-FLAG                  PIC X(1) VALUE 'N'.
           88  WS-ERR                       VALUE 'Y'.

       01  WS-COMMAREA.
           05  WS-CA-STATUS             PIC X(1).

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
           MOVE '口座番号・金額・区分を入力してください。' TO MSGO
           EXEC CICS
               SEND MAP('CSM01') MAPSET('CSM501')
                   FROM(CSM01O) ERASE
                   RESP(WS-RESP)
           END-EXEC
           IF WS-RESP NOT = DFHRESP(NORMAL)
               DISPLAY 'CSQ501 SEND MAPエラー RESP=' WS-RESP
           END-IF
           MOVE SPACES TO WS-COMMAREA
           EXEC CICS
               RETURN TRANSID('CS51')
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
           PERFORM 3000-ZANDAKA-KOSHIN
           IF WS-ERR
               PERFORM 8000-ERROR-HYOJI
           ELSE
               PERFORM 7000-KANRYO-HYOJI
           END-IF
           MOVE 'C' TO WS-CA-STATUS
           EXEC CICS
               RETURN TRANSID('CS51')
                   COMMAREA(WS-COMMAREA)
                   LENGTH(LENGTH OF WS-COMMAREA)
           END-EXEC.

       3000-ZANDAKA-KOSHIN.
           MOVE KOZANOI TO WS-KOZA-NO
           EXEC SQL
               SELECT ZANDAKA
                 INTO :WS-ZANDAKA
                 FROM CSDB.CSQKOZA
                WHERE KOZA_NO = :WS-KOZA-NO
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'Y' TO WS-ERR-FLAG
               MOVE '該当する口座がありません。' TO WS-MSG
           ELSE
               MOVE GAKUI TO WS-GAKU
               IF KUBUNI = 1
                   COMPUTE WS-ZANDAKA-SHIN = WS-ZANDAKA + WS-GAKU
                       ON SIZE ERROR
                           MOVE 'Y' TO WS-ERR-FLAG
                           MOVE '残高がけたあふれしました。' TO WS-MSG
                   END-COMPUTE
               ELSE
                   COMPUTE WS-ZANDAKA-SHIN = WS-ZANDAKA - WS-GAKU
                       ON SIZE ERROR
                           MOVE 'Y' TO WS-ERR-FLAG
                           MOVE '残高がけたあふれしました。' TO WS-MSG
                   END-COMPUTE
               END-IF
               IF NOT WS-ERR
                   MOVE FUNCTION CURRENT-DATE(1:8) TO WS-DATE
                   EXEC SQL
                       UPDATE CSDB.CSQKOZA
                          SET ZANDAKA = :WS-ZANDAKA-SHIN,
                              KOSHIN_YMD = :WS-DATE
                        WHERE KOZA_NO = :WS-KOZA-NO
                   END-EXEC
                   IF SQLCODE NOT = 0
                       MOVE 'Y' TO WS-ERR-FLAG
                       MOVE '残高更新に失敗しました。' TO WS-MSG
                   END-IF
               END-IF
           END-IF.

       7000-KANRYO-HYOJI.
           EXEC CICS
               SYNCPOINT
                   RESP(WS-RESP)
           END-EXEC
           IF WS-RESP NOT = DFHRESP(NORMAL)
               DISPLAY 'CSQ501 SYNCPOINTエラー RESP=' WS-RESP
           END-IF
           MOVE LOW-VALUES TO CSM01O
           MOVE DFHBMBRY TO MSGA
           MOVE '残高を更新しました。' TO MSGO
           EXEC CICS
               SEND MAP('CSM01') MAPSET('CSM501')
                   FROM(CSM01O) ERASE
                   RESP(WS-RESP)
           END-EXEC
           IF WS-RESP NOT = DFHRESP(NORMAL)
               DISPLAY 'CSQ501 SEND MAPエラー RESP=' WS-RESP
           END-IF.

       8000-ERROR-HYOJI.
           EXEC CICS
               SYNCPOINT ROLLBACK
                   RESP(WS-RESP)
           END-EXEC
           IF WS-RESP NOT = DFHRESP(NORMAL)
               DISPLAY 'CSQ501 ROLLBACKエラー RESP=' WS-RESP
           END-IF
           MOVE LOW-VALUES TO CSM01O
           MOVE DFHBMBRY TO MSGA
           MOVE WS-MSG TO MSGO
           EXEC CICS
               SEND MAP('CSM01') MAPSET('CSM501')
                   FROM(CSM01O) ERASE
                   RESP(WS-RESP)
           END-EXEC
           IF WS-RESP NOT = DFHRESP(NORMAL)
               DISPLAY 'CSQ501 SEND MAPエラー RESP=' WS-RESP
           END-IF.

       8100-MAPFAIL.
           MOVE 'Y' TO WS-ERR-FLAG
           MOVE '入力がありません。再入力してください。' TO WS-MSG
           PERFORM 8000-ERROR-HYOJI
           MOVE 'C' TO WS-CA-STATUS
           EXEC CICS
               RETURN TRANSID('CS51')
                   COMMAREA(WS-COMMAREA)
                   LENGTH(LENGTH OF WS-COMMAREA)
           END-EXEC.
