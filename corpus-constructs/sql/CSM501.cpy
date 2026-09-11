      *----------------------------------------------------------------
      * CSM501  シンボリックマップ（マップセット CSM501 の生成物）
      *   CSM01  口座番号・更新金額・区分・メッセージ
      *----------------------------------------------------------------
       01  CSM01I.
           02  FILLER PIC X(12).
           02  KOZANOL COMP PIC S9(4).
           02  KOZANOF PICTURE X.
           02  FILLER REDEFINES KOZANOF.
               03  KOZANOA PICTURE X.
           02  KOZANOI PIC X(10).
           02  GAKUL COMP PIC S9(4).
           02  GAKUF PICTURE X.
           02  FILLER REDEFINES GAKUF.
               03  GAKUA PICTURE X.
           02  GAKUI PIC 9(09).
           02  KUBUNL COMP PIC S9(4).
           02  KUBUNF PICTURE X.
           02  FILLER REDEFINES KUBUNF.
               03  KUBUNA PICTURE X.
           02  KUBUNI PIC 9(01).
           02  MSGL COMP PIC S9(4).
           02  MSGF PICTURE X.
           02  FILLER REDEFINES MSGF.
               03  MSGA PICTURE X.
           02  MSGI PIC X(60).
      *----------------------------------------------------------------
       01  CSM01O REDEFINES CSM01I.
           02  FILLER PIC X(12).
           02  FILLER PICTURE X(3).
           02  KOZANOO PIC X(10).
           02  FILLER PICTURE X(3).
           02  GAKUO PIC X(09).
           02  FILLER PICTURE X(3).
           02  KUBUNO PIC X(01).
           02  FILLER PICTURE X(3).
           02  MSGO PIC X(60).
