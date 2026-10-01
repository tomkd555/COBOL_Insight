//CJ205    JOB  (CJ0001),'CJ UNIT VOL FORMS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ205 : UNIT and VOL parameter forms
//*   UNIT=esoteric, UNIT=(esoteric,count), UNIT=AFF=ddname,
//*   UNIT=device-type, VOL=SER=, VOL=(,RETAIN,,count),
//*   VOL=REF=*.step.ddname, and VOL=PRIVATE.
//*-----------------------------------------------------------------
//STEP1    EXEC PGM=IEFBR14
//DD1      DD DSN=FLW.CJ205.BASE,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,VOL=SER=WORK01,
//            SPACE=(TRK,(5,1),RLSE)
//UNITMU   DD DSN=FLW.CJ205.MULTI,DISP=(NEW,CATLG,DELETE),
//            UNIT=(SYSDA,3),SPACE=(CYL,(5,2),RLSE)
//UNITAF   DD DSN=FLW.CJ205.AFFIN,DISP=(NEW,CATLG,DELETE),
//            UNIT=AFF=DD1,SPACE=(TRK,(5,1),RLSE)
//UNITDV   DD DSN=FLW.CJ205.DEVTYP,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=3390,SPACE=(TRK,(5,1),RLSE)
//VOLRET   DD DSN=FLW.CJ205.RETAIN,DISP=(OLD,KEEP),
//            UNIT=SYSDA,VOL=(,RETAIN,,3)
//STEP2    EXEC PGM=IEFBR14,COND=(4,LT)
//VOLREF   DD DSN=FLW.CJ205.REFVOL,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,VOL=REF=*.STEP1.DD1,
//            SPACE=(TRK,(5,1),RLSE)
//VOLPRV   DD DSN=FLW.CJ205.PRVOL,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,VOL=PRIVATE,
//            SPACE=(TRK,(5,1),RLSE)
//
