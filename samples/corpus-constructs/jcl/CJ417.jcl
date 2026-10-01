//CJ417    JOB  (CJ0001),'IEHPROGM IEHLIST',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ417 : constructs exercised                                *
//*    - IEHPROGM: SCRATCH DSNAME=...,VOL=devtype=volser,PURGE   *
//*    - IEHLIST: LISTVTOC FORMAT,VOL=devtype=volser              *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEHPROGM
//SYSPRINT DD   SYSOUT=*
//VOL1     DD   UNIT=SYSDA,VOL=SER=SYSDA1,DISP=OLD
//SYSIN    DD   *
  SCRATCH DSNAME=CJT.D260908.WORK.PURGE,VOL=SYSDA=SYSDA1,PURGE
/*
//*
//STEP020  EXEC PGM=IEHLIST,COND=(4,LT,STEP010)
//SYSPRINT DD   SYSOUT=*
//VOL1     DD   UNIT=SYSDA,VOL=SER=SYSDA1,DISP=OLD
//SYSIN    DD   *
  LISTVTOC FORMAT,VOL=SYSDA=SYSDA1
/*
