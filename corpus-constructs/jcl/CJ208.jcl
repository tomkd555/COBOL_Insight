//CJ208    JOB  (CJ0001),'CJ UNIX FILE DD',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ208 : z/OS UNIX file DD parameters
//*   PATH=, PATHOPTS=, PATHMODE=, PATHDISP=, and FILEDATA= on
//*   one DD naming a byte-stream file in the file system.
//*-----------------------------------------------------------------
//STEP010  EXEC PGM=IEFBR14
//UNIXOUT  DD PATH='/u/prod/out.dat',
//            PATHOPTS=(OWRONLY,OCREAT,OTRUNC),
//            PATHMODE=(SIRUSR,SIWUSR),
//            PATHDISP=(KEEP,DELETE),
//            FILEDATA=TEXT
//
