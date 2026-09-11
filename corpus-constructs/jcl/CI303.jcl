//*-------------------------------------------------------------*
//*  CI303 : INCLUDE member, one DD statement. The DD itself is  *
//*          qualified STEP1.RPTDD, so bringing this member in   *
//*          after a step's EXEC (via a symbolic MEMBER=&DDMBR)  *
//*          adds RPTDD to the called PROC's first step.         *
//*-------------------------------------------------------------*
//STEP1.RPTDD DD   SYSOUT=*
