//CJ407    JOB  (CJ0001),'DFSORT SORT OUTFIL',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ407 : constructs exercised (DFSORT, PGM=SORT)             *
//*    - SORT FIELDS=(...) continued on a second control card    *
//*    - INCLUDE COND=(...)                                      *
//*    - INREC OVERLAY=(...)                                     *
//*    - OUTREC BUILD=(...)                                      *
//*    - SUM FIELDS=NONE                                         *
//*    - OUTFIL FNAMES=ddname,INCLUDE=(...)                      *
//*    - OUTFIL FNAMES=(ddname,ddname) fan-out                   *
//*    - a second step: OPTION COPY (no sort, filter only)       *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=SORT
//SORTIN   DD   DSN=CJT.D260910.SORT.INPUT,DISP=SHR
//SORTWK01 DD   UNIT=SYSDA,SPACE=(CYL,(10,5))
//SORTWK02 DD   UNIT=SYSDA,SPACE=(CYL,(10,5))
//SYSOUT   DD   SYSOUT=*
//OUT1     DD   DSN=CJT.D260910.SORT.OUT1,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=88,BLKSIZE=0)
//OUT2     DD   DSN=CJT.D260910.SORT.OUT2,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=88,BLKSIZE=0)
//OUT3     DD   DSN=CJT.D260910.SORT.OUT3,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=88,BLKSIZE=0)
//SYSIN    DD   *
  SORT FIELDS=(1,8,CH,A,
               20,3,PD,D)
  INCLUDE COND=(9,2,CH,EQ,C'AB')
  INREC OVERLAY=(81:SEQNUM,8,ZD)
  OUTREC BUILD=(1,80,81,8)
  SUM FIELDS=NONE
  OUTFIL FNAMES=OUT1,INCLUDE=(1,3,CH,EQ,C'ABC')
  OUTFIL FNAMES=(OUT2,OUT3)
/*
//*
//STEP020  EXEC PGM=SORT,COND=(4,LT,STEP010)
//SORTIN   DD   DSN=CJT.D260910.SORT.INPUT2,DISP=SHR
//SORTOUT  DD   DSN=CJT.D260910.SORT.COPYOUT,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//SYSIN    DD   *
  OPTION COPY
  INCLUDE COND=(1,3,CH,EQ,C'XYZ')
/*
