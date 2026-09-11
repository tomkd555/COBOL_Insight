//*%OPC SCAN
//CJ516    JOB  (CJ0001),'IWS OPC SCHEDULER MARKERS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*%OPC SETVAR TDATE=(OYMD1)
//*-------------------------------------------------------------*
//*  CJ516 : constructs exercised                                *
//*    - //*%OPC SCAN and SETVAR, read by the controller before  *
//*      submit                                                  *
//*    - //*%OPC BEGIN/END ACTION=INCLUDE wrapping a conditional *
//*      DD                                                      *
//*    - the IWS-supplied variable &OYMD1 used inline in a DSN   *
//*    - //*>OPC, the controller-rewritten form of an already-   *
//*      resolved directive from a prior processing pass         *
//*-------------------------------------------------------------*
//*>OPC SETVAR RUNDATE=D2026254
//*%OPC BEGIN ACTION=INCLUDE,COMP=(&TDATE..EQ.(&OYMD1))
//STEP010  EXEC PGM=IEFBR14
//OUTDS    DD   DSN=MY.DS.D&OYMD1,DISP=SHR
//SYSPRINT DD   SYSOUT=*
//*%OPC END ACTION=INCLUDE
//
