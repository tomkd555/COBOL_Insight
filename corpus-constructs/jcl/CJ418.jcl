//CJ418    JOB  (CJ0001),'ADRDSSU DUMP RESTORE',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ418 : constructs exercised (ADRDSSU / DFSMSdss)           *
//*    - DUMP DATASET(INCLUDE(...)) OUTDDNAME(ddname)            *
//*    - RESTORE ... INDDNAME(ddname) RENAMEU(...,...)            *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=ADRDSSU
//SYSPRINT DD   SYSOUT=*
//TAPE     DD   DSN=CJT.D260910.DSSU.DUMP,
//             DISP=(NEW,CATLG,DELETE),
//             UNIT=SYSDA,SPACE=(CYL,(20,10)),
//             DCB=(RECFM=VBS,LRECL=32760,BLKSIZE=32760)
//SYSIN    DD   *
  DUMP DATASET(INCLUDE(CJT.D260910.**)) -
       OUTDDNAME(TAPE)
/*
//*
//STEP020  EXEC PGM=ADRDSSU,COND=(4,LT,STEP010)
//SYSPRINT DD   SYSOUT=*
//TAPE     DD   DSN=CJT.D260910.DSSU.DUMP,DISP=SHR
//SYSIN    DD   *
  RESTORE DATASET(INCLUDE(CJT.D260910.**)) -
          INDDNAME(TAPE) -
          RENAMEU(CJT.D260910.**,CJT.D260911.**)
/*
