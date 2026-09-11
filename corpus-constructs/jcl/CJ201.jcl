//CJ201    JOB  (CJ0001),'CJ DSN FORMS',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ201 : DSN parameter forms
//*   GDG relative generations (+1)/(0)/(-1), a temporary &&name
//*   dataset, a PDS member reference, a GDG absolute generation
//*   written in the same parenthesised form as a PDS member, a
//*   DSN using the special characters legal in a data set name,
//*   a quoted DSN, and a DSN operand long enough to need JCL
//*   continuation.
//*-----------------------------------------------------------------
//STEP010  EXEC PGM=IEFBR14
//NEWGEN   DD DSN=FLT.NYUKIN.GDG(+1),
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,
//            SPACE=(CYL,(5,2),RLSE),
//            DCB=(RECFM=FB,LRECL=200,BLKSIZE=0)
//CURGEN   DD DSN=FLT.NYUKIN.GDG(0),DISP=SHR
//PRVGEN   DD DSN=FLT.NYUKIN.GDG(-1),DISP=SHR
//WORKDS   DD DSN=&&TEMP,DISP=(NEW,DELETE),UNIT=SYSDA,
//            SPACE=(TRK,(5,5))
//MEMBER   DD DSN=FL.PROD.PROCLIB(MEMBER1),DISP=SHR
//GDGLIKE  DD DSN=FLT.HANBAI.JISSEKI(G0001V00),DISP=SHR
//SPCCHAR  DD DSN=FLW.WORK#01.DATA-A,DISP=SHR
//QUOTDSN  DD DSN='FLW.CJ201.A/B',DISP=SHR
//DSNLONG  DD DSN=FLW00001.NYUKIN01.SHORUI01.MEISAI01.DAILY01(MEMBER1),
//            DISP=SHR
//
