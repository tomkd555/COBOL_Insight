//CJ520    PROC
//*-------------------------------------------------------------*
//*  CJ520 : constructs exercised                                *
//*    - &C1SYSTEM, &C1ELEMENT and &C1STAGE, the standard CA     *
//*      Endevor processor substitution symbols, doubled-period  *
//*      delimited                                               *
//*-------------------------------------------------------------*
//COMPILE  EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   DSN=&C1SYSTEM..&C1ELEMENT..SRC,DISP=SHR
//SYSLIN   DD   DSN=&C1SYSTEM..&C1STAGE..OBJLIB(&C1ELEMENT),
//             DISP=SHR
