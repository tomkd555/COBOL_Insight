//CJ507    JOB  (CJ0001),'NOTIFY STATEMENT',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1)
//*-------------------------------------------------------------*
//*  CJ507 : constructs exercised                                *
//*    - a NOTIFY statement, EMAIL=/WHEN=, independent of the JOB*
//*      statement's own NOTIFY= keyword. WHEN= takes a quoted,  *
//*      parenthesised condition such as '(ABEND)', not a bare,  *
//*      unquoted list.                                           *
//*-------------------------------------------------------------*
//N1       NOTIFY EMAIL='user@example.com',WHEN='(ABEND)'
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
