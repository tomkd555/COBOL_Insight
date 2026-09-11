//CJ203    JOB  (CJ0001),'CJ DCB FORMS',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ203 : DCB parameter forms
//*   DCB=(RECFM=...,DSORG=PS) coded in full, a plain DCB
//*   referback, a DCB referback merged with a literal
//*   subparameter, DCB=MODEL.DSCB, and RECFM/LRECL/BLKSIZE coded
//*   directly on the DD instead of inside DCB=.
//*-----------------------------------------------------------------
//STEP1    EXEC PGM=IEFBR14
//DD1      DD DSN=FLW.CJ203.BASE,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(TRK,(5,1),RLSE),
//            DCB=(RECFM=FB,LRECL=80,BLKSIZE=0,DSORG=PS)
//STEP2    EXEC PGM=IEFBR14,COND=(4,LT)
//DD2      DD DSN=FLW.CJ203.REFBACK,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(TRK,(5,1),RLSE),
//            DCB=*.STEP1.DD1
//DD3      DD DSN=FLW.CJ203.REFBACK2,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(TRK,(5,1),RLSE),
//            DCB=(*.STEP1.DD1,LRECL=133)
//MODELDS  DD DSN=FLW.CJ203.MODELED,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(TRK,(5,1),RLSE),
//            DCB=MODEL.DSCB
//OUTSIDE  DD DSN=FLW.CJ203.OUTSIDE,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(TRK,(5,1),RLSE),
//            RECFM=FB,LRECL=100,BLKSIZE=0
//
