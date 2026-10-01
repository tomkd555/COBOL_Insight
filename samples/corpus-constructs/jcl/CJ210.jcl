//CJ210    JOB  (CJ0001),'CJ OUTPUT STMT',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ210 : OUTPUT statement and SYSOUT form-control keywords
//*   A job-level OUTPUT DEFAULT=YES, a step-level OUTPUT pair
//*   referenced from one SYSOUT DD via OUTPUT=(*.OUT1,*.OUT2),
//*   and BURST/CHARS/FCB/UCS/MODIFY coded on a SYSOUT DD.
//*-----------------------------------------------------------------
//DFLTOUT  OUTPUT DEFAULT=YES,CLASS=A,COPIES=1,FORMS=STD
//STEP010  EXEC PGM=IEFBR14
//OUT1     OUTPUT OUTDISP=(WRITE,HOLD),DEST=RMT3,COPIES=2,
//            FORMS=INV01,CLASS=A
//OUT2     OUTPUT OUTDISP=(WRITE,PURGE),DEST=LOCAL,
//            COPIES=1,FORMS=STD,CLASS=A
//SYSPRINT DD SYSOUT=*,OUTPUT=(*.OUT1,*.OUT2)
//FORMPRT  DD SYSOUT=A,BURST=YES,CHARS=GT12,FCB=STD3,
//            UCS=PN,MODIFY=(FRM1,1)
//
