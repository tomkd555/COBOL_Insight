//CJ104    JOB  (CJ01),'CJ シロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,TYPRUN=SCAN
//*-------------------------------------------------------------*
//*  CJ104 : JOB statement TYPRUN=SCAN parameter                 *
//*    TYPRUN=SCAN checks the JCL syntax only; no step of this   *
//*    job ever runs.                                            *
//*-------------------------------------------------------------*
//STEP1    EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//STEP2    EXEC PGM=CCP007,COND=(4,LT,STEP1)
//SYSOUT   DD   SYSOUT=*
//
