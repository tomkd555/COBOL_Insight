000100*=================================================================
000200* COPY       : FLC010
000300* 機能       : 入金レコード
000400* 備考       : :PFX: を取り込み側の接頭辞へ置き換えて使います。
000500*              明細部は申告件数ぶんだけ可変で並びます。
000600*=================================================================
000700 01  :PFX:-NYUKIN-REC.
000800     05  :PFX:-KEIYAKU-NO           PIC X(10).
000900     05  :PFX:-NYUKIN-YMD           PIC 9(08).
001000     05  :PFX:-NYUKIN-GAKU          PIC S9(09)V99 COMP-3.
001100     05  :PFX:-HANBAITEN-CD         PIC X(05).
001200     05  :PFX:-SHORI-KBN            PIC X(01).
001300         88  :PFX:-MISHORI          VALUE '0'.
001400         88  :PFX:-SHORIZUMI        VALUE '1'.
001500         88  :PFX:-TORIKESHI        VALUE '9'.
001600     05  :PFX:-MEISAI-CNT           PIC 9(02).
001700     05  :PFX:-MEISAI OCCURS 1 TO 30 TIMES
001800              DEPENDING ON :PFX:-MEISAI-CNT.
001900         10  :PFX:-MEISAI-KBN       PIC X(01).
002000         10  :PFX:-MEISAI-GAKU      PIC S9(07)V99 COMP-3.
002100     05  FILLER                     PIC X(50).
