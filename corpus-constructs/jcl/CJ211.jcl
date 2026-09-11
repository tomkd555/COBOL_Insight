//CJ211    JOB  (CJ0001),'CJ INSTREAM DATA',CLASS=A,
//             MSGCLASS=X,MSGLEVEL=(1,1),NOTIFY=&SYSUID
//*-----------------------------------------------------------------
//* CJ211 : in-stream data delimiters
//*   DD * with plain data, DD DATA whose data starts a line with
//*   // without ending the data, DD *,DLM=@@ whose data starts a
//*   line with /* without ending the data, and DD
//*   DATA,SYMBOLS=JCLONLY together with SET and EXPORT SYMLIST
//*   substituting a symbol into in-stream data.
//*-----------------------------------------------------------------
//         SET HLQ=FLW
//         EXPORT SYMLIST=(HLQ)
//STEP010  EXEC PGM=IEFBR14
//SYSIN    DD *
PLAIN IN-STREAM DATA LINE 1
PLAIN IN-STREAM DATA LINE 2
/*
//CTLCARD  DD DATA
//THIS-LOOKS-LIKE-A-DD-STATEMENT DD DSN=SAMPLE,DISP=SHR
   A DELIMITER SUCH AS /* CAN APPEAR MID-LINE TOO
END OF CTLCARD DATA
/*
//DLMCARD  DD *,DLM=@@
/* THIS LINE WOULD END DEFAULT DD * BUT DLM=@@ IS IN EFFECT
ANOTHER ORDINARY DATA LINE
@@
//SYMCARD  DD DATA,SYMBOLS=JCLONLY
DATASET PREFIX IS &HLQ..CJ211.WORK
/*
//
