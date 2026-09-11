//CJ207    JOB  (CJ0001),'CJ DD LIFECYCLE',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ207 : DD-level access and lifecycle parameters
//*   DDNAME=name forward reference resolved by a later DD of the
//*   same name, AMP=, RLS=, SUBSYS=, FREE=CLOSE, SPIN=UNALLOC,
//*   and CHKPT=EOV.
//*-----------------------------------------------------------------
//STEP010  EXEC PGM=IEFBR14
//FWDREF   DD DDNAME=LATER
//AMPDS    DD DSN=FLW.CJ207.VSAM,DISP=SHR,
//            AMP=('BUFND=10','BUFNI=5')
//RLSDS    DD DSN=FLW.CJ207.RLSVSM,DISP=SHR,RLS=CR
//IN1      DD DSN=FLW.CJ207.IN1DS,DISP=SHR
//SUBSYSDS DD SUBSYS=(BLSR,'DDNAME=IN1')
//FREEDS   DD DSN=FLW.CJ207.FREECLS,DISP=SHR,FREE=CLOSE
//SPINDS   DD SYSOUT=*,SPIN=UNALLOC
//CHKPTDS  DD DSN=FLW.CJ207.CHKPT,DISP=SHR,CHKPT=EOV
//LATER    DD DSN=FLW.CJ207.LATER,DISP=SHR
//
