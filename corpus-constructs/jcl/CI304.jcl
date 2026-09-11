//*-------------------------------------------------------------*
//*  CI304 : INCLUDE member holding a step. IBM's "Considerations *
//*          for using INCLUDE groups" bars defining a PROC        *
//*          inside the group but allows an EXEC statement that    *
//*          invokes one, so this member adds STEP005, calling     *
//*          the catalogued CP303, at job level.                   *
//*-------------------------------------------------------------*
//STEP005  EXEC CP303,CYCLE=250901
