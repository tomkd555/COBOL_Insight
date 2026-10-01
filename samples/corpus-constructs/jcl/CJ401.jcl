//CJ401    JOB  (CJ0001),'DSN RUN PROGRAM',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ401 : constructs exercised                                *
//*    - IKJEFT01 driving the DSN command processor              *
//*    - SYSTSIN: DSN SYSTEM(subsystem)                          *
//*    - RUN PROGRAM(...) PLAN(...) LIB(...) PARMS(...)          *
//*    - trailing-hyphen continuation of a TSO command line       *
//*    - a second RUN in the same SYSTSIN, then END              *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IKJEFT01,DYNAMNBR=20
//STEPLIB  DD   DSN=CCP.PROD.LOADLIB,DISP=SHR
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//SYSUDUMP DD   SYSOUT=*
//SYSTSIN  DD   *
  DSN SYSTEM(DB2P)
  RUN PROGRAM(CCP011) PLAN(CCPPLAN1) LIB('CCP.PROD.LOADLIB')      -
      PARMS('20260101,RERUN')
  RUN PROGRAM(CCP012) PLAN(CCPPLAN2) LIB('CCP.PROD.LOADLIB')
  END
/*
