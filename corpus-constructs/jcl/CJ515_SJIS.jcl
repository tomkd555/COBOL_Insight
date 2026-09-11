//CJ515    JOB  (CJ0001),'DBCS COMMENT AND PARM',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ515 : constructs exercised                                *
//*    - DBCS text in a JCL comment (Japanese month-end close)   *
//*    - DBCS text inside a quoted PARM value                    *
//*-------------------------------------------------------------*
//* åééüí˜Çﬂèàóù
//STEP010  EXEC PGM=IEFBR14,PARM='í˜Çﬂ'
//SYSPRINT DD   SYSOUT=*
//
