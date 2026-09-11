//CJ103    JOB  (CJ01),'CJ サブロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,RESTART=STEP2
//*-------------------------------------------------------------*
//*  CJ103 : JOB statement RESTART= parameter                    *
//*    RESTART=STEP2 resumes the job at STEP2 on rerun. STEP1    *
//*    is statically unreachable from that restart entry point.  *
//*-------------------------------------------------------------*
//STEP1    EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//STEP2    EXEC PGM=CCP007,COND=(4,LT,STEP1)
//SYSOUT   DD   SYSOUT=*
//
