/*
 Cypher lexer grammar for Graphite.
 Based on openCypher specification.
*/

lexer grammar CypherLexer;

channels {
    COMMENTS
}

options {
    caseInsensitive = true;
}

// Operators and punctuation
ASSIGN     : '=';
ADD_ASSIGN : '+=';
REGEX      : '=~';
LE         : '<=';
GE         : '>=';
NOT_EQUAL  : '<>';
GT         : '>';
LT         : '<';
RANGE      : '..';
SEMI       : ';';
DOT        : '.';
COMMA      : ',';
LPAREN     : '(';
RPAREN     : ')';
LBRACE     : '{';
RBRACE     : '}';
LBRACK     : '[';
RBRACK     : ']';
SUB        : '-';
PLUS       : '+';
DIV        : '/';
MOD        : '%';
CARET      : '^';
MULT       : '*';
COLON      : ':';
STICK      : '|';
DOLLAR     : '$';

// Keywords
CALL       : 'CALL';
YIELD      : 'YIELD';
FILTER     : 'FILTER';
EXTRACT    : 'EXTRACT';
COUNT      : 'COUNT';
ANY        : 'ANY';
NONE       : 'NONE';
SINGLE     : 'SINGLE';
ALL        : 'ALL';
ASC        : 'ASC';
ASCENDING  : 'ASCENDING';
BY         : 'BY';
CREATE     : 'CREATE';
DELETE     : 'DELETE';
DESC       : 'DESC';
DESCENDING : 'DESCENDING';
DETACH     : 'DETACH';
EXISTS     : 'EXISTS';
LIMIT      : 'LIMIT';
MATCH      : 'MATCH';
MERGE      : 'MERGE';
ON         : 'ON';
OPTIONAL   : 'OPTIONAL';
ORDER      : 'ORDER';
REMOVE     : 'REMOVE';
RETURN     : 'RETURN';
SET        : 'SET';
SKIP_W     : 'SKIP';
WHERE      : 'WHERE';
WITH       : 'WITH';
UNION      : 'UNION';
UNWIND     : 'UNWIND';
AND        : 'AND';
AS         : 'AS';
CONTAINS   : 'CONTAINS';
DISTINCT   : 'DISTINCT';
ENDS       : 'ENDS';
IN         : 'IN';
IS         : 'IS';
NOT        : 'NOT';
OR         : 'OR';
STARTS     : 'STARTS';
XOR        : 'XOR';
FALSE      : 'FALSE';
TRUE       : 'TRUE';
NULL_W     : 'NULL';
CONSTRAINT : 'CONSTRAINT';
DO         : 'DO';
FOR        : 'FOR';
REQUIRE    : 'REQUIRE';
UNIQUE     : 'UNIQUE';
CASE       : 'CASE';
WHEN       : 'WHEN';
THEN       : 'THEN';
ELSE       : 'ELSE';
END        : 'END';
MANDATORY  : 'MANDATORY';
SCALAR     : 'SCALAR';
OF         : 'OF';
ADD        : 'ADD';
DROP       : 'DROP';

// Identifiers
ID : LetterOrUnderscore (LetterOrUnderscore | [0-9])*;

// Escaped identifier: `something`
ESC_LITERAL : '`' ~[`]* '`';

// String literals: both single and double quoted, multi-char
STRING_LITERAL
    : '\'' ( '\\' . | '\'\'' | ~['\\] )* '\''
    | '"'  ( '\\' . | '""'  | ~["\\] )* '"'
    ;

// Numbers
HEX_INTEGER    : '0x' [0-9a-f]+;
OCTAL_INTEGER  : '0o' [0-9]+;
FLOAT_LITERAL  : [0-9]+ '.' [0-9]+ ExponentPart?
               | '.' [0-9]+ ExponentPart?
               | [0-9]+ ExponentPart
               ;
INTEGER_LITERAL: '0' | [1-9] [0-9]*;

// Whitespace and comments
WS           : [ \t\r\n\u000C]+ -> channel(HIDDEN);
COMMENT      : '/*' .*? '*/'    -> channel(COMMENTS);
LINE_COMMENT : '//' ~[\r\n]*    -> channel(COMMENTS);

fragment ExponentPart : [e] [+-]? [0-9]+;
fragment LetterOrUnderscore : [a-z_];
