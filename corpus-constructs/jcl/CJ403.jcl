//CJ403    JOB  (CJ0001),'TSO HOUSEKEEPING CMDS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ403 : constructs exercised                                *
//*    - IKJEFT01 driving plain TSO commands in SYSTSIN          *
//*    - ALLOCATE DA('...') FI(ddname) SHR                       *
//*    - DELETE 'dsn'                                             *
//*    - FREE FI(ddname)                                          *
//*    - LISTCAT                                                  *
//*    - CALL 'loadlib(member)' 'parm'                            *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IKJEFT01,DYNAMNBR=20
//STEPLIB  DD   DSN=CCP.PROD.LOADLIB,DISP=SHR
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//SYSUDUMP DD   SYSOUT=*
//SYSTSIN  DD   *
  ALLOCATE DA('CJT.D260910.WORK.INPUT') FI(INFILE) SHR
  DELETE 'CJT.D260909.WORK.OLD'
  FREE FI(INFILE)
  LISTCAT ENTRIES('CJT.D260910.WORK.INPUT') ALL
  CALL 'CCP.PROD.LOADLIB(CCP006)' 'PARM1,PARM2'
/*
