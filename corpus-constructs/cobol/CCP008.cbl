      *----------------------------------------------------------*
      *  PROGRAM-ID : CCP008                                     *
      *  Constructs : plain shared stub, no file I/O, DISPLAY,    *
      *               GOBACK. Named by EXEC PGM= in               *
      *               corpus-constructs/jcl fixtures only.        *
      *----------------------------------------------------------*
       IDENTIFICATION DIVISION.
       PROGRAM-ID.  CCP008.

       ENVIRONMENT DIVISION.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-MSG                         PIC X(20) VALUE 'CCP008 END'.

       PROCEDURE DIVISION.
       0000-MAIN.
           DISPLAY WS-MSG
           GOBACK.
