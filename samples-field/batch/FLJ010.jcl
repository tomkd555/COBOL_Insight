//FLJ010   JOB  (FL0001),'FL 日次入金消込',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,REGION=0M
/*JOBPARM LINES=9999
//*-------------------------------------------------------------*
//*  FLJ010 : 日次入金消込ジョブ                                 *
//*    STEP010 : 作業データセットの削除(IDCAMS)                  *
//*    STEP020 : 日次入金ファイルの整列(SORT)                    *
//*    STEP030 : 入金データ編集・督促リスト作成(FLP010)          *
//*    STEP035 : 異常フラグの作成(IEFBR14)                       *
//*              STEP030 の戻り値が 4 を超えたときだけ通ります   *
//*    STEP040 : 入金消込(IKJEFT01 経由で FLB020)                *
//*    STEP050 : 消込結果の世代退避(IEBGENER)                    *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(FL.PROD.PROCLIB)
//JOBLIB   DD DSN=FL.PROD.LOADLIB,DISP=SHR
//         SET CYCLE=250901,HLQ=FLW
//*
//*  作業データセットを削除します。
//STEP010  EXEC PGM=IDCAMS
//SYSPRINT DD SYSOUT=*
//SYSIN    DD *
  DELETE FLW.D250901.NYUKIN.SORTED
  SET MAXCC=0
/*
//*
//*  日次入金ファイルを契約番号順に整列します。
//STEP020  EXEC PGM=SORT,COND=(4,LT)
//SORTIN   DD DSN=FLT.D&CYCLE..NYUKIN.DAILY,DISP=SHR
//SORTOUT  DD DSN=&HLQ..D&CYCLE..NYUKIN.SORTED,
//            DISP=(NEW,CATLG,DELETE),
//            SPACE=(CYL,(10,5),RLSE),
//            DCB=(RECFM=VB,LRECL=266,BLKSIZE=0)
//SORTWK01 DD UNIT=SYSDA,SPACE=(CYL,(10,5))
//SYSOUT   DD SYSOUT=*
//SYSIN    DD *
  SORT FIELDS=(1,10,CH,A)
/*
//*
//*  入金データ編集と督促リスト作成をまとめて実行します。
//STEP030  EXEC FLP010,CYCLE=&CYCLE,HLQ=&HLQ,COND=(4,LT)
//STEP1.NYUKIN DD DSN=&HLQ..D&CYCLE..NYUKIN.SORTED,DISP=SHR
//*
//*  編集で警告以上が出たときは異常フラグだけを残して終えます。
//         IF (STEP030.RC > 4) THEN
//STEP035  EXEC PGM=IEFBR14
//ERRFLAG  DD DSN=&HLQ..D&CYCLE..NYUKIN.ERRFLAG,
//            DISP=(NEW,CATLG),
//            SPACE=(TRK,(1,1)),
//            DCB=(RECFM=FB,LRECL=80,BLKSIZE=0)
//         ELSE
//*  DB2 に接続して入金消込を実行します。
//STEP040  EXEC PGM=IKJEFT01,DYNAMNBR=20
//STEPLIB  DD DSN=FL.PROD.LOADLIB,DISP=SHR
//SYSTSPRT DD SYSOUT=*
//SYSPRINT DD SYSOUT=*
//SYSUDUMP DD SYSOUT=*
//SYSTSIN  DD *
  DSN SYSTEM(DB2P)
  RUN PROGRAM(FLB020) PLAN(FLPLAN1) LIB('FL.PROD.LOADLIB')
  END
/*
//         ENDIF
//*
//*  整列済みの入金ファイルを世代へ退避します。
//STEP050  EXEC PGM=IEBGENER
//SYSPRINT DD SYSOUT=*
//SYSUT1   DD DSN=&HLQ..D&CYCLE..NYUKIN.SORTED,DISP=SHR
//SYSUT2   DD DSN=FLT.NYUKIN.HISTORY(+1),
//            DISP=(NEW,CATLG,DELETE),
//            SPACE=(CYL,(10,5),RLSE),
//            DCB=(RECFM=VB,LRECL=266,BLKSIZE=0)
//SYSUT3   DD UNIT=SYSDA,SPACE=(CYL,(1,1))
//SYSIN    DD DUMMY
//
