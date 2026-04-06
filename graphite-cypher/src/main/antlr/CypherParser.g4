/*
 Cypher parser grammar for Graphite.
 Based on openCypher specification.
*/

parser grammar CypherParser;

options {
    tokenVocab = CypherLexer;
}

script
    : statement (SEMI statement)* SEMI? EOF
    ;

statement
    : query
    ;

query
    : regularQuery
    ;

regularQuery
    : singleQuery (unionSt singleQuery)*
    ;

unionSt
    : UNION ALL?
    ;

singleQuery
    : clause+
    ;

clause
    : matchSt
    | unwindSt
    | withSt
    | returnSt
    | createSt
    | deleteSt
    | setSt
    | removeSt
    | mergeSt
    ;

matchSt
    : OPTIONAL? MATCH patternList whereSt?
    ;

unwindSt
    : UNWIND expression AS variable
    ;

withSt
    : WITH DISTINCT? returnItems whereSt? orderBySt? skipSt? limitSt?
    ;

returnSt
    : RETURN DISTINCT? returnItems orderBySt? skipSt? limitSt?
    ;

whereSt
    : WHERE expression
    ;

orderBySt
    : ORDER BY orderItem (COMMA orderItem)*
    ;

orderItem
    : expression (ASCENDING | ASC | DESCENDING | DESC)?
    ;

skipSt
    : SKIP_W expression
    ;

limitSt
    : LIMIT expression
    ;

returnItems
    : MULT
    | returnItem (COMMA returnItem)*
    ;

returnItem
    : expression (AS variable)?
    ;

createSt
    : CREATE patternList
    ;

deleteSt
    : DETACH? DELETE expressionList
    ;

setSt
    : SET setItem (COMMA setItem)*
    ;

setItem
    : variable DOT propertyKeyName ASSIGN expression     # SetProperty
    | variable ADD_ASSIGN expression                      # SetMergeProperties
    | variable ASSIGN expression                          # SetAllProperties
    | variable nodeLabels                                 # SetLabels
    ;

removeSt
    : REMOVE removeItem (COMMA removeItem)*
    ;

removeItem
    : variable DOT propertyKeyName   # RemoveProperty
    | variable nodeLabels             # RemoveLabels
    ;

mergeSt
    : MERGE patternPart
    ;

// Patterns
patternList
    : patternPart (COMMA patternPart)*
    ;

patternPart
    : (variable ASSIGN)? patternElement
    ;

patternElement
    : nodePattern (relationshipPattern nodePattern)*
    ;

nodePattern
    : LPAREN variable? nodeLabels? properties? RPAREN
    ;

nodeLabels
    : (COLON labelName)+
    ;

relationshipPattern
    : leftArrow dash relationDetail? dash rightArrow     # RelFullPattern
    | leftArrow dash relationDetail? dash                 # RelLeftPattern
    | dash relationDetail? dash rightArrow                # RelRightPattern
    | dash relationDetail? dash                            # RelBothPattern
    ;

leftArrow
    : LT
    ;

rightArrow
    : GT
    ;

dash
    : SUB
    ;

relationDetail
    : LBRACK variable? relationshipTypes? rangeLiteral? properties? RBRACK
    ;

relationshipTypes
    : COLON relTypeName (STICK COLON? relTypeName)*
    ;

rangeLiteral
    : MULT integerLiteral? (RANGE integerLiteral?)?
    ;

properties
    : mapLiteral
    ;

// Expressions
expression
    : orExpression
    ;

orExpression
    : xorExpression (OR xorExpression)*
    ;

xorExpression
    : andExpression (XOR andExpression)*
    ;

andExpression
    : notExpression (AND notExpression)*
    ;

notExpression
    : NOT notExpression
    | comparisonExpression
    ;

comparisonExpression
    : stringPredicateExpression (compOp stringPredicateExpression)*
    ;

compOp
    : ASSIGN
    | NOT_EQUAL
    | LT
    | GT
    | LE
    | GE
    ;

stringPredicateExpression
    : addSubExpression stringPredicateSuffix*
    ;

stringPredicateSuffix
    : STARTS WITH addSubExpression                   # StartsWithPredicate
    | ENDS WITH addSubExpression                     # EndsWithPredicate
    | CONTAINS addSubExpression                      # ContainsPredicate
    | IN addSubExpression                            # InPredicate
    | REGEX addSubExpression                         # RegexPredicate
    | IS NOT NULL_W                                  # IsNotNullPredicate
    | IS NULL_W                                      # IsNullPredicate
    | NOT CONTAINS addSubExpression                  # NotContainsPredicate
    | NOT STARTS WITH addSubExpression               # NotStartsWithPredicate
    | NOT ENDS WITH addSubExpression                 # NotEndsWithPredicate
    ;

addSubExpression
    : multDivExpression ((PLUS | SUB) multDivExpression)*
    ;

multDivExpression
    : powerExpression ((MULT | DIV | MOD) powerExpression)*
    ;

powerExpression
    : unaryExpression (CARET powerExpression)?
    ;

unaryExpression
    : SUB unaryExpression
    | PLUS unaryExpression
    | postfixExpression
    ;

postfixExpression
    : atomExpression (DOT propertyKeyName | LBRACK subscriptOrSlice RBRACK)*
    ;

subscriptOrSlice
    : expression RANGE expression?    # SliceFromTo
    | RANGE expression                # SliceTo
    | expression                      # SubscriptIndex
    ;

atomExpression
    : parameter                                                            # ParameterAtom
    | caseExpression                                                       # CaseAtom
    | COUNT LPAREN MULT RPAREN                                             # CountStarAtom
    | listComprehension                                                    # ListComprehensionAtom
    | EXISTS LPAREN expression RPAREN                                      # ExistsAtom
    | functionInvocation                                                   # FunctionAtom
    | LPAREN expression RPAREN                                             # ParenAtom
    | DISTINCT unaryExpression                                             # DistinctAtom
    | literal                                                              # LiteralAtom
    | variable                                                             # VariableAtom
    ;

functionInvocation
    : functionName LPAREN DISTINCT? expressionList? RPAREN
    ;

functionName
    : symbolicName (DOT symbolicName)*
    ;

caseExpression
    : CASE expression? caseWhen+ caseElse? END
    ;

caseWhen
    : WHEN expression THEN expression
    ;

caseElse
    : ELSE expression
    ;

listComprehension
    : LBRACK variable IN expression (WHERE expression)? (STICK expression)? RBRACK
    ;

parameter
    : DOLLAR symbolicName
    ;

// Literals
literal
    : TRUE                   # TrueLiteral
    | FALSE                  # FalseLiteral
    | NULL_W                 # NullLiteral
    | integerLiteral         # IntLiteral
    | floatLiteral           # FloatLit
    | stringLiteral          # StringLit
    | listLiteral            # ListLit
    | mapLiteral             # MapLit
    ;

integerLiteral
    : INTEGER_LITERAL
    | HEX_INTEGER
    | OCTAL_INTEGER
    ;

floatLiteral
    : FLOAT_LITERAL
    ;

stringLiteral
    : STRING_LITERAL
    ;

listLiteral
    : LBRACK expressionList? RBRACK
    ;

mapLiteral
    : LBRACE (mapPair (COMMA mapPair)*)? RBRACE
    ;

mapPair
    : propertyKeyName COLON expression
    ;

expressionList
    : expression (COMMA expression)*
    ;

// Names
variable
    : symbolicName
    ;

labelName
    : symbolicName
    ;

relTypeName
    : symbolicName
    ;

propertyKeyName
    : symbolicName
    ;

symbolicName
    : ID
    | ESC_LITERAL
    | CALL
    | YIELD
    | COUNT
    | FILTER
    | EXTRACT
    | ANY
    | NONE
    | SINGLE
    | ALL
    | ASC
    | ASCENDING
    | BY
    | CREATE
    | DELETE
    | DESC
    | DESCENDING
    | DETACH
    | EXISTS
    | LIMIT
    | MATCH
    | MERGE
    | ON
    | OPTIONAL
    | ORDER
    | REMOVE
    | RETURN
    | SET
    | SKIP_W
    | WHERE
    | WITH
    | UNION
    | UNWIND
    | AND
    | AS
    | CONTAINS
    | DISTINCT
    | ENDS
    | IN
    | IS
    | NOT
    | OR
    | STARTS
    | XOR
    | FALSE
    | TRUE
    | NULL_W
    | CONSTRAINT
    | DO
    | FOR
    | REQUIRE
    | UNIQUE
    | CASE
    | WHEN
    | THEN
    | ELSE
    | END
    | MANDATORY
    | SCALAR
    | OF
    | ADD
    | DROP
    ;
