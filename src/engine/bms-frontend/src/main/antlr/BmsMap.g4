/*
 * CICS BMS マップ定義ソース(アセンブラ固定形式)の文法。
 *
 * 受理する入力
 *   - マクロ DFHMSD(マップセット)、DFHMDI(マップ)、DFHMDF(フィールド)と、
 *     アセンブラの終端命令 END。
 *   - マクロ名の前に置く任意のラベル。マップセット名・マップ名・フィールド名がここに来る。
 *   - key=value 形式のパラメータ列。値は、名前、数値、括弧で囲んだ複数値、
 *     引用符で囲んだ文字列、&SYSPARM のような変数参照のいずれかを取る。
 *   - 継続行。1 つのマクロのパラメータ列が複数行にわたる記述を受理する。
 *   - 1 桁目を * としたコメント行。
 *
 * 受理しない入力
 *   - DFHMSD・DFHMDI・DFHMDF 以外の BMS マクロ(パーティション定義の DFHPSD など)。
 *   - COPY のようなアセンブラ命令。PRINT・TITLE・SPACE・EJECT の行は BmsSourceParser が
 *     字句解析の前に空行へ置き換えるため、ここには届かない。
 *   - 条件アセンブリ(AIF・AGO・SETA など)。&SYSPARM のような変数参照は字句として
 *     保持するだけで、値の展開は行わない。
 *   - 行をまたぐ引用文字列。文字列は同一行の中で閉じる必要がある。
 *   - パラメータ値の中の演算子と長さ属性(L'FIELD の形)、および数字で始まり英字を含む
 *     16 進定数(XINIT=1D の形)。数値は数字だけ、名前は英字または国別文字で始まる。
 */
grammar BmsMap;

mapFile
    : statement* EOF
    ;

// 文の境界はマクロ名で決まる。改行は空白として読み飛ばすため、行の切れ目は境界にならない。
// ラベルの桁位置は検査しない。固定形式ではラベルは 1 桁目から始まるが、その検査は行わない。
statement
    : label=NAME? macro
    | END
    ;

macro
    : kind=(DFHMSD | DFHMDI | DFHMDF) paramList?
    ;

// パラメータ列が続くかどうかは、コンマの有無だけで決まる。継続指示子 X は字句の段階で
// 読み飛ばすため、継続行かどうかは構文解析に影響しない。
paramList
    : param (COMMA param)*
    ;

param
    : key=NAME EQUALS value
    ;

// 括弧の中の要素は省略できる。アセンブラのマクロは括弧内の位置パラメータを空にできる。
value
    : LPAREN value? (COMMA value?)* RPAREN  # groupValue
    | NAME                                  # nameValue
    | NUMBER                                # numberValue
    | DASHED                                # dashedValue
    | SYSVAR                                # sysvarValue
    | STRING                                # stringValue
    ;

// マクロ名は NAME より先に宣言する。字句規則の宣言順が優先順位を決めるため、
// この順序でなければ DFHMSD 等が NAME として字句解析される。
DFHMSD : 'DFHMSD' ;
DFHMDI : 'DFHMDI' ;
DFHMDF : 'DFHMDF' ;
END    : 'END' ;

// 1桁目の * はコメント行。アセンブラの固定形式では 1 桁目の * だけがコメントを表すため、
// 行の途中に現れる * をコメントとして扱わないよう桁位置の述語で限定する。
COMMENT : {getCharPositionInLine() == 0}? '*' ~[\r\n]* -> skip ;

// 72桁目以降(継続指示子と一連番号領域)はコードではない。
// 述語の 71 は 0 起点の桁位置(getCharPositionInLine)であり、1 起点で数えた 72 桁目に当たる。
MARGIN : {getCharPositionInLine() >= 71}? ~[\r\n]+ -> skip ;

EQUALS : '=' ;
COMMA  : ',' ;
LPAREN : '(' ;
RPAREN : ')' ;

// アセンブラの利用者定義語は、英数字に国別文字 @ # $ を含む。
NAME   : [A-Za-z@#$] [A-Za-z0-9@#$]* ;
NUMBER : [0-9]+ ;
// TERM=3270-2 のような、ハイフンでつないだ値。最長一致で NAME・NUMBER より優先される。
DASHED : [A-Za-z0-9@#$]+ ('-' [A-Za-z0-9@#$]+)+ ;
SYSVAR : '&' [A-Za-z@#$] [A-Za-z0-9@#$]* ;
// 文字列定数の中では ' を 2 個続けて ' 1 個を表す。
STRING : '\'' ('\'\'' | ~['\r\n])* '\'' ;

// 改行も空白として読み飛ばす。これによって、継続行にまたがるパラメータ列が 1 文になる。
WS : [ \t\r\n]+ -> skip ;
