//CJ519    PROC
//*-------------------------------------------------------------*
//*  CJ519 : constructs exercised                                *
//*    - ++INCLUDE, a Panvalet control statement pulling in      *
//*      another member before this one becomes real JCL         *
//*    - an 8-character Librarian version stamp in columns 73-80 *
//*-------------------------------------------------------------*
++INCLUDE STDPROC                                                       LB000100
//STEP010  EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
