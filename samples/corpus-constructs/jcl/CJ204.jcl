//CJ204    JOB  (CJ0001),'CJ SPACE FORMS',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ204 : SPACE parameter forms
//*   TRK, CYL and average-block-length quantities, a secondary
//*   quantity, a directory-block quantity, RLSE, CONTIG, ROUND,
//*   and AVGREC=K paired with a record-count SPACE.
//*-----------------------------------------------------------------
//STEP010  EXEC PGM=IEFBR14
//TRKSP    DD DSN=FLW.CJ204.TRK,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(TRK,(10,5),RLSE)
//CYLSP    DD DSN=FLW.CJ204.CYL,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(CYL,(5,2),RLSE)
//BLKSP    DD DSN=FLW.CJ204.BLK,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(800,(100,50))
//DIRSP    DD DSN=FLW.CJ204.PDS,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(TRK,(10,5,20),RLSE),
//            DCB=(RECFM=FB,LRECL=80,BLKSIZE=0,DSORG=PO)
//CTGSP    DD DSN=FLW.CJ204.CONTIG,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(CYL,(5,2),,CONTIG)
//RNDSP    DD DSN=FLW.CJ204.ROUND,DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,SPACE=(800,(100,50),,,ROUND)
//AVGSP    DD DSN=FLW.CJ204.AVGREC,
//            DISP=(NEW,CATLG,DELETE),
//            UNIT=SYSDA,AVGREC=K,SPACE=(80,(500,100))
//
