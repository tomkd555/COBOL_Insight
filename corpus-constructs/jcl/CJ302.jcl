//CJ302    JOB  (CJ0001),'CJ CATLG PROC 1',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ302 : catalogued PROC CP301 called from this job and from *
//*          CJ303; CP301 nests CP302 two levels deep.            *
//*    JCLLIB  : literal two-entry ORDER= library list.          *
//*    Override: reaching a DD of the nested inner PROC (CP302's *
//*              INSTEP) works only one level down, so the       *
//*              override lives inside CP301; this job passes    *
//*              the data set name through NESTDSN= instead.     *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(CJ.PROD.PROCLIB,CJ.TEST.PROCLIB)
//         SET CYCLE=250901
//*
//STEP010  EXEC CP301,CYCLE=&CYCLE,NESTDSN=CJW.D&CYCLE..CJ302.NEST
//*
//STEP020  EXEC PGM=CCP003,COND=(0,NE,STEP010)
//STEPLIB  DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//SYSOUT   DD   SYSOUT=*
