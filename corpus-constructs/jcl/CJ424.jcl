//CJ424    JOB  (CJ0001),'SAS VIA CATLGD PROC',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ424 : constructs exercised                                *
//*    - EXEC of a catalogued PROC (CPSAS) that runs PGM=SAS     *
//*    - a DD override on the PROC's step (SASSTEP.SYSIN)        *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(CJT.PROD.PROCLIB)
//STEP010  EXEC CPSAS,LIB=CJT.PROD.SASLIB
//SASSTEP.SYSIN DD *
DATA WORK.KEIYAKU;
   SET KEIYAKU(KEEP=KEIYAKU_NO ZANDAKA);
   IF ZANDAKA > 100000;
RUN;
/*
