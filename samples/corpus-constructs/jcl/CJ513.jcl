//CJ513    JOB  (CJ0001),'CRLF LINE ENDINGS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ513 : constructs exercised                                *
//*    - every physical line of this member ends CRLF, not LF    *
//*-------------------------------------------------------------*
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
