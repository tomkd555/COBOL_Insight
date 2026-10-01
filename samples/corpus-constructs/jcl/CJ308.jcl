//CJ308    JOB  (CJ0001),'CJ INCLUDE MBRS',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ308 : INCLUDE MEMBER=.                                     *
//*    SETGRP  : job level, brings in SET statements (CI302).    *
//*    STEPGRP : job level, brings in a step (CI304, EXEC CP303) *
//*              -- an INCLUDE group may not define a PROC but   *
//*              may hold an EXEC that invokes one.                *
//*    STEP010 : job level, adds DD statements (CI301) to a step *
//*              that lives directly in this job's own JCL.       *
//*    STEP020 : after a step, brings in CI303 (MEMBER= given    *
//*              symbolically); CI303's own DD is qualified       *
//*              STEP1.RPTDD, adding a DD to the called PROC's   *
//*              first step. The INCLUDE statement's own name     *
//*              is just a label, never an override target.       *
//*-------------------------------------------------------------*
//         JCLLIB ORDER=(CJ.PROD.PROCLIB)
//         SET DDMBR=CI303
//SETGRP   INCLUDE MEMBER=CI302
//STEPGRP  INCLUDE MEMBER=CI304
//*
//STEP010  EXEC PGM=CCP009,PARM='ENV=&ENV,RUNID=&RUNID'
//STEPLIB  DD   DSN=CJ.PROD.LOADLIB,DISP=SHR
//IN1      DD   DSN=CJT.D250901.CJ308.IN1,DISP=SHR
//INCGRP   INCLUDE MEMBER=CI301
//*
//STEP020  EXEC CP303,CYCLE=250901,COND=(0,NE,STEP010)
//INCADD   INCLUDE MEMBER=&DDMBR
