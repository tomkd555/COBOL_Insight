//CJ512    JOB  (CJ0001),'TRAILING AND INLINE COMMENTS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ512 : constructs exercised                                *
//*    - a trailing comment after a single blank following the   *
//*      last operand                                            *
//*    - a //* comment statement placed between the continuation *
//*      lines of a DD statement                                 *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=* TRAILING COMMENT AFTER LAST OPERAND
//INDD1    DD   DSN=CJT.CJ512.CONTINUATION.DEMO.NAME,
//*  a comment statement placed between continuation lines
//             DISP=SHR
//
