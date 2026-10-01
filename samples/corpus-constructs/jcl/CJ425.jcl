//CJ425    JOB  (CJ0001),'FILE-AID BATCH COPY',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ425 : constructs exercised                                *
//*    - PGM=FILEAID: $$ddname COPY control statement            *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=FILEAID
//STEPLIB  DD   DSN=BMC.FILEAID.LOADLIB,DISP=SHR
//DD01     DD   DSN=CJT.D260910.FILEAID.INPUT,DISP=SHR
//DD01O    DD   DSN=CJT.D260910.FILEAID.OUTPUT,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
$$DD01 COPY
/*
