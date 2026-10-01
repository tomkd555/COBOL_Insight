//CJ304    JOB  (CJ0001),'CJ NONAME PROC',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ304 : calls CP303, whose PROC statement carries no name;  *
//*          the EXEC still names the member, CP303.             *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(CJ.PROD.PROCLIB)
//         SET CYCLE=250901
//*
//STEP010  EXEC CP303,CYCLE=&CYCLE
