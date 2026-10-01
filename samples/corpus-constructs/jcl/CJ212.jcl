//CJ212    JOB  (CJ0001),'CJ CNTL RD',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID,
//             RESTART=STEP020
//SYSCHK   DD   DSN=FLW.CJ212.CHKPT,DISP=(OLD,KEEP)
//*-----------------------------------------------------------------
//* CJ212 : CNTL/ENDCNTL control statements and checkpoint restart
//*   A named CNTL group holding a PRINTDEV control statement, a
//*   SYSOUT DD referencing it through CNTL=*.ddname, and a second
//*   step whose EXEC carries RD=R for checkpoint restart. RESTART=
//*   on the JOB statement names STEP020 and SYSCHK is the job-level
//*   checkpoint data set that a RESTART= rerun reads.
//*-----------------------------------------------------------------
//STEP010  EXEC PGM=IEFBR14
//CTL1     CNTL *
//         PRINTDEV FONT(GT12)
//         ENDCNTL
//PRTOUT   DD SYSOUT=A,CNTL=*.CTL1
//STEP020  EXEC PGM=IEFBR14,COND=(4,LT),RD=R
//
