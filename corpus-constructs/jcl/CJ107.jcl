//CJ107    JOB  (CJ01),'CJ ロクロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ107 : DD statement forms                                  *
//*    SYSOUT=*, SYSOUT=(A,,STD), SYSOUT=(,INTRDR), DUMMY,        *
//*    DSN=NULLFILE, OUTPUT statement referenced by               *
//*    SYSOUT=*,OUTPUT=*.name.                                    *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CCP006
//SYSPRINT DD   SYSOUT=*
//PRTOUT   DD   SYSOUT=(A,,STD)
//RDROUT   DD   SYSOUT=(,INTRDR)
//DUMMYIN  DD   DUMMY
//NULLOUT  DD   DSN=NULLFILE
//OUT1     OUTPUT DEST=RMT3,COPIES=2
//RPTOUT   DD   SYSOUT=*,OUTPUT=*.OUT1
//
