//CJ504    JOB  (CJ0001),'JCL COMMAND STATEMENTS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ504 : constructs exercised                                *
//*    - // COMMAND 'text', the JCL COMMAND statement            *
//*    - // $S PRT1, a JES2 $ command coded directly as JCL      *
//*-------------------------------------------------------------*
// COMMAND 'S PROD1'
// $S PRT1
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
