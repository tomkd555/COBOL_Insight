//CJ506    JOB  (CJ0001),'SCHEDULE STARTBY',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//         SCHEDULE STARTBY=('18:00','10/02/2026')
//*-------------------------------------------------------------*
//*  CJ506 : constructs exercised                                *
//*    - // SCHEDULE STARTBY=(date,time), a deadline independent *
//*      of any job group (JOBGROUP= and STARTBY= are mutually   *
//*      exclusive)                                              *
//*-------------------------------------------------------------*
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
