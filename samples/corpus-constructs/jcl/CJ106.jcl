//CJ106    JOB  (CJ01),'CJ ゴロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ106 : EXEC statement PROC forms                            *
//*    STEP010 : positional procedure name (EXEC CJPR01)         *
//*    STEP020 : keyword form (EXEC PROC=CJPR01), COND-guarded    *
//*-------------------------------------------------------------*
//CJPR01   PROC
//STEP1    EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//         PEND
//*
//STEP010  EXEC CJPR01
//*
//STEP020  EXEC PROC=CJPR01,COND=(4,LT,STEP010.STEP1)
//
