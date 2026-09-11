//CJ101    JOB  (CJ01,DEPT05,D250911),'CJ タロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,REGION=0M,TIME=1440
//*-------------------------------------------------------------*
//*  CJ101 : JOB statement common parameters (set 1)             *
//*    Accounting sublist with commas, quoted programmer name,   *
//*    CLASS, MSGCLASS, MSGLEVEL=(1,1), NOTIFY=&SYSUID,          *
//*    REGION=0M, TIME=1440.                                     *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//
