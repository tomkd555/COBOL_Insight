//CJ108    JOB  (CJ01),'CJ シチロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),
//*  A comment statement here, between the continuation lines.  *
//             NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ108 : null statement, comment placement, delimiters        *
//*    A comment statement sits between the continuation lines    *
//*    above. SYSIN closes on the default /* delimiter; SYSIN2    *
//*    closes on DLM=ZZ, so a literal /* inside it is just data.  *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//SYSIN    DD   *
CJ108 SYSIN DATA LINE 1
/*
//SYSIN2   DD   DATA,DLM=ZZ
CJ108 SYSIN2 DATA LINE WITH /* INSIDE IT
ZZ
//
