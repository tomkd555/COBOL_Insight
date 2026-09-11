//CJ408    JOB  (CJ0001),'DFSORT MERGE',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ408 : constructs exercised (DFSORT, PGM=SORT)             *
//*    - MERGE FIELDS=(...) of two pre-sorted inputs             *
//*    - SORTIN01/SORTIN02 numbered merge inputs                 *
//*    - SORTOF01-style numbered OUTFIL output                   *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=SORT
//SORTIN01 DD   DSN=CJT.D260910.MERGE.IN01,DISP=SHR
//SORTIN02 DD   DSN=CJT.D260910.MERGE.IN02,DISP=SHR
//SORTOF01 DD   DSN=CJT.D260910.MERGE.OUT01,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//SYSIN    DD   *
  MERGE FIELDS=(1,8,CH,A)
  OUTFIL FNAMES=SORTOF01
/*
