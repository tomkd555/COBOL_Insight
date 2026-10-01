//CJ421    JOB  (CJ0001),'DFHCSDUP CSD MAINT',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ421 : constructs exercised (DFHCSDUP, offline CSD update)*
//*    - DEFINE PROGRAM(name) GROUP(name)                        *
//*    - DEFINE TRANSACTION(name) PROGRAM(name) GROUP(name)       *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=DFHCSDUP
//STEPLIB  DD   DSN=CICSTS.CICS.SDFHLOAD,DISP=SHR
//DFHCSD   DD   DSN=CICSTS.PROD.DFHCSD,DISP=OLD
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
  DEFINE PROGRAM(CCP007) GROUP(G1)
  DEFINE TRANSACTION(TRN1) PROGRAM(CCP007) GROUP(G1)
/*
