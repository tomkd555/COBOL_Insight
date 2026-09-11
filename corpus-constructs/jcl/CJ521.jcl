//CJ521    JOB  (CJ0001),'JES3 ROUTE XEQ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ521 : //*ROUTE XEQ, the JES3 spelling (leading //*, not   *
//*    /*). IBM requires an MVS JOB statement immediately after  *
//*    it; JES3 then transmits everything from that JOB          *
//*    statement up to the next JOB statement or end of input to *
//*    the named node for execution there. Split out of CJ502.jcl*
//*    because it cannot coexist with the rest of a local job's   *
//*    steps.                                                     *
//*-------------------------------------------------------------*
//*ROUTE XEQ SY2
//CJ521R   JOB  (CJ0001),'REMOTE EXEC AT SY2',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//STEP010  EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
