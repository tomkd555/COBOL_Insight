      *----------------------------------------------------------*
      *  PROGRAM-ID : CSQ504                                      *
      *  構成       : SQLコンテナ構文の検証コーパス                *
      *  対象       : IMS BMP形式の連絡節（PCBマスク）、           *
      *               CALL 'CBLTDLI' USING によるDL/I呼び出しと    *
      *               EXEC SQLの併用。                             *
      *  用途       : 誤検出計測用（defect無し）                  *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CSQ504.
       AUTHOR.      CSQ-CORPUS-DEV.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
           EXEC SQL INCLUDE SQLCA END-EXEC.

           EXEC SQL BEGIN DECLARE SECTION END-EXEC.
       01  KOZA-NO                      PIC X(10).
       01  KOKYAKU-NO                   PIC X(8).
       01  ZANDAKA                      PIC S9(11) USAGE COMP-3.
       01  KOSHIN-YMD                   PIC X(8).
       01  WS-KOSHIN-YMD-IND            PIC S9(4) COMP VALUE ZERO.
           EXEC SQL END DECLARE SECTION END-EXEC.

       01  WS-PARM-AREA.
           05  WS-PARM-KOZA-NO          PIC X(10).

       01  WS-DLI-FUNC-GU               PIC X(4) VALUE 'GU  '.

       01  WS-SSA-KOKYAKU.
           05  FILLER                   PIC X(8) VALUE 'KOKYAKU '.
           05  FILLER                   PIC X(1) VALUE '('.
           05  FILLER                   PIC X(8) VALUE 'KOZA_NO '.
           05  FILLER                   PIC X(2) VALUE 'EQ'.
           05  WS-SSA-VALUE             PIC X(10).
           05  FILLER                   PIC X(1) VALUE ')'.

       01  WS-IOAREA.
           05  WS-IO-KOZA-NO            PIC X(10).
           05  WS-IO-KOKYAKU-NO         PIC X(8).
           05  FILLER                   PIC X(50).

       01  WS-STATUS-CODE               PIC X(2).

       LINKAGE SECTION.
       01  PCB-IO.
           05  PCB-IO-LTERM             PIC X(8).
           05  PCB-IO-RESERVE1          PIC X(2).
           05  PCB-IO-STATUS            PIC X(2).
           05  PCB-IO-DATE              PIC S9(7) COMP-3.
           05  PCB-IO-TIME              PIC S9(7) COMP-3.
           05  PCB-IO-SEQNO             PIC S9(5) COMP.

       01  PCB-KOKYAKU.
           05  PCB-DBDNAME              PIC X(8).
           05  PCB-SEGLEVEL             PIC X(2).
           05  PCB-STATUS               PIC X(2).
           05  PCB-PROCOPT              PIC X(4).
           05  FILLER                   PIC S9(5) COMP.
           05  PCB-SEGNAME              PIC X(8).
           05  PCB-KEYLENGTH            PIC S9(5) COMP.
           05  PCB-NUMSEGMENTS          PIC S9(5) COMP.
           05  PCB-KEY                  PIC X(8).

       PROCEDURE DIVISION USING PCB-IO PCB-KOKYAKU.
       0000-MAIN.
           ACCEPT WS-PARM-AREA
           MOVE WS-PARM-KOZA-NO TO WS-SSA-VALUE
           CALL 'CBLTDLI' USING WS-DLI-FUNC-GU PCB-KOKYAKU
               WS-IOAREA WS-SSA-KOKYAKU
           MOVE PCB-STATUS TO WS-STATUS-CODE
           IF WS-STATUS-CODE = SPACES
               MOVE WS-IO-KOZA-NO TO KOZA-NO
               PERFORM 1000-ZANDAKA-SHOKAI
           ELSE
               IF WS-STATUS-CODE = 'GE'
                   DISPLAY 'CSQ504 該当する顧客セグメントが'
                           'ありません。'
               ELSE
                   DISPLAY 'CSQ504 DL/I呼び出し異常 STATUS='
                           WS-STATUS-CODE
               END-IF
           END-IF
           GOBACK.

       1000-ZANDAKA-SHOKAI.
           EXEC SQL
               SELECT KOKYAKU_NO, ZANDAKA, KOSHIN_YMD
                 INTO :KOKYAKU-NO, :ZANDAKA,
                      :KOSHIN-YMD :WS-KOSHIN-YMD-IND
                 FROM CSDB.CSQKOZA
                WHERE KOZA_NO = :KOZA-NO
           END-EXEC
           IF SQLCODE NOT = 0
               DISPLAY 'CSQ504 Db2照会異常 SQLCODE=' SQLCODE
           ELSE
               IF WS-KOSHIN-YMD-IND < 0
                   DISPLAY 'CSQ504 顧客番号=' KOKYAKU-NO
                           ' 残高=' ZANDAKA
               ELSE
                   DISPLAY 'CSQ504 顧客番号=' KOKYAKU-NO
                           ' 残高=' ZANDAKA ' 更新日=' KOSHIN-YMD
               END-IF
           END-IF.
