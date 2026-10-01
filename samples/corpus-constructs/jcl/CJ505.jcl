//GRP1     JOBGROUP
//J1       GJOB
//J2       GJOB
//J2       AFTER NAME=J1
//GRP1     ENDGROUP
//*-------------------------------------------------------------*
//*  CJ505 : constructs exercised                                *
//*    - JOBGROUP/GJOB/AFTER NAME=/ENDGROUP defining a two-job   *
//*      dependency graph, coded before either member job        *
//*    - // SCHEDULE JOBGROUP=, on each member job after ENDGROUP*
//*-------------------------------------------------------------*
//J1       JOB  (CJ0001),'GROUP MEMBER JOB 1',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//         SCHEDULE JOBGROUP=GRP1
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
//J2       JOB  (CJ0001),'GROUP MEMBER JOB 2',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//         SCHEDULE JOBGROUP=GRP1
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
