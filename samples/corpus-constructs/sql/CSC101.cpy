      *----------------------------------------------------------------
      * CSC101 -- a paragraph held in a copybook, pulled in by a plain
      *   COBOL COPY statement. The paragraph itself carries one of
      *   CSQ109's EXEC SQL blocks, so the program's SQL is split
      *   between the .cbl file and this copybook.
      *----------------------------------------------------------------
       商品照会.
           EXEC SQL
               SELECT SHOHIN_MEI, TANKA
                 INTO :WS-SHOHIN-MEI, :WS-TANKA
                 FROM CSQDB.SHOHIN
                WHERE SHOHIN_CD = :WS-SHOHIN-CD
           END-EXEC
           EVALUATE SQLCODE
               WHEN 0
                   CONTINUE
               WHEN +100
                   MOVE SPACE TO WS-SHOHIN-MEI
                   MOVE ZERO  TO WS-TANKA
               WHEN OTHER
                   DISPLAY 'CSQ109 SELECT異常 SQLCODE=' SQLCODE
           END-EVALUATE.
