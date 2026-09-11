000100*=================================================================
000200* COPY       : FLC020
000300* 機能       : 契約マスタレコード
000400* 備考       : 取り込み側は接頭辞 FLC2 を業務略号へ置き換えます。
000500*=================================================================
000600 01  FLC2-KEIYAKU-REC.
000700     05  FLC2-KEIYAKU-NO            PIC X(10).
000800     05  FLC2-KOKYAKU-NO            PIC X(08).
000900     05  FLC2-KOKYAKU-MEI           PIC X(30).
001000     05  FLC2-KEIYAKU-YMD           PIC 9(08).
001100     05  FLC2-KEIYAKU-YMD-R REDEFINES FLC2-KEIYAKU-YMD.
001200         10  FLC2-KEIYAKU-YYYY      PIC 9(04).
001300         10  FLC2-KEIYAKU-MM        PIC 9(02).
001400         10  FLC2-KEIYAKU-DD        PIC 9(02).
001500     05  FLC2-ZANDAKA               PIC S9(11) COMP-3.
001600     05  FLC2-SEIKYU-GAKU           PIC S9(09) COMP-3.
001700     05  FLC2-HANBAITEN-CD          PIC X(05).
001800     05  FLC2-SEIKYU-KBN            PIC X(01).
001900     05  FLC2-TOKUSOKU-KBN          PIC X(01).
002000         88  FLC2-TOKUSOKU-NASHI    VALUE '0'.
002100         88  FLC2-TOKUSOKU-1JI      VALUE '1'.
002200         88  FLC2-TOKUSOKU-2JI      VALUE '2'.
002300         88  FLC2-TOKUSOKU-HORITSU  VALUE '9'.
002400     05  FLC2-KOSHIN-YMD            PIC 9(08).
002500     05  FILLER                     PIC X(20).
