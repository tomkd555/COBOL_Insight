//CJ105    JOB  (CJ01),'CJ クロウ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ105 : EXEC statement parameters                            *
//*    PARM (quoted, comma-separated, doubled quote, continued    *
//*    to column 16), ACCT, ADDRSPC, COND, DYNAMNBR, PERFORM,     *
//*    RD, REGION, TIME, MEMLIMIT, CCSID, TVSMSG.                 *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CCP006
//SYSOUT   DD   SYSOUT=*
//STEP020  EXEC PGM=CCP008,PARM='CYCLE=250911,MODE=''BATCH'',DEBUG=N,LOX
//             GLEVEL=INFO,RETRY=3,OUTPUT=SUMMARY,VERBOSE=Y,STATUS=OK',
//             ACCT=(D250911,'BATCH'),ADDRSPC=VIRT,COND=(4,LT,STEP010),
//             DYNAMNBR=25,PERFORM=5,RD=RNC,REGION=4M,TIME=(1,30),
//             MEMLIMIT=1G,CCSID=1047,TVSMSG=COMMIT
//SYSOUT   DD   SYSOUT=*
//
