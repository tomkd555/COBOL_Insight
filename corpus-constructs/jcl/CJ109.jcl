//CJ109    JOB  (CJ01),'CJ ハチロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,COND=(0,NE)
//*-------------------------------------------------------------*
//*  CJ109 : nested IF/THEN/ELSE/ENDIF (3 levels), step and       *
//*    step.procstep qualification. RC, ABEND, ABENDCC=S0C7,      *
//*    RUN, NOT, AND/OR with parentheses. STEP060, the job's      *
//*    last step, carries no COND of its own: it runs only        *
//*    through the job-level COND=(0,NE) above.                   *
//*-------------------------------------------------------------*
//CJPR01   PROC
//STEP1    EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//         PEND
//*
//STEP010  EXEC CJPR01
//*
//         IF (NOT STEP010.STEP1.ABEND) THEN
//            IF (STEP010.STEP1.RC <= 4) THEN
//             IF (STEP010.RUN AND (STEP010.STEP1.RC = 0 OR            X
//             STEP010.STEP1.RC = 4)) THEN
//STEP020  EXEC PGM=CCP007
//             ELSE
//STEP021  EXEC PGM=CCP008
//             ENDIF
//            ELSE
//STEP024  EXEC PGM=CCP006
//            ENDIF
//         ELSE
//            IF (STEP010.STEP1.ABENDCC = S0C7) THEN
//STEP022  EXEC PGM=CCP009
//            ELSE
//STEP023  EXEC PGM=CCP010
//            ENDIF
//         ENDIF
//*
//STEP050  EXEC PGM=CCP007,COND=(4,LT,STEP010.STEP1)
//SYSOUT   DD   SYSOUT=*
//*
//STEP060  EXEC PGM=CCP008
//SYSOUT   DD   SYSOUT=*
//
