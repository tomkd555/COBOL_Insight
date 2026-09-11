//CJ306    JOB  (CJ0001),'CJ SYMBOLICS',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ306 : symbol substitution edge cases.                     *
//*    SET    : several symbols on one statement; SFX given a    *
//*             null value.                                      *
//*    CJIN   : &SFX. substitutes to nothing; &CYCLE. sits        *
//*             before a qualifier period.                       *
//*    CJWORK : &&WORK1 is a temporary dataset name, not a       *
//*             symbol.                                          *
//*    CJCLN  : the system symbols &YYMMDD and &SYSNAME.         *
//*    CJHIST : the system symbol &LYYMMDD.                       *
//*    CJCLONE: &SYSCLONE substringed, (2:1).                     *
//*    PARM   : a symbol inside PARM quotes.                      *
//*-------------------------------------------------------------*
//         SET CYCLE=250901,SFX=
//*
//STEP010  EXEC PGM=CCP007,PARM='CYCLE=&CYCLE,ENV=PROD'
//STEPLIB  DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//CJIN     DD   DSN=CJT.D&CYCLE..NYUKIN&SFX..DAILY,DISP=SHR
//CJWORK   DD   DSN=&&WORK1,UNIT=SYSDA,SPACE=(CYL,(1,1)),
//             DISP=(NEW,DELETE,DELETE)
//CJCLN    DD   DSN=CJT.D&YYMMDD..S&SYSNAME..DAILY,DISP=SHR
//CJHIST   DD   DSN=CJT.HIST.D&LYYMMDD,DISP=SHR
//CJCLONE  DD   DSN=CJT.D&SYSCLONE(2:1).DAILY,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//*
//STEP020  EXEC PGM=CCP008,COND=(0,NE,STEP010)
//STEPLIB  DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//SYSOUT   DD   SYSOUT=*
