//CJ412    JOB  (CJ0001),'IDCAMS LISTCAT ALTER IF',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ412 : constructs exercised (IDCAMS)                       *
//*    - LISTCAT ENTRIES(...) ALL                                *
//*    - ALTER ... NEWNAME(...)                                  *
//*    - PRINT INFILE(ddname) COUNT(n)                           *
//*    - IF LASTCC > 0 THEN ... ELSE ...                         *
//*    - IF MAXCC = 0 THEN DO ... END                            *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//IN       DD   DSN=CJT.D260910.PRINT.INPUT,DISP=SHR
//SYSIN    DD   *
  LISTCAT ENTRIES(CJV.KEIYAKU.CLUSTER) ALL
  IF LASTCC > 0 THEN SET MAXCC = 0 ELSE -
      LISTCAT ENTRIES(CJV.KEIYAKU.AIX) ALL
  ALTER CJT.D260909.WORK.OLD -
        NEWNAME(CJT.D260909.WORK.RENAMED)
  PRINT INFILE(IN) COUNT(10)
  IF MAXCC = 0 THEN DO
      PRINT INFILE(IN) COUNT(5)
      SET MAXCC = 0
  END
/*
