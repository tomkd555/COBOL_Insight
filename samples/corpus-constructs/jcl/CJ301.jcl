//CJ301    JOB  (CJ0001),'CJ IN-STREAM PROC',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ301 : in-stream PROC with symbolic defaults and PEND,     *
//*          called twice by the same job with different         *
//*          symbolic overrides at each call.                    *
//*    SET   : several symbols on one SET statement.              *
//*-------------------------------------------------------------*
//         SET CYCLE=250901,HLQ=CJW,ENV=PROD
//*
//CJPRC01  PROC CYCLE=000000,HLQ=CJW
//STEP1    EXEC PGM=CCP007,PARM='&CYCLE'
//STEPLIB  DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//OUT1     DD   DSN=&HLQ..D&CYCLE..CJPRC01.OUT1,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             DCB=(RECFM=FB,LRECL=100,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//         PEND
//*
//*  First call: default HLQ, overridden CYCLE.
//STEP010  EXEC CJPRC01,CYCLE=&CYCLE
//*
//*  Second call: both CYCLE and HLQ overridden, run only if the
//*  first call ended normally.
//STEP020  EXEC CJPRC01,CYCLE=&CYCLE,HLQ=CJW2,COND=(0,NE,STEP010)
