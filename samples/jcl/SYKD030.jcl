//SYKD030  JOB  (SYK1234),'SYK03 受注再処理',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  SYKD030 : 受注データ　エラー分再処理バッチ（リラン専用）      *
//*    前提 : SYKD010 STEP010 が出力したORDERR(異常分)を対象に、  *
//*           内容訂正後の再検証・再登録を行う。                  *
//*    STEP010 : 受注データ再検証(SYK001、PARM の二番目の項目      *
//*              としてリランモード RERUN を渡す)                 *
//*    STEP020 : 受注データ登録(SYK002)                          *
//*-------------------------------------------------------------*
//         SET CYCLE=250718
//*
//STEP010  EXEC PGM=SYK001,PARM='&CYCLE,RERUN'
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//ORDIN    DD   DSN=SYKW.D&CYCLE..ORDER.ERROR,DISP=SHR
//ORDVALID DD   DSN=SYKW.D&CYCLE..ORDER.RERUN.VALID,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             DCB=(RECFM=FB,LRECL=253,BLKSIZE=0)
//ORDERR   DD   DSN=SYKW.D&CYCLE..ORDER.RERUN.ERROR,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(1,1),RLSE),
//             DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//*
//STEP020  EXEC PGM=SYK002,COND=(4,LT,STEP010)
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//ORDVALID DD   DSN=SYKW.D&CYCLE..ORDER.RERUN.VALID,DISP=SHR
//ORDMSTR  DD   DSN=SYKV.ORDER.MASTER,DISP=SHR
//SYSOUT   DD   SYSOUT=*
