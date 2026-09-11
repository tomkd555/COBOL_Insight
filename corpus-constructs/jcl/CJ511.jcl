//CJ511    JOB  (CJ0001),'CONTINUATION TRIGGERS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ511 : constructs exercised                                *
//*    - trailing comma before column 72, no flag character      *
//*      (ordinary case)                                         *
//*    - trailing comma at column 71 with a non-blank flag        *
//*      character in column 72, resumed at column 16             *
//*    - an open quoted string broken at column 71, resumed at   *
//*      column 16 with no leading quote                         *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEFBR14,PARM='CJ511 CONTINUATION DEMO PARM BROKEN MX
//             ID LITERAL AND CLOSED HERE'
//INDD1    DD DSN=CJT.CJ511.CONTINUATION.DEMO.QUALIFIER.ONE,
//             DISP=SHR
//SYSUT2 DD DSN=CJT.CJ511.CONTINUATION.TARGET,DISP=SHR,VOL=SER=(VOL001,X
//             VOL002,VOL003,VOL004)
//SYSPRINT DD   SYSOUT=*
//
