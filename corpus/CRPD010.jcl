//CRPD010  JOB  (CRP1234),'CRP01 入庫日次',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CRPD010 : 入庫データ検証・商品マスタ更新　日次バッチ         *
//*    STEP010 : 入庫データ検証(CRP001)                          *
//*              CRPIN(入庫日次ファイル) を読み、明細検証を行い   *
//*              CRPVALID(正常分)／CRPERR(異常分) へ振り分ける    *
//*    STEP020 : 商品マスタ更新(CRP002)                          *
//*              商品トランザクション(CRPTRAN)を読み、VSAM KSDS   *
//*              商品マスタ(CRPMSTR)へ登録・更新・削除する        *
//*-------------------------------------------------------------*
//         SET CYCLE=250901
//*
//STEP010  EXEC PGM=CRP001,PARM='&CYCLE'
//STEPLIB  DD   DSN=CRP.PROD.LOADLIB,DISP=SHR
//CRPIN    DD   DSN=CRPT.D&CYCLE..NYUKO.DAILY,DISP=SHR
//CRPVALID DD   DSN=CRPW.D&CYCLE..NYUKO.VALID,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(5,5),RLSE),
//             DCB=(RECFM=FB,LRECL=253,BLKSIZE=0)
//CRPERR   DD   DSN=CRPW.D&CYCLE..NYUKO.ERROR,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(2,2),RLSE),
//             DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//*
//STEP020  EXEC PGM=CRP002,COND=(4,LT,STEP010)
//STEPLIB  DD   DSN=CRP.PROD.LOADLIB,DISP=SHR
//CRPTRAN  DD   DSN=CRPT.D&CYCLE..SHOHIN.TRAN,DISP=SHR
//CRPMSTR  DD   DSN=CRPV.SHOHIN.MASTER,DISP=SHR
//SYSOUT   DD   SYSOUT=*
