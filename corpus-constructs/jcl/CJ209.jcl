//CJ209    JOB  (CJ0001),'CJ DD CONCAT',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ209 : concatenation of DD statements
//*   A named DD followed by unnamed DDs, mixing a DUMMY DD into
//*   the middle of the concatenation and varying DISP across the
//*   members.
//*-----------------------------------------------------------------
//STEP010  EXEC PGM=IEFBR14
//CONCATIN DD DSN=FLW.CJ209.PART1,DISP=SHR
//         DD DSN=FLW.CJ209.PART2,DISP=OLD
//         DD DUMMY
//         DD DSN=FLW.CJ209.PART4,DISP=(OLD,KEEP)
//
