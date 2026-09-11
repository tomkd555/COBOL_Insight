//CJ307    JOB  (CJ0001),'CJ REFERBACKS',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ307 : DD-parameter referback forms, and JOBLIB/STEPLIB    *
//*          concatenation.                                      *
//*    DD2 : DSN=*.DD1, same step.                                *
//*    DD3 : DSN=*.STEP010.DD1, an earlier job step.              *
//*    DD4 : DCB=*.STEP010.DD1.                                   *
//*    DD5 : VOL=REF=*.STEP010.DD1.                                *
//*    DD6 : UNIT=AFF=DD5, same step.                              *
//*    DD7 : REFDD=*.DD3, same step.                               *
//*    DD8 : DSN=*.STEP020.OUTSTEP.WORK1, a DD inside the PROC    *
//*          that STEP020 called.                                 *
//*    CJPRT: OUTPUT=*.OUT1.                                       *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(CJ.PROD.PROCLIB)
//JOBLIB   DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//         DD   DSN=CJ.PROD.LOADLB2,DISP=SHR
//OUT1     OUTPUT DEST=LOCAL,COPIES=1
//         SET CYCLE=250901
//*
//STEP010  EXEC PGM=CCP009
//DD1      DD   DSN=CJT.D&CYCLE..CJ307.DD1,DISP=SHR
//DD2      DD   DSN=*.DD1,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//*
//STEP020  EXEC CP301,CYCLE=&CYCLE,COND=(0,NE,STEP010)
//*
//STEP030  EXEC PGM=CCP010,COND=((0,NE,STEP010),(0,NE,STEP020))
//STEPLIB  DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//         DD   DSN=CJ.PROD.LOADLB2,DISP=SHR
//DD3      DD   DSN=*.STEP010.DD1,DISP=SHR
//DD4      DD   DSN=CJT.D&CYCLE..CJ307.DD4,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             DCB=*.STEP010.DD1
//DD5      DD   DSN=CJT.D&CYCLE..CJ307.DD5,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             VOL=REF=*.STEP010.DD1
//DD6      DD   DSN=CJT.D&CYCLE..CJ307.DD6,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             UNIT=AFF=DD5
//DD7      DD   DSN=CJT.D&CYCLE..CJ307.DD7,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             REFDD=*.DD3
//DD8      DD   DSN=*.STEP020.OUTSTEP.WORK1,DISP=SHR
//CJPRT    DD   SYSOUT=*,OUTPUT=*.OUT1
