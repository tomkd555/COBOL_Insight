/*SIGNON       REMOTE3
/*PRIORITY 8
//CJ501    JOB  (CJ0001),'JES2 JECL STATEMENTS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
/*JOBPARM LINES=999,SYSAFF=(SYSA,SYSB),PROCLIB=PROC01
/*ROUTE PRINT RMT5
/*ROUTE XEQ NODE1
/*OUTPUT OUT1 DEST=RMT3,COPIES=2
/*XEQ NODE1
/*SETUP VOL001
/*MESSAGE MOUNT TAPES
/*NOTIFY USER01
//*-------------------------------------------------------------*
//*  CJ501 : constructs exercised                                *
//*    - /*SIGNON and /*PRIORITY, both coded before the JOB card *
//*    - /*JOBPARM LINES=/SYSAFF=/PROCLIB= after the JOB card    *
//*    - /*ROUTE PRINT and /*ROUTE XEQ (JES2 form)               *
//*    - /*OUTPUT DEST=/COPIES=, and /*XEQ (legacy execution     *
//*      node)                                                   *
//*    - /*SETUP, /*MESSAGE, /*NOTIFY                            *
//*    - /*SIGNOFF, after the job's own null statement           *
//*-------------------------------------------------------------*
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
/*SIGNOFF
