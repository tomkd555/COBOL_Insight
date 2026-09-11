//cj510    job  (cj0001),'lowercase jcl demo',class=a,
//             msgclass=x,msglevel=(1,1),notify=&sysuid
//*-------------------------------------------------------------*
//*  CJ510 : constructs exercised                                *
//*    - every unquoted operation and operand folded to lowercase*
//*      (//step010 exec pgm=ccp001); a quoted string keeps its  *
//*      written case even while the surrounding JCL is lowercase*
//*-------------------------------------------------------------*
//step010  exec pgm=ccp001,parm='Mixed Case Parm'
//infile   dd   dummy
//outfile  dd   dummy
//
