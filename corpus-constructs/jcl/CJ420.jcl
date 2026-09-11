//CJ420    JOB  (CJ0001),'FTP IRXJCL',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ420 : constructs exercised                                *
//*    - PGM=FTP: INPUT script (OPEN/USER/BINARY/PUT/QUIT),      *
//*      no NETRC DD, so USER supplies the id and password       *
//*    - PGM=IRXJCL: standalone batch REXX, PARM='exec args'     *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=FTP
//SYSPRINT DD   SYSOUT=*
//OUTPUT   DD   SYSOUT=*
//INPUT    DD   *
  OPEN MVSHOST.EXAMPLE.COM
  USER MFTUSER01 MFTPASS01
  BINARY
  PUT 'CJT.D260910.FTP.OUTBOUND' MFTOUT.DAT
  QUIT
/*
//*
//STEP020  EXEC PGM=IRXJCL,PARM='MYEXEC ARG1',
//             COND=(4,LT,STEP010)
//SYSEXEC  DD   DSN=CJT.PROD.REXXLIB,DISP=SHR
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
