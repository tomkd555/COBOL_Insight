//FLJ020   JOB  (FL0001),'FL 月次請求',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,RESTART=STEP010
/*JOBPARM LINES=9999
//*-------------------------------------------------------------*
//*  FLJ020 : 月次請求ジョブ                                     *
//*    STEP010 : 請求書作成(FLB030)                              *
//*    STEP020 : 請求書の再発行(FLB0301)                         *
//*    STEP030 : 入金データ編集・督促リスト作成(FLP010)          *
//*  前回の打ち切りから再開するため RESTART を付けています。     *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(FL.PROD.PROCLIB)
//JOBLIB   DD DSN=FL.PROD.LOADLIB,DISP=SHR
//         SET CYCLE=250901,HLQ=FLM
//*
//*  契約マスタから当月分の請求書を作ります。
//STEP010  EXEC PGM=FLB030,PARM='&CYCLE'
//KEIMST   DD DSN=FLM.KEIYAKU.MASTER,DISP=SHR
//SEIKYU   DD DSN=&HLQ..D&CYCLE..SEIKYU.PRINT,
//            DISP=(,CATLG,DELETE),
//            SPACE=(CYL,(5,5),RLSE),
//            DCB=(RECFM=FB,LRECL=132,BLKSIZE=0)
//KEIUPD   DD DSN=&HLQ..D&CYCLE..SEIKYU.JISSEKI,
//            DISP=(,CATLG,DELETE),
//            SPACE=(CYL,(2,1),RLSE),
//            DCB=(RECFM=FB,LRECL=100,BLKSIZE=0)
//SYSOUT   DD SYSOUT=*
//*
//*  返送分の請求書を再発行します。
//STEP020  EXEC PGM=FLB0301,COND=(0,NE)
//KEIMST   DD DSN=FLM.KEIYAKU.MASTER,DISP=SHR
//SEIKYU   DD DSN=&HLQ..D&CYCLE..SEIKYU.SAIHAKKO,
//            DISP=(NEW,CATLG,DELETE),
//            SPACE=(CYL,(5,5),RLSE),
//            DCB=(RECFM=FB,LRECL=132,BLKSIZE=0)
//KEIUPD   DD DSN=&HLQ..D&CYCLE..SEIKYU.SAIJISSEKI,
//            DISP=(NEW,CATLG,DELETE),
//            SPACE=(CYL,(2,1),RLSE),
//            DCB=(RECFM=FB,LRECL=100,BLKSIZE=0)
//SYSOUT   DD SYSOUT=*
//*
//*  月次でも入金データ編集と督促リスト作成を通します。
//STEP030  EXEC FLP010,CYCLE=&CYCLE,HLQ=FLM,COND=(4,LT)
//
