//CJ422    JOB  (CJ0001),'DFSRRC00 IMS BMP',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ422 : constructs exercised                                *
//*    - DFSRRC00 IMS batch region controller                    *
//*    - PARM=(DLI,program,psb,...) positional parameter list    *
//*    - IMS PSBLIB/DBDLIB and DL/I log DDs                       *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=DFSRRC00,
//             PARM=(DLI,CCP008,PSB001,7,0000,,0,,N,0,T,,,,,)
//STEPLIB  DD   DSN=IMS.PROD.SDFSRESL,DISP=SHR
//         DD   DSN=CCP.PROD.LOADLIB,DISP=SHR
//IEFRDER  DD   DUMMY
//DFSRESLB DD   DSN=IMS.PROD.SDFSRESL,DISP=SHR
//DFSVSAMP DD   DSN=IMS.PROD.VSAMPARM,DISP=SHR
//IMS      DD   DSN=IMS.PROD.PSBLIB,DISP=SHR
//         DD   DSN=IMS.PROD.DBDLIB,DISP=SHR
//SYSPRINT DD   SYSOUT=*
//SYSUDUMP DD   SYSOUT=*
