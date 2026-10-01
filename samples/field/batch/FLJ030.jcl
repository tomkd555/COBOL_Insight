//FLJ030   JOB  (FL0001),'FL 月次バッチ検証',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
/*JOBPARM LINES=9999
//*-------------------------------------------------------------*
//*  FLJ030 : ルール検証用ジョブ(R046・R047)                     *
//*    STEP010 : 入金データ編集(FLB010)                          *
//*    STEP020 : 入金データ編集(FLB010)。STEP999 は存在しない    *
//*    STEP030 : 入金データ編集(FLB010)。正常時だけ実行する      *
//*    STEP040 : 入金データ編集(FLB010)。最終ステップ            *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(FL.PROD.PROCLIB)
//JOBLIB   DD DSN=FL.PROD.LOADLIB,DISP=SHR
//*
//*  先行ステップがないため COND は不要です。
//STEP010  EXEC PGM=FLB010
//SYSOUT   DD SYSOUT=*
//*
//*  STEP999 は存在しません。未定義ステップへの参照です。
//STEP020  EXEC PGM=FLB010,COND=(4,LT,STEP999)
//SYSOUT   DD SYSOUT=*
//*
//*  STEP010 が正常終了したときだけ実行する、通常の COND です。
//STEP030  EXEC PGM=FLB010,COND=(0,NE)
//SYSOUT   DD SYSOUT=*
//*
//*  最終ステップです。COND=(0,EQ) は先行ステップが正常終了した
//*  とき真になり、このステップ自体が読み飛ばされます。
//STEP040  EXEC PGM=FLB010,COND=(0,EQ)
//SYSOUT   DD SYSOUT=*
//
