//CJ303    JOB  (CJ0001),'CJ CATLG PROC 2',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ303 : the same catalogued PROC CP301 as CJ302, called      *
//*          from a second job.                                  *
//*    JCLLIB  : ORDER= with a symbolic library name.             *
//*    Guard   : IF/THEN/ENDIF instead of COND on STEP020.        *
//*-------------------------------------------------------------*
//         SET PLQ=CJ.PROD
//         JCLLIB ORDER=(&PLQ..PROCLIB)
//         SET CYCLE=250902
//*
//STEP010  EXEC CP301,CYCLE=&CYCLE
//*
//         IF (STEP010.RC = 0) THEN
//STEP020  EXEC PGM=CCP003
//STEPLIB  DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//         ENDIF
