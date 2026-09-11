//CJ102    JOB  (CJ02),'CJ ハナコ',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID,TIME=(,30),
//             PRTY=9,USER=CJUSR01,GROUP=CJGRP01,
//             SCHENV=CJSCENV1,LINES=(20,WARNING),BYTES=(2000,WARNING),
//             JOBRC=LASTRC,MEMLIMIT=2G,CCSID=1047
//*-------------------------------------------------------------*
//*  CJ102 : JOB statement common parameters (set 2)            *
//*    TIME=(,30), PRTY, USER, GROUP, SCHENV, LINES, BYTES,     *
//*    JOBRC, MEMLIMIT, CCSID.                                  *
//*    PASSWORD is omitted: GROUP requires only USER on the     *
//*    JOB statement (JCL Reference, USER parameter); PASSWORD  *
//*    becomes mandatory only for specific installation         *
//*    scenarios (RACF-protected access, cross-user submission),*
//*    not assumed here.                                        *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=CCP007
//SYSOUT   DD   SYSOUT=*
//
