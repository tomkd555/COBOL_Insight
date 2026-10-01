//CJ419    JOB  (CJ0001),'DFSMSHSM HSEND',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ419 : constructs exercised                                *
//*    - IKJEFT01 driving DFSMShsm: the authorized HSEND form,   *
//*      HSEND MIGRATE DSNAME(dsn)                                *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IKJEFT01,DYNAMNBR=20
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//SYSUDUMP DD   SYSOUT=*
//SYSTSIN  DD   *
  HSEND MIGRATE DSNAME(CJT.D260901.WORK.OLDFILE)
/*
