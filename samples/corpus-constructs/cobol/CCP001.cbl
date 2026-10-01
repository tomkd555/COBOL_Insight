      *----------------------------------------------------------*
      *  PROGRAM-ID : CCP001                                     *
      *  Constructs : QSAM shared stub, SELECT ASSIGN TO INFILE /*
      *               OUTFILE, OPEN INPUT/OUTPUT, READ, WRITE,   *
      *               CLOSE, DISPLAY, GOBACK. Shared batch stub  *
      *               for corpus-constructs JCL fixtures.        *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CCP001.

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT INFILE  ASSIGN TO INFILE
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-INFILE-STATUS.
           SELECT OUTFILE ASSIGN TO OUTFILE
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS WS-OUTFILE-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD  INFILE
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  IN-REC                         PIC X(80).

       FD  OUTFILE
           RECORDING MODE IS F
           LABEL RECORDS ARE STANDARD.
       01  OUT-REC                        PIC X(80).

       WORKING-STORAGE SECTION.
       01  WS-FILE-STATUS.
           05  WS-INFILE-STATUS           PIC X(02).
           05  WS-OUTFILE-STATUS          PIC X(02).
       01  WS-EOF-FLAG                    PIC X(01) VALUE 'N'.
           88  WS-EOF                         VALUE 'Y'.
       01  WS-OPEN-OK                     PIC X(01) VALUE 'Y'.

       PROCEDURE DIVISION.
       0000-MAIN.
           OPEN INPUT  INFILE
           IF WS-INFILE-STATUS NOT = '00'
               DISPLAY 'CCP001 INFILEオープン異常 STATUS='
                       WS-INFILE-STATUS
               MOVE 'N' TO WS-OPEN-OK
           END-IF
           OPEN OUTPUT OUTFILE
           IF WS-OUTFILE-STATUS NOT = '00'
               DISPLAY 'CCP001 OUTFILEオープン異常 STATUS='
                       WS-OUTFILE-STATUS
               MOVE 'N' TO WS-OPEN-OK
           END-IF
           IF WS-OPEN-OK = 'Y'
               PERFORM 1000-READ-LOOP UNTIL WS-EOF
               CLOSE INFILE
               IF WS-INFILE-STATUS NOT = '00'
                   DISPLAY 'CCP001 INFILEクローズ異常 STATUS='
                           WS-INFILE-STATUS
               END-IF
               CLOSE OUTFILE
               IF WS-OUTFILE-STATUS NOT = '00'
                   DISPLAY 'CCP001 OUTFILEクローズ異常 STATUS='
                           WS-OUTFILE-STATUS
               END-IF
           ELSE
               MOVE 12 TO RETURN-CODE
           END-IF
           DISPLAY 'CCP001 END'
           GOBACK.

       1000-READ-LOOP.
           READ INFILE
               AT END
                   MOVE 'Y' TO WS-EOF-FLAG
           END-READ
           IF NOT WS-EOF AND WS-INFILE-STATUS NOT = '00'
               DISPLAY 'CCP001 INFILE読込異常 STATUS='
                       WS-INFILE-STATUS
               MOVE 'Y' TO WS-EOF-FLAG
           END-IF
           IF NOT WS-EOF
               MOVE IN-REC TO OUT-REC
               WRITE OUT-REC
               IF WS-OUTFILE-STATUS NOT = '00'
                   DISPLAY 'CCP001 OUTFILE書込異常 STATUS='
                           WS-OUTFILE-STATUS
               END-IF
           END-IF.
