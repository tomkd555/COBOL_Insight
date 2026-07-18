//SYKD020  JOB  (SYK1234),'SYK02 在庫更新',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  SYKD020 : 在庫更新・引当確定　日次バッチ                    *
//*    STEP010 : 在庫マスタ更新(SYK006、Db2)                     *
//*              STKIN(在庫日次トランザクション) を読み、Db2の    *
//*              在庫マスタ表を更新し、STKEXTR(抽出ファイル)へ    *
//*              カーソルで抽出結果を出力する                    *
//*    STEP020 : 在庫引当率算出・引当数量確定(SYK007、Db2)        *
//*              インストリームPROC SYKPRC01 経由で実行し、       *
//*              STEP010が出力したSTKEXTRを読み込む               *
//*-------------------------------------------------------------*
//         SET CYCLE=250718
//*
//SYKPRC01 PROC CYCLE=000000
//STEP020  EXEC PGM=SYK007
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//STKEXTR  DD   DSN=SYKW.D&CYCLE..STOCK.EXTRACT,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//         PEND
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=SYK006,PARM='&CYCLE'
//STEPLIB  DD   DSN=SYK.PROD.LOADLIB,DISP=SHR
//STKIN    DD   DSN=SYKT.D&CYCLE..STOCK.DAILY,DISP=SHR
//STKEXTR  DD   DSN=SYKW.D&CYCLE..STOCK.EXTRACT,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(3,3),RLSE),
//             DCB=(RECFM=FB,LRECL=029,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//*
//STEP020  EXEC SYKPRC01,CYCLE=&CYCLE
