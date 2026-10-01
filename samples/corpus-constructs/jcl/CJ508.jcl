//CJ508A   JOB  (CJ0001),'MULTI-JOB DECK 1 OF 3',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ508 : constructs exercised                                *
//*    - one member holding three complete, independent jobs, one*
//*      after another                                           *
//*-------------------------------------------------------------*
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
//CJ508B   JOB  (CJ0001),'MULTI-JOB DECK 2 OF 3',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//STEP010   EXEC PGM=CCP001
//INFILE   DD   DUMMY
//OUTFILE  DD   DUMMY
//
//CJ508C   JOB  (CJ0001),'MULTI-JOB DECK 3 OF 3',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
