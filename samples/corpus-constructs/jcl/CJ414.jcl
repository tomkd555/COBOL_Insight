//CJ414    JOB  (CJ0001),'IEBCOPY PDS COPY',CLASS=A,MSGCLASS=X,
//             MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ414 : constructs exercised (IEBCOPY)                      *
//*    - COPY INDD=ddname,OUTDD=ddname                           *
//*    - SELECT MEMBER=(name,name,(oldname,newname,R))            *
//*    - COPYMOD INDD=ddname,OUTDD=ddname                        *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IEBCOPY
//SYSPRINT DD   SYSOUT=*
//IN       DD   DSN=CJT.PROD.SRCLIB,DISP=SHR
//OUT      DD   DSN=CJT.TEST.SRCLIB,DISP=SHR
//SYSIN    DD   *
  COPY INDD=IN,OUTDD=OUT
  SELECT MEMBER=(MEMBA,MEMBB,(MEMBC,MEMBD,R))
/*
//*
//STEP020  EXEC PGM=IEBCOPY,COND=(4,LT,STEP010)
//SYSPRINT DD   SYSOUT=*
//IN2      DD   DSN=CJT.PROD.LOADLIB2,DISP=SHR
//OUT2     DD   DSN=CJT.TEST.LOADLIB2,DISP=SHR
//SYSIN    DD   *
  COPYMOD INDD=IN2,OUTDD=OUT2
/*
