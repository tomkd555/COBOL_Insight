//CJ406    JOB  (CJ0001),'DSNTIAUL DSNTIAD',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ406 : constructs exercised                                *
//*    - IKJEFT01 driving DSN RUN PROGRAM(DSNTIAUL/DSNTIAD)      *
//*    - DSNTIAUL: unloads a table via a free-form SELECT        *
//*    - DSNTIAD: applies a free-form UPDATE (non-SELECT)        *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IKJEFT01,DYNAMNBR=20
//STEPLIB  DD   DSN=DB2P.SDSNLOAD,DISP=SHR
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//SYSTSIN  DD   *
  DSN SYSTEM(DB2P)
  RUN PROGRAM(DSNTIAUL) PLAN(DSNTIAUL)                            -
      LIB('DB2P.RUNLIB.LOAD') PARMS('SQL')
  END
/*
//SYSREC00 DD   DSN=CJT.D260910.SCHEMA.TBL.TIAUL,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=100,BLKSIZE=0)
//SYSIN    DD   *
  SELECT KEIYAKU_NO, HANBAITEN_CD, ZANDAKA
    FROM SCHEMA.TBL
   WHERE SHORI_KBN = '0';
/*
//*
//STEP020  EXEC PGM=IKJEFT01,DYNAMNBR=20,COND=(4,LT,STEP010)
//STEPLIB  DD   DSN=DB2P.SDSNLOAD,DISP=SHR
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//SYSTSIN  DD   *
  DSN SYSTEM(DB2P)
  RUN PROGRAM(DSNTIAD) PLAN(DSNTIA13)                              -
      LIB('DB2P.RUNLIB.LOAD')
  END
/*
//SYSIN    DD   *
  UPDATE SCHEMA.TBL
     SET SHORI_KBN = '1'
   WHERE SHORI_KBN = '0'
     AND HANBAITEN_CD = 'H0001';
/*
