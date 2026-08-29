//CRPD020  JOB  (CRP1234),'CRP02 在庫更新',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CRPD020 : 在庫数更新・抽出　日次バッチ                      *
//*    STEP010 : 在庫数更新・抽出(CRP005、Db2)                   *
//*              CRPIN2(在庫トランザクション) を読み、Db2の       *
//*              在庫マスタ表を更新し、CRPEXTR(抽出ファイル)へ    *
//*              カーソルで抽出結果を出力する                    *
//*    STEP020 : STEP010が正常終了した場合だけIF/THENで実行する。 *
//*              インストリームPROC CRPPRC01 経由で商品区分コード *
//*              検証(CRP008)を実行する                          *
//*-------------------------------------------------------------*
//         SET CYCLE=250901
//*
//CRPPRC01 PROC CYCLE=000000
//STEP020  EXEC PGM=CRP008
//STEPLIB  DD   DSN=CRP.PROD.LOADLIB,DISP=SHR
//CRPCODE  DD   DSN=CRPT.D&CYCLE..SHOCD.DAILY,DISP=SHR
//SYSOUT   DD   SYSOUT=*
//         PEND
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CRP005,PARM='&CYCLE'
//STEPLIB  DD   DSN=CRP.PROD.LOADLIB,DISP=SHR
//CRPIN2   DD   DSN=CRPT.D&CYCLE..ZAIKO.TRAN,DISP=SHR
//CRPEXTR  DD   DSN=CRPW.D&CYCLE..ZAIKO.EXTRACT,
//             DISP=(NEW,CATLG,DELETE),
//             SPACE=(CYL,(3,3),RLSE),
//             DCB=(RECFM=FB,LRECL=029,BLKSIZE=0)
//SYSOUT   DD   SYSOUT=*
//*
//         IF (STEP010.RC = 0) THEN
//STEP020  EXEC CRPPRC01,CYCLE=&CYCLE
//         ENDIF
