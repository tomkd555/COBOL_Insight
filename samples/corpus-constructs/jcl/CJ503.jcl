//CJ503    JOB  (CJ0001),'XMIT STATEMENT',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//SYSUT2   XMIT DEST=NODE1.USER1,DLM=$$
//REMOTE1  JOB  (CJ0001),'PAYLOAD JOB',CLASS=A,MSGCLASS=X
//*-------------------------------------------------------------*
//*  CJ503 : constructs exercised                                *
//*    - an XMIT statement transmitting a JCL payload to a remote*
//*      node, the payload delimited by DLM=$$ instead of the    *
//*      default /*. XMIT must follow the JOB statement with no  *
//*      other MVS JCL statement between them, so this comment   *
//*      block lives inside the transmitted payload instead.     *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
$$
