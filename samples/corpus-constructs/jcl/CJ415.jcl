//CJ415    JOB  (CJ0001),'IEBPTPCH IEBUPDTE',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ415 : constructs exercised                                *
//*    - IEBPTPCH: PRINT TYPORG=PO                               *
//*    - IEBUPDTE: ./ ADD NAME=..., ./ NUMBER NEW1=...,INCR=...,  *
//*      in-line member text, ./ ENDUP                            *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEBPTPCH
//SYSPRINT DD   SYSOUT=*
//SYSUT1   DD   DSN=CJT.PROD.SRCLIB,DISP=SHR
//SYSUT2   DD   SYSOUT=*
//SYSIN    DD   *
  PRINT TYPORG=PO
/*
//*
//STEP020  EXEC PGM=IEBUPDTE,PARM=NEW,COND=(4,LT,STEP010)
//SYSPRINT DD   SYSOUT=*
//SYSUT1   DD   DUMMY
//SYSUT2   DD   DSN=CJT.TEST.SRCLIB,DISP=SHR
//SYSIN    DD   *
./ ADD NAME=NEWMEM
./ NUMBER NEW1=10,INCR=10
       IDENTIFICATION DIVISION.
       PROGRAM-ID. NEWMEM.
./ ENDUP
/*
