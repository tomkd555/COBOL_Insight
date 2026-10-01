//CJ517    JOB  (CJ0001),'CONTROL-M AUTOEDIT MARKERS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//* %%SET %%A=%%ODATE
//* %%LIBSYM CTM.PROD.SYMBOLS
//*-------------------------------------------------------------*
//*  CJ517 : constructs exercised                                *
//*    - //* %%SET and %%LIBSYM AutoEdit statements              *
//*    - //* %%IF/%%ENDIF bracketing a step                      *
//*    - %%ODATE substituted inline in a DSN, back to back with  *
//*      text                                                    *
//*    - //* %%CALCDATE, another AutoEdit function               *
//*    - the $ODATE and $OJULDAY sigil forms, also inline in a   *
//*      DSN                                                     *
//*-------------------------------------------------------------*
//* %%IF %%A EQ 20260101
//STEP010  EXEC PGM=IEFBR14
//OUTDS    DD   DSN=PREFIX.D%%ODATE,DISP=SHR
//JULDS    DD   DSN=PREFIX.$OJULDAY.DAILY,DISP=SHR
//DATEDS   DD   DSN=PREFIX.$ODATE.DAILY,DISP=SHR
//SYSPRINT DD   SYSOUT=*
//* %%ENDIF
//* %%CALCDATE %%A,+1,%%B
//
