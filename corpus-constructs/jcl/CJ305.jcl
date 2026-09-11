//CJ305    JOB  (CJ0001),'CJ DD OVERRIDES',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ305 : DD and EXEC overrides on catalogued PROC CP304.     *
//*    EXEC   : PARM.STEP1=, COND.STEP2=, TIME.STEP1=,           *
//*             REGION.STEP1= on the calling EXEC.               *
//*    INDD         : unqualified override, new DSN and DISP.    *
//*    STEP1.OUTDD  : override of a single subparameter only     *
//*                   (DCB=BLKSIZE=).                             *
//*    STEP1.WORKDD : override of a single parameter only        *
//*                   (SPACE=).                                   *
//*    STEP1.RPTDD  : DD addition, a ddname CP304 does not code. *
//*    Overrides are coded in STEP1's own DD order (INDD, OUTDD, *
//*    WORKDD) with the RPTDD addition last, as JCL requires.    *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(CJ.PROD.PROCLIB)
//         SET CYCLE=250901,HLQ=CJW
//*
//STEP010  EXEC CP304,CYCLE=&CYCLE,HLQ=&HLQ,
//             PARM.STEP1='RUN=&CYCLE',
//             COND.STEP2=(0,NE,STEP1),
//             TIME.STEP1=5,REGION.STEP1=4M
//INDD     DD   DSN=&HLQ..D&CYCLE..CJ305.ALTIN,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(1,1),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//STEP1.OUTDD DD DCB=BLKSIZE=27920
//STEP1.WORKDD DD SPACE=(CYL,(20,10),RLSE)
//STEP1.RPTDD DD SYSOUT=*
