//CJ405    JOB  (CJ0001),'DSNTEP2 BATCH SPUFI',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ405 : constructs exercised                                *
//*    - IKJEFT01 driving DSN RUN PROGRAM(DSNTEP2)               *
//*    - free-form SQL in SYSIN, three statements ended by ';'   *
//*    - SQL end-of-line '--' comments                           *
//*    - a statement continued across several physical lines     *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IKJEFT01,DYNAMNBR=20
//STEPLIB  DD   DSN=DB2P.SDSNLOAD,DISP=SHR
//SYSTSPRT DD   SYSOUT=*
//SYSPRINT DD   SYSOUT=*
//SYSUDUMP DD   SYSOUT=*
//SYSTSIN  DD   *
  DSN SYSTEM(DB2P)
  RUN PROGRAM(DSNTEP2) PLAN(DSNTEP2) LIB('DB2P.SDSNLOAD')
  END
/*
//SYSIN    DD   *
  SELECT KEIYAKU_NO, ZANDAKA           -- current balance
    FROM SCHEMA.TBL
   WHERE HANBAITEN_CD = 'H0001';
  UPDATE SCHEMA.TBL
     SET ZANDAKA = ZANDAKA - 1000,
         KOSHIN_YMD = '20260910'
   WHERE KEIYAKU_NO = 'K0000000001';
  SELECT COUNT(*)
    -- number of unprocessed rows
    FROM SCHEMA.TBL
   WHERE SHORI_KBN = '0';
/*
