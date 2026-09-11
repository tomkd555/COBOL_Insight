//CJ413    JOB  (CJ0001),'IEBGENER COPY GENERATE',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ413 : constructs exercised (IEBGENER)                     *
//*    - plain byte copy with SYSIN DD DUMMY                     *
//*    - GENERATE MAXFLDS=n / RECORD FIELD=(...),FIELD=(...)     *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEBGENER
//SYSPRINT DD   SYSOUT=*
//SYSUT1   DD   DSN=CJT.D260910.GENER.INPUT,DISP=SHR
//SYSUT2   DD   DSN=CJT.D260910.GENER.OUTPUT,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//SYSIN    DD   DUMMY
//*
//STEP020  EXEC PGM=IEBGENER,COND=(4,LT,STEP010)
//SYSPRINT DD   SYSOUT=*
//SYSUT1   DD   DSN=CJT.D260910.GENER.INPUT2,DISP=SHR
//SYSUT2   DD   DSN=CJT.D260910.GENER.OUTPUT2,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//SYSIN    DD   *
  GENERATE MAXFLDS=2
  RECORD FIELD=(8,1,,1),FIELD=(72,9,,9)
/*
