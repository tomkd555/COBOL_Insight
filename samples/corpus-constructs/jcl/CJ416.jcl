//CJ416    JOB  (CJ0001),'IEFBR14 DD LIFECYCLE',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ416 : constructs exercised                                *
//*    - IEFBR14 creating a dataset: DISP=(NEW,CATLG,DELETE)      *
//*    - IEFBR14 deleting a dataset: DISP=(MOD,DELETE,DELETE)     *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEFBR14
//NEWDS    DD   DSN=CJT.D260910.WORK.NEWFILE,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(1,1),RLSE),
//             DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//OLDDS    DD   DSN=CJT.D260909.WORK.OLDFILE,
//             DISP=(MOD,DELETE,DELETE)
