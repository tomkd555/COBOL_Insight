//CJ423    JOB  (CJ0001),'CSQUTIL EZTPA00',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ423 : constructs exercised                                *
//*    - CSQUTIL: SYSIN COMMAND DDNAME(ddname), MQSC in that DD  *
//*    - EZTPA00: SYSIN holding an Easytrieve source program      *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CSQUTIL,PARM='CJQM01'
//STEPLIB  DD   DSN=MQM.PROD.SCSQANLE,DISP=SHR
//         DD   DSN=MQM.PROD.SCSQAUTH,DISP=SHR
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
COMMAND DDNAME(CMDINP)
/*
//CMDINP   DD   *
DEFINE QLOCAL('CJT.WORK.QUEUE') REPLACE
/*
//*
//STEP020  EXEC PGM=EZTPA00,COND=(4,LT,STEP010)
//STEPLIB  DD   DSN=CAI.EASY.LOADLIB,DISP=SHR
//EZTVFY   DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//KEIYAKU  DD   DSN=CJT.D260910.KEIYAKU.MASTER,DISP=SHR
//TOKLIST  DD   DSN=CJT.D260910.TOKUSOKU.LIST,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,1),RLSE),
//             DCB=(RECFM=FB,LRECL=132,BLKSIZE=0)
//SYSIN    DD   *
FILE KEIYAKU
   KEIYAKU-NO    1   8  A
   ZANDAKA       9   6  P  2
FILE TOKLIST
   OUT-NO        1   8  A
   OUT-GAKU      9   6  P  2
JOB INPUT KEIYAKU NAME TOKULIST
   IF ZANDAKA GT 100000
      MOVE KEIYAKU-NO TO OUT-NO
      MOVE ZANDAKA  TO OUT-GAKU
      PUT TOKLIST
   END-IF
/*
