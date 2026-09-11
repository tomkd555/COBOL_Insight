//CJ410    JOB  (CJ0001),'IDCAMS DEFINE CLUSTER',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-------------------------------------------------------------*
//*  CJ410 : constructs exercised (IDCAMS)                       *
//*    - DEFINE CLUSTER with DATA()/INDEX() subparameters        *
//*      (INDEXED, KEYS, RECORDSIZE, SHAREOPTIONS)                *
//*    - hyphen line continuation across many physical cards      *
//*    - DEFINE AIX (alternate index)                             *
//*    - DEFINE PATH connecting the base cluster to the AIX       *
//*-------------------------------------------------------------*
//STEP010  EXEC PGM=IDCAMS
//SYSPRINT DD   SYSOUT=*
//SYSIN    DD   *
  DEFINE CLUSTER (NAME(CJV.KEIYAKU.CLUSTER)                  -
                  INDEXED                                    -
                  KEYS(8 0)                                  -
                  RECORDSIZE(100 100)                        -
                  SHAREOPTIONS(2 3)                           -
                  VOLUMES(SYSDA1))                            -
         DATA (NAME(CJV.KEIYAKU.DATA)                        -
               CYLINDERS(10 5))                               -
         INDEX (NAME(CJV.KEIYAKU.INDEX)                      -
                CYLINDERS(1 1))
  DEFINE AIX (NAME(CJV.KEIYAKU.AIX)                           -
              RELATE(CJV.KEIYAKU.CLUSTER)                     -
              KEYS(5 8)                                       -
              NONUNIQUEKEY                                    -
              UPGRADE                                         -
              RECORDSIZE(20 20)                               -
              CYLINDERS(3 2))                                 -
         DATA (NAME(CJV.KEIYAKU.AIX.DATA))                    -
         INDEX (NAME(CJV.KEIYAKU.AIX.INDEX))
  DEFINE PATH (NAME(CJV.KEIYAKU.PATH)                          -
               PATHENTRY(CJV.KEIYAKU.AIX)                      -
               UPDATE)
/*
