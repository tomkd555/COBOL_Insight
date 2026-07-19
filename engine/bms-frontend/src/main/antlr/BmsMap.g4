grammar BmsMap;

mapFile
    : statement* EOF
    ;

statement
    : label=NAME? macro
    | END
    ;

macro
    : kind=(DFHMSD | DFHMDI | DFHMDF) paramList?
    ;

paramList
    : param (COMMA param)*
    ;

param
    : key=NAME EQUALS value
    ;

value
    : LPAREN value? (COMMA value?)* RPAREN  # groupValue
    | NAME                                  # nameValue
    | NUMBER                                # numberValue
    | SYSVAR                                # sysvarValue
    | STRING                                # stringValue
    ;

DFHMSD : 'DFHMSD' ;
DFHMDI : 'DFHMDI' ;
DFHMDF : 'DFHMDF' ;
END    : 'END' ;

// 1桁目の * はコメント行
COMMENT : {getCharPositionInLine() == 0}? '*' ~[\r\n]* -> skip ;

// 72桁目以降(継続指示子と一連番号領域)はコードではない
MARGIN : {getCharPositionInLine() >= 71}? ~[\r\n]+ -> skip ;

EQUALS : '=' ;
COMMA  : ',' ;
LPAREN : '(' ;
RPAREN : ')' ;

NAME   : [A-Za-z@#$] [A-Za-z0-9@#$]* ;
NUMBER : [0-9]+ ;
SYSVAR : '&' [A-Za-z@#$] [A-Za-z0-9@#$]* ;
STRING : '\'' ('\'\'' | ~['\r\n])* '\'' ;

WS : [ \t\r\n]+ -> skip ;
