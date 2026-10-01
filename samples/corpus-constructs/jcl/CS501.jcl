)CM ------------------------------------------------------------
)CM  CS501 : ISPF file-tailoring skeleton
)CM    - )SEL / )ENDSEL   selects the JOB card by &ZENV
)CM    - )SET             assigns a dialog variable
)CM    - )DOT / )ENDDOT   repeats a step block over a table
)CM    - )IM              imbeds another skeleton member
)CM    - &ZUSER           an ISPF dialog variable, not a JCL
)CM                       symbol
)CM ------------------------------------------------------------
)SEL &ZENV = 'PROD'
//&ZUSER.J  JOB  (CJ0001),'&ZUSER PROD RUN',CLASS=A,MSGCLASS=X
)ENDSEL
)SEL &ZENV = 'TEST'
//&ZUSER.J  JOB  (CJ0001),'&ZUSER TEST RUN',CLASS=A,MSGCLASS=T
)ENDSEL
)SET &X = 1
)DOT &STEPTAB
//STEP0&X   EXEC PGM=IEFBR14
//SYSPRINT DD   SYSOUT=*
)SET &X = &X + 1
)ENDDOT
)IM CI301
//
