//CJ502    JOB  (CJ0001),'JES3 JECL STATEMENTS',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*NET NETID=BATCH01,NHOLD=2
//*PROCESS CI
//*ENDPROCESS
//*DATASET DDNAME=SYSIN,J=YES
 CJ502 IN-STREAM DATA FOR THE SYSIN DATASET DSP
//*ENDDATASET
//*OPERATOR MOUNT TAPE
//*MAIN SYSTEM=SY1,LINES=500,CLASS=A
//*FORMAT PR,DDNAME=SYSPRINT,DEST=PRT1
//*-------------------------------------------------------------*
//*  CJ502 : constructs exercised                                *
//*    - //*NET NETID=/NHOLD= (DJC network; NHOLD is the         *
//*      predecessor-completion count, not DEPEND=)               *
//*    - //*PROCESS/*ENDPROCESS bracketing a CI DSP identifier,  *
//*      placed right after //*NET, the only statement IBM lets  *
//*      intervene between the JOB card and //*PROCESS            *
//*    - //*DATASET/*ENDDATASET wrapping in-stream data for a DSP*
//*    - //*OPERATOR, a free-text operator instruction           *
//*    - //*MAIN and //*FORMAT, placed after //*ENDPROCESS, as   *
//*      IBM's own JES3 chapter Example 1 does                   *
//*    - //*ROUTE XEQ, the JES3 spelling, is exercised in its     *
//*      own file, CJ521.jcl: it requires an MVS JOB statement    *
//*      immediately after it and would divert the rest of this  *
//*      job's stream to a remote node                            *
//*-------------------------------------------------------------*
//STEP010   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
//
