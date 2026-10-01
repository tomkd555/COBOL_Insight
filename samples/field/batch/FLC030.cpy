000100*=================================================================
000200* COPY       : FLC030
000300* 機能       : 督促レコード
000400* 備考       : 項目名の接頭辞は XX- で統一しています。
000500*=================================================================
000600 01  XX-TOKUSOKU-REC.
000700     05  XX-KEIYAKU-NO              PIC X(10).
000800     05  XX-KOKYAKU-NO              PIC X(08).
000900     05  XX-KOKYAKU-MEI             PIC X(30).
001000     05  XX-TOKUSOKU-KBN            PIC X(01).
001100     05  XX-TOKUSOKU-GAKU           PIC S9(09) COMP-3.
001200     05  XX-TOKUSOKU-YMD            PIC 9(08).
001300     05  XX-HANBAITEN-CD            PIC X(05).
001400     05  FILLER                     PIC X(15).
