//CJ518    JOB  (CJ0001),'CA7 IN-MEMBER OVERRIDE STMTS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
#JI,ID=1,BD=20260101,JOB=TESTJOB
//*-------------------------------------------------------------*
//*  CJ518 : constructs exercised                                *
//*    - #JI, directly in column 1 after the JOB card, including *
//*      statements for schedule ID 1                            *
//*    - #JO, omitting a statement for schedule ID 2             *
//*    - #JEND, closing the set of override control statements   *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
#JO,ID=2,BD=20260101,JOB=TESTJOB
//SYSUDUMP DD   SYSOUT=*
#JEND
//
