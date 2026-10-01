//CJ411    JOB  (CJ0001),'IDCAMS GDG REPRO DELETE',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ411 : constructs exercised (IDCAMS)                       *
//*    - DEFINE GDG (...) with LIMIT() and SCRATCH               *
//*    - REPRO INFILE(ddname) OUTFILE(ddname)                    *
//*    - REPRO INDATASET(dsn) OUTDATASET(dsn)                    *
//*    - DELETE dsn CLUSTER PURGE                                 *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//IN       DD   DSN=CJT.D260910.REPRO.INPUT,DISP=SHR
//OUT      DD   DSN=CJT.D260910.REPRO.OUTPUT,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//SYSIN    DD   *
  DEFINE GDG (NAME(CJT.KEIYAKU.HISTORY)                    -
              LIMIT(10)                                    -
              SCRATCH)
  REPRO INFILE(IN) OUTFILE(OUT)
  REPRO INDATASET(CJT.D260909.REPRO.OLDDATA)                -
        OUTDATASET(CJT.D260910.REPRO.NEWDATA)
  DELETE CJT.D260908.WORK.TEMP CLUSTER PURGE
/*
