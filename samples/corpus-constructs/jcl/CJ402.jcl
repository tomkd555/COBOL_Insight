//CJ402    JOB  (CJ0001),'DSN BIND PLAN PACKAGE',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ402 : constructs exercised                                *
//*    - IKJEFT1B driving the DSN command processor              *
//*    - BIND PACKAGE(collection) MEMBER(...) ACTION(REPLACE)    *
//*    - BIND PLAN(...) PKLIST(collection.*) ACTION(REPLACE)     *
//*    - BIND PLAN(...) MEMBER(...) ACTION(REPLACE), the legacy  *
//*      DBRM-based form withdrawn after Db2 10, kept here only  *
//*      for the analyser's tolerance of older BIND syntax        *
//*    - FREE PACKAGE(collection.package)                        *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IKJEFT1B,DYNAMNBR=20
//STEPLIB  DD   DSN=CCP.PROD.LOADLIB,DISP=SHR
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//SYSUDUMP DD   SYSOUT=*
//DBRMLIB  DD   DSN=CCP.PROD.DBRMLIB,DISP=SHR
//SYSTSIN  DD   *
  DSN SYSTEM(DB2P)
  BIND PACKAGE(CCPCOLL1) MEMBER(CCP011) ACTION(REPLACE)            -
      ISOLATION(CS) VALIDATE(BIND)
  BIND PACKAGE(CCPCOLL1) MEMBER(CCP012) ACTION(REPLACE)            -
      ISOLATION(CS) VALIDATE(BIND)
  BIND PLAN(CCPPLAN1) PKLIST(CCPCOLL1.*) ACTION(REPLACE)           -
      ISOLATION(CS)
  BIND PLAN(CCPPLAN9) MEMBER(CCP011) ACTION(REPLACE)                -
      ISOLATION(CS)
  FREE PACKAGE(CCPCOLL1.CCP012)
  END
/*
