      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ308                                     *
      *  Constructs : A BLOB locator host variable (SQL TYPE IS   *
      *               BLOB-LOCATOR), FETCH INTO it, HOLD LOCATOR, *
      *               FREE LOCATOR; a CLOB file reference host    *
      *               variable (SQL TYPE IS CLOB-FILE); SELECT    *
      *               XMLSERIALIZE(... AS VARCHAR(n)) INTO a host *
      *               variable; INSERT with NEXT VALUE FOR a      *
      *               sequence; IDENTITY_VAL_LOCAL() after an      *
      *               INSERT into an identity column.             *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ308.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.
           EXEC SQL INCLUDE CSD303 END-EXEC.

      *  BUNSHO_LOG_ID is GENERATED ALWAYS AS IDENTITY in the base
      *  table; DECLARE TABLE cannot spell out an identity column,
      *  so it is declared NOT NULL WITH DEFAULT here instead.
           EXEC SQL
               DECLARE CSQDB.BUNSHO_LOG TABLE
               ( BUNSHO_LOG_ID          INTEGER NOT NULL WITH DEFAULT,
                 BUNSHO_ID              INTEGER NOT NULL,
                 SHORI_NAIYO            VARCHAR(40) NOT NULL )
           END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  WS-BUNSHO-ID                   PIC S9(9) COMP.
       01  WS-BLOB-LOC USAGE SQL TYPE IS BLOB-LOCATOR.
       01  WS-BLOB-LOC-IND                PIC S9(4) COMP.
       01  WS-CLOB-FILE USAGE SQL TYPE IS CLOB-FILE.
       01  WS-CLOB-FILE-IND               PIC S9(4) COMP.
       01  WS-XML-TEXT                    PIC X(4000).
       01  WS-XML-TEXT-IND                PIC S9(4) COMP.
       01  WS-NEW-BUNSHO-MEI.
           49  WS-NEW-BUNSHO-MEI-LEN      PIC S9(4) COMP.
           49  WS-NEW-BUNSHO-MEI-TXT      PIC X(60).
       01  WS-DATE                        PIC X(8).
       01  WS-SHORI-NAIYO.
           49  WS-SHORI-NAIYO-LEN         PIC S9(4) COMP.
           49  WS-SHORI-NAIYO-TXT         PIC X(40).
       01  WS-NEW-LOG-ID                  PIC S9(18) COMP-3.
           EXEC SQL END DECLARE SECTION END-EXEC.

           EXEC SQL
               DECLARE CSR-BUNSHO CURSOR FOR
                   SELECT GAZOU
                     FROM CSQDB.BUNSHO
                    WHERE BUNSHO_ID = :WS-BUNSHO-ID
                    FOR FETCH ONLY
           END-EXEC.

       01  WS-ERR-MSG                     PIC X(60).

       PROCEDURE DIVISION.
       0000-MAIN.
           MOVE 1 TO WS-BUNSHO-ID
           PERFORM 1000-BLOB-LOCATOR-DEMO
           PERFORM 2000-CLOB-FILE-DEMO
           PERFORM 3000-XML-SERIALIZE-DEMO
           PERFORM 4000-SEQUENCE-INSERT
           PERFORM 5000-IDENTITY-INSERT
           DISPLAY 'CSQ308 END'
           STOP RUN.

       1000-BLOB-LOCATOR-DEMO.
           EXEC SQL
               OPEN CSR-BUNSHO
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '文書カーソル開始エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               FETCH CSR-BUNSHO INTO :WS-BLOB-LOC :WS-BLOB-LOC-IND
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '画像取得エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               CLOSE CSR-BUNSHO
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '文書カーソル終了エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               HOLD LOCATOR :WS-BLOB-LOC
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'ロケーター保持エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               FREE LOCATOR :WS-BLOB-LOC
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'ロケーター解放エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       2000-CLOB-FILE-DEMO.
           MOVE '/u/csq/teisai.txt' TO WS-CLOB-FILE-NAME
           MOVE 17                 TO WS-CLOB-FILE-NAME-LENGTH
           MOVE SQL-FILE-OVERWRITE TO WS-CLOB-FILE-FILE-OPTION
           EXEC SQL
               SELECT TEISAI
                 INTO :WS-CLOB-FILE :WS-CLOB-FILE-IND
                 FROM CSQDB.BUNSHO
                WHERE BUNSHO_ID = :WS-BUNSHO-ID
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '体裁出力エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       3000-XML-SERIALIZE-DEMO.
           EXEC SQL
               SELECT XMLSERIALIZE(NAIYOU_XML AS VARCHAR(4000))
                 INTO :WS-XML-TEXT :WS-XML-TEXT-IND
                 FROM CSQDB.BUNSHO
                WHERE BUNSHO_ID = :WS-BUNSHO-ID
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE 'XML変換エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       4000-SEQUENCE-INSERT.
           MOVE 12               TO WS-NEW-BUNSHO-MEI-LEN
           MOVE 'NEW DOCUMENT'   TO WS-NEW-BUNSHO-MEI-TXT
           MOVE '20260910'       TO WS-DATE
           EXEC SQL
               INSERT INTO CSQDB.BUNSHO
                      (BUNSHO_ID, BUNSHO_MEI, KOSHIN_YMD)
               VALUES (NEXT VALUE FOR CSQDB.BUNSHO_SEQ,
                       :WS-NEW-BUNSHO-MEI, :WS-DATE)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '文書採番登録エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       5000-IDENTITY-INSERT.
           MOVE 10           TO WS-SHORI-NAIYO-LEN
           MOVE 'REGISTERED' TO WS-SHORI-NAIYO-TXT
           EXEC SQL
               INSERT INTO CSQDB.BUNSHO_LOG (BUNSHO_ID, SHORI_NAIYO)
               VALUES (:WS-BUNSHO-ID, :WS-SHORI-NAIYO)
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '処理履歴登録エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF
           EXEC SQL
               VALUES IDENTITY_VAL_LOCAL()
                   INTO :WS-NEW-LOG-ID
           END-EXEC
           IF SQLCODE NOT = 0
               MOVE '採番値取得エラー' TO WS-ERR-MSG
               PERFORM 9900-SQL-ERROR
           END-IF.

       9900-SQL-ERROR.
           DISPLAY 'CSQ308 SQLエラー SQLCODE = ' SQLCODE
           DISPLAY '  内容 : ' WS-ERR-MSG
           EXEC SQL ROLLBACK END-EXEC
           MOVE 12 TO RETURN-CODE
           GOBACK.
