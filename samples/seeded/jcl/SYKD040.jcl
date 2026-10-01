//SYKD040  JOB  (SYK1234),'SYK04 在庫再集計',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,RESTART=STEP020
//*-------------------------------------------------------------*
//*  SYKD040 : 在庫集計表の再集計　日次バッチ                     *
//*    STEP010 : 在庫集計表の照会・更新(SYK010、Db2)              *
//*              SYKLOG(処理ログ) の DD 文を書き忘れている        *
//*    STEP020 : カーソル操作(SYK011、Db2)                        *
//*              BACKREF の参照先 STEP999 がない                  *
//*    STEP030 : 集計結果の印刷(インストリームPROC SYKPRC02)       *
//*    STEP040 : 明細の印刷(PROC SYKPRC99、メンバーが存在しない)   *
//*-------------------------------------------------------------*
//         SET CYCLE=250718
//*
//SYKPRC02 PROC CYCLE=000000
//PRTSTEP  EXEC PGM=SYK005
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//         PEND
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=SYK010,PARM='&MODE'
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//SYSOUT   DD   SYSOUT=A
//*
//STEP020  EXEC PGM=SYK011,COND=(4,LT,STEP010)
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//BACKREF  DD   DSN=*.STEP999.OUT1,DISP=SHR
//*
//STEP030  EXEC SYKPRC02,CYCLE=&CYCLE,COND=(4,LT,STEP010)
//BADSTEP.SYSIN DD DUMMY
//*
//STEP040  EXEC SYKPRC99,COND=(4,LT,STEP010)
