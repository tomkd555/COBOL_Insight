//SYKD010  JOB  (SYK1234),'SYK01 受注日次',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  SYKD010 : 受注データ検証・登録　日次バッチ                  *
//*    STEP010 : 受注データ検証(SYK001)                          *
//*              ORDIN(受注日次ファイル) を読み、明細検証を行い   *
//*              ORDVALID(正常分)／ORDERR(異常分) へ振り分ける    *
//*    STEP020 : 受注データ登録(SYK002)                          *
//*              STEP010が出力したORDVALIDを読み、VSAM KSDS       *
//*              受注マスタ(ORDMSTR)へ登録・更新する              *
//*-------------------------------------------------------------*
//         SET CYCLE=250718
//*
//STEP010  EXEC PGM=SYK001,PARM='&CYCLE'
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//ORDIN    DD   DSN=SYKT.D&CYCLE..ORDER.DAILY,DISP=SHR
//ORDVALID DD   DSN=SYKW.D&CYCLE..ORDER.VALID,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=253,BLKSIZE=0)
//ORDERR   DD   DSN=SYKW.D&CYCLE..ORDER.ERROR,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//*
//STEP020  EXEC PGM=SYK002,COND=(4,LT,STEP010)
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//ORDVALID DD   DSN=SYKW.D&CYCLE..ORDER.VALID,DISP=SHR
//ORDMSTR  DD   DSN=SYKV.ORDER.MASTER,DISP=SHR
//SYSOUT   DD   SYSOUT=*
