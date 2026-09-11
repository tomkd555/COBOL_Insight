//CJ509    JOB  (CJ0001),'SEQUENCE NUMBERED SOURCE',CLASS=A,            00000010
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID                 00000020
//*-------------------------------------------------------------*       00000030
//*  CJ509 : constructs exercised                                *      00000040
//*    - every physical line of this member carries an 8-digit   *      00000050
//*    - sequence number in columns 73-80                        *      00000060
//*-------------------------------------------------------------*       00000070
//STEP010   EXEC PGM=IEFBR14                                            00000080
//SYSPRINT DD   SYSOUT=*                                                00000090
//                                                                      00000100
