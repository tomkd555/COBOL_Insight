//CJ110    JOB  (CJ01),'CJ クロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,COND=(0,NE)
//*-------------------------------------------------------------*
//*  CJ110 : COND parameter forms on EXEC and on JOB             *
//*    STEP020 COND=(rc,op); STEP030 COND=(rc,op,step);          *
//*    STEP040 COND=((4,LT),(8,GT,STEP010)); STEP050 COND=EVEN;  *
//*    STEP060 COND=ONLY; STEP070 (last) COND=(0,NE), runs on    *
//*    success. The JOB statement above also carries COND=.      *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//STEP020  EXEC PGM=CCP007,COND=(4,LT)
//SYSOUT   DD   SYSOUT=*
//STEP030  EXEC PGM=CCP008,COND=(4,LT,STEP010)
//SYSOUT   DD   SYSOUT=*
//STEP040  EXEC PGM=CCP009,COND=((4,LT),(8,GT,STEP010))
//SYSOUT   DD   SYSOUT=*
//STEP050  EXEC PGM=CCP010,COND=EVEN
//SYSOUT   DD   SYSOUT=*
//STEP060  EXEC PGM=CCP006,COND=ONLY
//SYSOUT   DD   SYSOUT=*
//STEP070  EXEC PGM=CCP007,COND=(0,NE)
//SYSOUT   DD   SYSOUT=*
//
