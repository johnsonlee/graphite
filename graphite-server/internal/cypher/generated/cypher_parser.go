// Code generated from graphite-cypher/src/main/antlr/CypherParser.g4 by ANTLR 4.13.2. DO NOT EDIT.

package generated // CypherParser
import (
	"fmt"
	"strconv"
	"sync"

	"github.com/antlr4-go/antlr/v4"
)

// Suppress unused import errors
var _ = fmt.Printf
var _ = strconv.Itoa
var _ = sync.Once{}

type CypherParser struct {
	*antlr.BaseParser
}

var CypherParserParserStaticData struct {
	once                   sync.Once
	serializedATN          []int32
	LiteralNames           []string
	SymbolicNames          []string
	RuleNames              []string
	PredictionContextCache *antlr.PredictionContextCache
	atn                    *antlr.ATN
	decisionToDFA          []*antlr.DFA
}

func cypherparserParserInit() {
	staticData := &CypherParserParserStaticData
	staticData.LiteralNames = []string{
		"", "'='", "'+='", "'=~'", "'<='", "'>='", "'<>'", "'>'", "'<'", "'..'",
		"';'", "'.'", "','", "'('", "')'", "'{'", "'}'", "'['", "']'", "'-'",
		"'+'", "'/'", "'%'", "'^'", "'*'", "':'", "'|'", "'$'", "'CALL'", "'YIELD'",
		"'FILTER'", "'EXTRACT'", "'COUNT'", "'ANY'", "'NONE'", "'SINGLE'", "'ALL'",
		"'ASC'", "'ASCENDING'", "'BY'", "'CREATE'", "'DELETE'", "'DESC'", "'DESCENDING'",
		"'DETACH'", "'EXISTS'", "'LIMIT'", "'MATCH'", "'MERGE'", "'ON'", "'OPTIONAL'",
		"'ORDER'", "'REMOVE'", "'RETURN'", "'SET'", "'SKIP'", "'WHERE'", "'WITH'",
		"'UNION'", "'UNWIND'", "'AND'", "'AS'", "'CONTAINS'", "'DISTINCT'",
		"'ENDS'", "'IN'", "'IS'", "'NOT'", "'OR'", "'STARTS'", "'XOR'", "'FALSE'",
		"'TRUE'", "'NULL'", "'CONSTRAINT'", "'DO'", "'FOR'", "'REQUIRE'", "'UNIQUE'",
		"'CASE'", "'WHEN'", "'THEN'", "'ELSE'", "'END'", "'MANDATORY'", "'SCALAR'",
		"'OF'", "'ADD'", "'DROP'",
	}
	staticData.SymbolicNames = []string{
		"", "ASSIGN", "ADD_ASSIGN", "REGEX", "LE", "GE", "NOT_EQUAL", "GT",
		"LT", "RANGE", "SEMI", "DOT", "COMMA", "LPAREN", "RPAREN", "LBRACE",
		"RBRACE", "LBRACK", "RBRACK", "SUB", "PLUS", "DIV", "MOD", "CARET",
		"MULT", "COLON", "STICK", "DOLLAR", "CALL", "YIELD", "FILTER", "EXTRACT",
		"COUNT", "ANY", "NONE", "SINGLE", "ALL", "ASC", "ASCENDING", "BY", "CREATE",
		"DELETE", "DESC", "DESCENDING", "DETACH", "EXISTS", "LIMIT", "MATCH",
		"MERGE", "ON", "OPTIONAL", "ORDER", "REMOVE", "RETURN", "SET", "SKIP_W",
		"WHERE", "WITH", "UNION", "UNWIND", "AND", "AS", "CONTAINS", "DISTINCT",
		"ENDS", "IN", "IS", "NOT", "OR", "STARTS", "XOR", "FALSE", "TRUE", "NULL_W",
		"CONSTRAINT", "DO", "FOR", "REQUIRE", "UNIQUE", "CASE", "WHEN", "THEN",
		"ELSE", "END", "MANDATORY", "SCALAR", "OF", "ADD", "DROP", "ID", "ESC_LITERAL",
		"STRING_LITERAL", "HEX_INTEGER", "OCTAL_INTEGER", "FLOAT_LITERAL", "INTEGER_LITERAL",
		"WS", "COMMENT", "LINE_COMMENT",
	}
	staticData.RuleNames = []string{
		"script", "statement", "query", "regularQuery", "unionSt", "singleQuery",
		"clause", "matchSt", "unwindSt", "withSt", "returnSt", "whereSt", "orderBySt",
		"orderItem", "skipSt", "limitSt", "returnItems", "returnItem", "createSt",
		"deleteSt", "setSt", "setItem", "removeSt", "removeItem", "mergeSt",
		"patternList", "patternPart", "patternElement", "nodePattern", "nodeLabels",
		"relationshipPattern", "leftArrow", "rightArrow", "dash", "relationDetail",
		"relationshipTypes", "rangeLiteral", "properties", "expression", "orExpression",
		"xorExpression", "andExpression", "notExpression", "comparisonExpression",
		"compOp", "stringPredicateExpression", "stringPredicateSuffix", "addSubExpression",
		"multDivExpression", "powerExpression", "unaryExpression", "postfixExpression",
		"subscriptOrSlice", "atomExpression", "functionInvocation", "functionName",
		"caseExpression", "caseWhen", "caseElse", "listComprehension", "predicateFunction",
		"parameter", "literal", "integerLiteral", "floatLiteral", "stringLiteral",
		"listLiteral", "mapLiteral", "mapPair", "expressionList", "variable",
		"labelName", "relTypeName", "propertyKeyName", "symbolicName",
	}
	staticData.PredictionContextCache = antlr.NewPredictionContextCache()
	staticData.serializedATN = []int32{
		4, 1, 98, 733, 2, 0, 7, 0, 2, 1, 7, 1, 2, 2, 7, 2, 2, 3, 7, 3, 2, 4, 7,
		4, 2, 5, 7, 5, 2, 6, 7, 6, 2, 7, 7, 7, 2, 8, 7, 8, 2, 9, 7, 9, 2, 10, 7,
		10, 2, 11, 7, 11, 2, 12, 7, 12, 2, 13, 7, 13, 2, 14, 7, 14, 2, 15, 7, 15,
		2, 16, 7, 16, 2, 17, 7, 17, 2, 18, 7, 18, 2, 19, 7, 19, 2, 20, 7, 20, 2,
		21, 7, 21, 2, 22, 7, 22, 2, 23, 7, 23, 2, 24, 7, 24, 2, 25, 7, 25, 2, 26,
		7, 26, 2, 27, 7, 27, 2, 28, 7, 28, 2, 29, 7, 29, 2, 30, 7, 30, 2, 31, 7,
		31, 2, 32, 7, 32, 2, 33, 7, 33, 2, 34, 7, 34, 2, 35, 7, 35, 2, 36, 7, 36,
		2, 37, 7, 37, 2, 38, 7, 38, 2, 39, 7, 39, 2, 40, 7, 40, 2, 41, 7, 41, 2,
		42, 7, 42, 2, 43, 7, 43, 2, 44, 7, 44, 2, 45, 7, 45, 2, 46, 7, 46, 2, 47,
		7, 47, 2, 48, 7, 48, 2, 49, 7, 49, 2, 50, 7, 50, 2, 51, 7, 51, 2, 52, 7,
		52, 2, 53, 7, 53, 2, 54, 7, 54, 2, 55, 7, 55, 2, 56, 7, 56, 2, 57, 7, 57,
		2, 58, 7, 58, 2, 59, 7, 59, 2, 60, 7, 60, 2, 61, 7, 61, 2, 62, 7, 62, 2,
		63, 7, 63, 2, 64, 7, 64, 2, 65, 7, 65, 2, 66, 7, 66, 2, 67, 7, 67, 2, 68,
		7, 68, 2, 69, 7, 69, 2, 70, 7, 70, 2, 71, 7, 71, 2, 72, 7, 72, 2, 73, 7,
		73, 2, 74, 7, 74, 1, 0, 1, 0, 1, 0, 5, 0, 154, 8, 0, 10, 0, 12, 0, 157,
		9, 0, 1, 0, 3, 0, 160, 8, 0, 1, 0, 1, 0, 1, 1, 1, 1, 1, 2, 1, 2, 1, 3,
		1, 3, 1, 3, 1, 3, 5, 3, 172, 8, 3, 10, 3, 12, 3, 175, 9, 3, 1, 4, 1, 4,
		3, 4, 179, 8, 4, 1, 5, 4, 5, 182, 8, 5, 11, 5, 12, 5, 183, 1, 6, 1, 6,
		1, 6, 1, 6, 1, 6, 1, 6, 1, 6, 1, 6, 1, 6, 3, 6, 195, 8, 6, 1, 7, 3, 7,
		198, 8, 7, 1, 7, 1, 7, 1, 7, 3, 7, 203, 8, 7, 1, 8, 1, 8, 1, 8, 1, 8, 1,
		8, 1, 9, 1, 9, 3, 9, 212, 8, 9, 1, 9, 1, 9, 3, 9, 216, 8, 9, 1, 9, 3, 9,
		219, 8, 9, 1, 9, 3, 9, 222, 8, 9, 1, 9, 3, 9, 225, 8, 9, 1, 10, 1, 10,
		3, 10, 229, 8, 10, 1, 10, 1, 10, 3, 10, 233, 8, 10, 1, 10, 3, 10, 236,
		8, 10, 1, 10, 3, 10, 239, 8, 10, 1, 11, 1, 11, 1, 11, 1, 12, 1, 12, 1,
		12, 1, 12, 1, 12, 5, 12, 249, 8, 12, 10, 12, 12, 12, 252, 9, 12, 1, 13,
		1, 13, 3, 13, 256, 8, 13, 1, 14, 1, 14, 1, 14, 1, 15, 1, 15, 1, 15, 1,
		16, 1, 16, 1, 16, 1, 16, 5, 16, 268, 8, 16, 10, 16, 12, 16, 271, 9, 16,
		3, 16, 273, 8, 16, 1, 17, 1, 17, 1, 17, 3, 17, 278, 8, 17, 1, 18, 1, 18,
		1, 18, 1, 19, 3, 19, 284, 8, 19, 1, 19, 1, 19, 1, 19, 1, 20, 1, 20, 1,
		20, 1, 20, 5, 20, 293, 8, 20, 10, 20, 12, 20, 296, 9, 20, 1, 21, 1, 21,
		1, 21, 1, 21, 1, 21, 1, 21, 1, 21, 1, 21, 1, 21, 1, 21, 1, 21, 1, 21, 1,
		21, 1, 21, 1, 21, 1, 21, 1, 21, 3, 21, 315, 8, 21, 1, 22, 1, 22, 1, 22,
		1, 22, 5, 22, 321, 8, 22, 10, 22, 12, 22, 324, 9, 22, 1, 23, 1, 23, 1,
		23, 1, 23, 1, 23, 1, 23, 1, 23, 3, 23, 333, 8, 23, 1, 24, 1, 24, 1, 24,
		1, 25, 1, 25, 1, 25, 5, 25, 341, 8, 25, 10, 25, 12, 25, 344, 9, 25, 1,
		26, 1, 26, 1, 26, 3, 26, 349, 8, 26, 1, 26, 1, 26, 1, 27, 1, 27, 1, 27,
		1, 27, 5, 27, 357, 8, 27, 10, 27, 12, 27, 360, 9, 27, 1, 28, 1, 28, 3,
		28, 364, 8, 28, 1, 28, 3, 28, 367, 8, 28, 1, 28, 3, 28, 370, 8, 28, 1,
		28, 1, 28, 1, 29, 1, 29, 4, 29, 376, 8, 29, 11, 29, 12, 29, 377, 1, 30,
		1, 30, 1, 30, 3, 30, 383, 8, 30, 1, 30, 1, 30, 1, 30, 1, 30, 1, 30, 1,
		30, 3, 30, 391, 8, 30, 1, 30, 1, 30, 1, 30, 1, 30, 3, 30, 397, 8, 30, 1,
		30, 1, 30, 1, 30, 1, 30, 1, 30, 3, 30, 404, 8, 30, 1, 30, 1, 30, 3, 30,
		408, 8, 30, 1, 31, 1, 31, 1, 32, 1, 32, 1, 33, 1, 33, 1, 34, 1, 34, 3,
		34, 418, 8, 34, 1, 34, 3, 34, 421, 8, 34, 1, 34, 3, 34, 424, 8, 34, 1,
		34, 3, 34, 427, 8, 34, 1, 34, 1, 34, 1, 35, 1, 35, 1, 35, 1, 35, 3, 35,
		435, 8, 35, 1, 35, 5, 35, 438, 8, 35, 10, 35, 12, 35, 441, 9, 35, 1, 36,
		1, 36, 3, 36, 445, 8, 36, 1, 36, 1, 36, 3, 36, 449, 8, 36, 3, 36, 451,
		8, 36, 1, 37, 1, 37, 1, 38, 1, 38, 1, 39, 1, 39, 1, 39, 5, 39, 460, 8,
		39, 10, 39, 12, 39, 463, 9, 39, 1, 40, 1, 40, 1, 40, 5, 40, 468, 8, 40,
		10, 40, 12, 40, 471, 9, 40, 1, 41, 1, 41, 1, 41, 5, 41, 476, 8, 41, 10,
		41, 12, 41, 479, 9, 41, 1, 42, 1, 42, 1, 42, 3, 42, 484, 8, 42, 1, 43,
		1, 43, 1, 43, 1, 43, 5, 43, 490, 8, 43, 10, 43, 12, 43, 493, 9, 43, 1,
		44, 1, 44, 1, 45, 1, 45, 5, 45, 499, 8, 45, 10, 45, 12, 45, 502, 9, 45,
		1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1,
		46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46,
		1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 1, 46, 3, 46, 532, 8, 46, 1,
		47, 1, 47, 1, 47, 5, 47, 537, 8, 47, 10, 47, 12, 47, 540, 9, 47, 1, 48,
		1, 48, 1, 48, 5, 48, 545, 8, 48, 10, 48, 12, 48, 548, 9, 48, 1, 49, 1,
		49, 1, 49, 3, 49, 553, 8, 49, 1, 50, 1, 50, 1, 50, 1, 50, 1, 50, 3, 50,
		560, 8, 50, 1, 51, 1, 51, 1, 51, 1, 51, 1, 51, 1, 51, 1, 51, 5, 51, 569,
		8, 51, 10, 51, 12, 51, 572, 9, 51, 1, 52, 1, 52, 1, 52, 3, 52, 577, 8,
		52, 1, 52, 1, 52, 1, 52, 3, 52, 582, 8, 52, 1, 53, 1, 53, 1, 53, 1, 53,
		1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1,
		53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 1, 53, 3, 53, 606, 8, 53,
		1, 54, 1, 54, 1, 54, 3, 54, 611, 8, 54, 1, 54, 3, 54, 614, 8, 54, 1, 54,
		1, 54, 1, 55, 1, 55, 1, 55, 5, 55, 621, 8, 55, 10, 55, 12, 55, 624, 9,
		55, 1, 56, 1, 56, 3, 56, 628, 8, 56, 1, 56, 4, 56, 631, 8, 56, 11, 56,
		12, 56, 632, 1, 56, 3, 56, 636, 8, 56, 1, 56, 1, 56, 1, 57, 1, 57, 1, 57,
		1, 57, 1, 57, 1, 58, 1, 58, 1, 58, 1, 59, 1, 59, 1, 59, 1, 59, 1, 59, 1,
		59, 3, 59, 654, 8, 59, 1, 59, 1, 59, 3, 59, 658, 8, 59, 1, 59, 1, 59, 1,
		60, 1, 60, 1, 60, 1, 60, 1, 60, 1, 60, 1, 60, 3, 60, 669, 8, 60, 1, 60,
		1, 60, 1, 61, 1, 61, 1, 61, 1, 62, 1, 62, 1, 62, 1, 62, 1, 62, 1, 62, 1,
		62, 1, 62, 3, 62, 684, 8, 62, 1, 63, 1, 63, 1, 64, 1, 64, 1, 65, 1, 65,
		1, 66, 1, 66, 3, 66, 694, 8, 66, 1, 66, 1, 66, 1, 67, 1, 67, 1, 67, 1,
		67, 5, 67, 702, 8, 67, 10, 67, 12, 67, 705, 9, 67, 3, 67, 707, 8, 67, 1,
		67, 1, 67, 1, 68, 1, 68, 1, 68, 1, 68, 1, 69, 1, 69, 1, 69, 5, 69, 718,
		8, 69, 10, 69, 12, 69, 721, 9, 69, 1, 70, 1, 70, 1, 71, 1, 71, 1, 72, 1,
		72, 1, 73, 1, 73, 1, 74, 1, 74, 1, 74, 0, 0, 75, 0, 2, 4, 6, 8, 10, 12,
		14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48,
		50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84,
		86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116,
		118, 120, 122, 124, 126, 128, 130, 132, 134, 136, 138, 140, 142, 144, 146,
		148, 0, 7, 2, 0, 37, 38, 42, 43, 2, 0, 1, 1, 4, 8, 1, 0, 19, 20, 2, 0,
		21, 22, 24, 24, 1, 0, 33, 36, 2, 0, 92, 93, 95, 95, 1, 0, 28, 90, 771,
		0, 150, 1, 0, 0, 0, 2, 163, 1, 0, 0, 0, 4, 165, 1, 0, 0, 0, 6, 167, 1,
		0, 0, 0, 8, 176, 1, 0, 0, 0, 10, 181, 1, 0, 0, 0, 12, 194, 1, 0, 0, 0,
		14, 197, 1, 0, 0, 0, 16, 204, 1, 0, 0, 0, 18, 209, 1, 0, 0, 0, 20, 226,
		1, 0, 0, 0, 22, 240, 1, 0, 0, 0, 24, 243, 1, 0, 0, 0, 26, 253, 1, 0, 0,
		0, 28, 257, 1, 0, 0, 0, 30, 260, 1, 0, 0, 0, 32, 272, 1, 0, 0, 0, 34, 274,
		1, 0, 0, 0, 36, 279, 1, 0, 0, 0, 38, 283, 1, 0, 0, 0, 40, 288, 1, 0, 0,
		0, 42, 314, 1, 0, 0, 0, 44, 316, 1, 0, 0, 0, 46, 332, 1, 0, 0, 0, 48, 334,
		1, 0, 0, 0, 50, 337, 1, 0, 0, 0, 52, 348, 1, 0, 0, 0, 54, 352, 1, 0, 0,
		0, 56, 361, 1, 0, 0, 0, 58, 375, 1, 0, 0, 0, 60, 407, 1, 0, 0, 0, 62, 409,
		1, 0, 0, 0, 64, 411, 1, 0, 0, 0, 66, 413, 1, 0, 0, 0, 68, 415, 1, 0, 0,
		0, 70, 430, 1, 0, 0, 0, 72, 442, 1, 0, 0, 0, 74, 452, 1, 0, 0, 0, 76, 454,
		1, 0, 0, 0, 78, 456, 1, 0, 0, 0, 80, 464, 1, 0, 0, 0, 82, 472, 1, 0, 0,
		0, 84, 483, 1, 0, 0, 0, 86, 485, 1, 0, 0, 0, 88, 494, 1, 0, 0, 0, 90, 496,
		1, 0, 0, 0, 92, 531, 1, 0, 0, 0, 94, 533, 1, 0, 0, 0, 96, 541, 1, 0, 0,
		0, 98, 549, 1, 0, 0, 0, 100, 559, 1, 0, 0, 0, 102, 561, 1, 0, 0, 0, 104,
		581, 1, 0, 0, 0, 106, 605, 1, 0, 0, 0, 108, 607, 1, 0, 0, 0, 110, 617,
		1, 0, 0, 0, 112, 625, 1, 0, 0, 0, 114, 639, 1, 0, 0, 0, 116, 644, 1, 0,
		0, 0, 118, 647, 1, 0, 0, 0, 120, 661, 1, 0, 0, 0, 122, 672, 1, 0, 0, 0,
		124, 683, 1, 0, 0, 0, 126, 685, 1, 0, 0, 0, 128, 687, 1, 0, 0, 0, 130,
		689, 1, 0, 0, 0, 132, 691, 1, 0, 0, 0, 134, 697, 1, 0, 0, 0, 136, 710,
		1, 0, 0, 0, 138, 714, 1, 0, 0, 0, 140, 722, 1, 0, 0, 0, 142, 724, 1, 0,
		0, 0, 144, 726, 1, 0, 0, 0, 146, 728, 1, 0, 0, 0, 148, 730, 1, 0, 0, 0,
		150, 155, 3, 2, 1, 0, 151, 152, 5, 10, 0, 0, 152, 154, 3, 2, 1, 0, 153,
		151, 1, 0, 0, 0, 154, 157, 1, 0, 0, 0, 155, 153, 1, 0, 0, 0, 155, 156,
		1, 0, 0, 0, 156, 159, 1, 0, 0, 0, 157, 155, 1, 0, 0, 0, 158, 160, 5, 10,
		0, 0, 159, 158, 1, 0, 0, 0, 159, 160, 1, 0, 0, 0, 160, 161, 1, 0, 0, 0,
		161, 162, 5, 0, 0, 1, 162, 1, 1, 0, 0, 0, 163, 164, 3, 4, 2, 0, 164, 3,
		1, 0, 0, 0, 165, 166, 3, 6, 3, 0, 166, 5, 1, 0, 0, 0, 167, 173, 3, 10,
		5, 0, 168, 169, 3, 8, 4, 0, 169, 170, 3, 10, 5, 0, 170, 172, 1, 0, 0, 0,
		171, 168, 1, 0, 0, 0, 172, 175, 1, 0, 0, 0, 173, 171, 1, 0, 0, 0, 173,
		174, 1, 0, 0, 0, 174, 7, 1, 0, 0, 0, 175, 173, 1, 0, 0, 0, 176, 178, 5,
		58, 0, 0, 177, 179, 5, 36, 0, 0, 178, 177, 1, 0, 0, 0, 178, 179, 1, 0,
		0, 0, 179, 9, 1, 0, 0, 0, 180, 182, 3, 12, 6, 0, 181, 180, 1, 0, 0, 0,
		182, 183, 1, 0, 0, 0, 183, 181, 1, 0, 0, 0, 183, 184, 1, 0, 0, 0, 184,
		11, 1, 0, 0, 0, 185, 195, 3, 14, 7, 0, 186, 195, 3, 16, 8, 0, 187, 195,
		3, 18, 9, 0, 188, 195, 3, 20, 10, 0, 189, 195, 3, 36, 18, 0, 190, 195,
		3, 38, 19, 0, 191, 195, 3, 40, 20, 0, 192, 195, 3, 44, 22, 0, 193, 195,
		3, 48, 24, 0, 194, 185, 1, 0, 0, 0, 194, 186, 1, 0, 0, 0, 194, 187, 1,
		0, 0, 0, 194, 188, 1, 0, 0, 0, 194, 189, 1, 0, 0, 0, 194, 190, 1, 0, 0,
		0, 194, 191, 1, 0, 0, 0, 194, 192, 1, 0, 0, 0, 194, 193, 1, 0, 0, 0, 195,
		13, 1, 0, 0, 0, 196, 198, 5, 50, 0, 0, 197, 196, 1, 0, 0, 0, 197, 198,
		1, 0, 0, 0, 198, 199, 1, 0, 0, 0, 199, 200, 5, 47, 0, 0, 200, 202, 3, 50,
		25, 0, 201, 203, 3, 22, 11, 0, 202, 201, 1, 0, 0, 0, 202, 203, 1, 0, 0,
		0, 203, 15, 1, 0, 0, 0, 204, 205, 5, 59, 0, 0, 205, 206, 3, 76, 38, 0,
		206, 207, 5, 61, 0, 0, 207, 208, 3, 140, 70, 0, 208, 17, 1, 0, 0, 0, 209,
		211, 5, 57, 0, 0, 210, 212, 5, 63, 0, 0, 211, 210, 1, 0, 0, 0, 211, 212,
		1, 0, 0, 0, 212, 213, 1, 0, 0, 0, 213, 215, 3, 32, 16, 0, 214, 216, 3,
		22, 11, 0, 215, 214, 1, 0, 0, 0, 215, 216, 1, 0, 0, 0, 216, 218, 1, 0,
		0, 0, 217, 219, 3, 24, 12, 0, 218, 217, 1, 0, 0, 0, 218, 219, 1, 0, 0,
		0, 219, 221, 1, 0, 0, 0, 220, 222, 3, 28, 14, 0, 221, 220, 1, 0, 0, 0,
		221, 222, 1, 0, 0, 0, 222, 224, 1, 0, 0, 0, 223, 225, 3, 30, 15, 0, 224,
		223, 1, 0, 0, 0, 224, 225, 1, 0, 0, 0, 225, 19, 1, 0, 0, 0, 226, 228, 5,
		53, 0, 0, 227, 229, 5, 63, 0, 0, 228, 227, 1, 0, 0, 0, 228, 229, 1, 0,
		0, 0, 229, 230, 1, 0, 0, 0, 230, 232, 3, 32, 16, 0, 231, 233, 3, 24, 12,
		0, 232, 231, 1, 0, 0, 0, 232, 233, 1, 0, 0, 0, 233, 235, 1, 0, 0, 0, 234,
		236, 3, 28, 14, 0, 235, 234, 1, 0, 0, 0, 235, 236, 1, 0, 0, 0, 236, 238,
		1, 0, 0, 0, 237, 239, 3, 30, 15, 0, 238, 237, 1, 0, 0, 0, 238, 239, 1,
		0, 0, 0, 239, 21, 1, 0, 0, 0, 240, 241, 5, 56, 0, 0, 241, 242, 3, 76, 38,
		0, 242, 23, 1, 0, 0, 0, 243, 244, 5, 51, 0, 0, 244, 245, 5, 39, 0, 0, 245,
		250, 3, 26, 13, 0, 246, 247, 5, 12, 0, 0, 247, 249, 3, 26, 13, 0, 248,
		246, 1, 0, 0, 0, 249, 252, 1, 0, 0, 0, 250, 248, 1, 0, 0, 0, 250, 251,
		1, 0, 0, 0, 251, 25, 1, 0, 0, 0, 252, 250, 1, 0, 0, 0, 253, 255, 3, 76,
		38, 0, 254, 256, 7, 0, 0, 0, 255, 254, 1, 0, 0, 0, 255, 256, 1, 0, 0, 0,
		256, 27, 1, 0, 0, 0, 257, 258, 5, 55, 0, 0, 258, 259, 3, 76, 38, 0, 259,
		29, 1, 0, 0, 0, 260, 261, 5, 46, 0, 0, 261, 262, 3, 76, 38, 0, 262, 31,
		1, 0, 0, 0, 263, 273, 5, 24, 0, 0, 264, 269, 3, 34, 17, 0, 265, 266, 5,
		12, 0, 0, 266, 268, 3, 34, 17, 0, 267, 265, 1, 0, 0, 0, 268, 271, 1, 0,
		0, 0, 269, 267, 1, 0, 0, 0, 269, 270, 1, 0, 0, 0, 270, 273, 1, 0, 0, 0,
		271, 269, 1, 0, 0, 0, 272, 263, 1, 0, 0, 0, 272, 264, 1, 0, 0, 0, 273,
		33, 1, 0, 0, 0, 274, 277, 3, 76, 38, 0, 275, 276, 5, 61, 0, 0, 276, 278,
		3, 140, 70, 0, 277, 275, 1, 0, 0, 0, 277, 278, 1, 0, 0, 0, 278, 35, 1,
		0, 0, 0, 279, 280, 5, 40, 0, 0, 280, 281, 3, 50, 25, 0, 281, 37, 1, 0,
		0, 0, 282, 284, 5, 44, 0, 0, 283, 282, 1, 0, 0, 0, 283, 284, 1, 0, 0, 0,
		284, 285, 1, 0, 0, 0, 285, 286, 5, 41, 0, 0, 286, 287, 3, 138, 69, 0, 287,
		39, 1, 0, 0, 0, 288, 289, 5, 54, 0, 0, 289, 294, 3, 42, 21, 0, 290, 291,
		5, 12, 0, 0, 291, 293, 3, 42, 21, 0, 292, 290, 1, 0, 0, 0, 293, 296, 1,
		0, 0, 0, 294, 292, 1, 0, 0, 0, 294, 295, 1, 0, 0, 0, 295, 41, 1, 0, 0,
		0, 296, 294, 1, 0, 0, 0, 297, 298, 3, 140, 70, 0, 298, 299, 5, 11, 0, 0,
		299, 300, 3, 146, 73, 0, 300, 301, 5, 1, 0, 0, 301, 302, 3, 76, 38, 0,
		302, 315, 1, 0, 0, 0, 303, 304, 3, 140, 70, 0, 304, 305, 5, 2, 0, 0, 305,
		306, 3, 76, 38, 0, 306, 315, 1, 0, 0, 0, 307, 308, 3, 140, 70, 0, 308,
		309, 5, 1, 0, 0, 309, 310, 3, 76, 38, 0, 310, 315, 1, 0, 0, 0, 311, 312,
		3, 140, 70, 0, 312, 313, 3, 58, 29, 0, 313, 315, 1, 0, 0, 0, 314, 297,
		1, 0, 0, 0, 314, 303, 1, 0, 0, 0, 314, 307, 1, 0, 0, 0, 314, 311, 1, 0,
		0, 0, 315, 43, 1, 0, 0, 0, 316, 317, 5, 52, 0, 0, 317, 322, 3, 46, 23,
		0, 318, 319, 5, 12, 0, 0, 319, 321, 3, 46, 23, 0, 320, 318, 1, 0, 0, 0,
		321, 324, 1, 0, 0, 0, 322, 320, 1, 0, 0, 0, 322, 323, 1, 0, 0, 0, 323,
		45, 1, 0, 0, 0, 324, 322, 1, 0, 0, 0, 325, 326, 3, 140, 70, 0, 326, 327,
		5, 11, 0, 0, 327, 328, 3, 146, 73, 0, 328, 333, 1, 0, 0, 0, 329, 330, 3,
		140, 70, 0, 330, 331, 3, 58, 29, 0, 331, 333, 1, 0, 0, 0, 332, 325, 1,
		0, 0, 0, 332, 329, 1, 0, 0, 0, 333, 47, 1, 0, 0, 0, 334, 335, 5, 48, 0,
		0, 335, 336, 3, 52, 26, 0, 336, 49, 1, 0, 0, 0, 337, 342, 3, 52, 26, 0,
		338, 339, 5, 12, 0, 0, 339, 341, 3, 52, 26, 0, 340, 338, 1, 0, 0, 0, 341,
		344, 1, 0, 0, 0, 342, 340, 1, 0, 0, 0, 342, 343, 1, 0, 0, 0, 343, 51, 1,
		0, 0, 0, 344, 342, 1, 0, 0, 0, 345, 346, 3, 140, 70, 0, 346, 347, 5, 1,
		0, 0, 347, 349, 1, 0, 0, 0, 348, 345, 1, 0, 0, 0, 348, 349, 1, 0, 0, 0,
		349, 350, 1, 0, 0, 0, 350, 351, 3, 54, 27, 0, 351, 53, 1, 0, 0, 0, 352,
		358, 3, 56, 28, 0, 353, 354, 3, 60, 30, 0, 354, 355, 3, 56, 28, 0, 355,
		357, 1, 0, 0, 0, 356, 353, 1, 0, 0, 0, 357, 360, 1, 0, 0, 0, 358, 356,
		1, 0, 0, 0, 358, 359, 1, 0, 0, 0, 359, 55, 1, 0, 0, 0, 360, 358, 1, 0,
		0, 0, 361, 363, 5, 13, 0, 0, 362, 364, 3, 140, 70, 0, 363, 362, 1, 0, 0,
		0, 363, 364, 1, 0, 0, 0, 364, 366, 1, 0, 0, 0, 365, 367, 3, 58, 29, 0,
		366, 365, 1, 0, 0, 0, 366, 367, 1, 0, 0, 0, 367, 369, 1, 0, 0, 0, 368,
		370, 3, 74, 37, 0, 369, 368, 1, 0, 0, 0, 369, 370, 1, 0, 0, 0, 370, 371,
		1, 0, 0, 0, 371, 372, 5, 14, 0, 0, 372, 57, 1, 0, 0, 0, 373, 374, 5, 25,
		0, 0, 374, 376, 3, 142, 71, 0, 375, 373, 1, 0, 0, 0, 376, 377, 1, 0, 0,
		0, 377, 375, 1, 0, 0, 0, 377, 378, 1, 0, 0, 0, 378, 59, 1, 0, 0, 0, 379,
		380, 3, 62, 31, 0, 380, 382, 3, 66, 33, 0, 381, 383, 3, 68, 34, 0, 382,
		381, 1, 0, 0, 0, 382, 383, 1, 0, 0, 0, 383, 384, 1, 0, 0, 0, 384, 385,
		3, 66, 33, 0, 385, 386, 3, 64, 32, 0, 386, 408, 1, 0, 0, 0, 387, 388, 3,
		62, 31, 0, 388, 390, 3, 66, 33, 0, 389, 391, 3, 68, 34, 0, 390, 389, 1,
		0, 0, 0, 390, 391, 1, 0, 0, 0, 391, 392, 1, 0, 0, 0, 392, 393, 3, 66, 33,
		0, 393, 408, 1, 0, 0, 0, 394, 396, 3, 66, 33, 0, 395, 397, 3, 68, 34, 0,
		396, 395, 1, 0, 0, 0, 396, 397, 1, 0, 0, 0, 397, 398, 1, 0, 0, 0, 398,
		399, 3, 66, 33, 0, 399, 400, 3, 64, 32, 0, 400, 408, 1, 0, 0, 0, 401, 403,
		3, 66, 33, 0, 402, 404, 3, 68, 34, 0, 403, 402, 1, 0, 0, 0, 403, 404, 1,
		0, 0, 0, 404, 405, 1, 0, 0, 0, 405, 406, 3, 66, 33, 0, 406, 408, 1, 0,
		0, 0, 407, 379, 1, 0, 0, 0, 407, 387, 1, 0, 0, 0, 407, 394, 1, 0, 0, 0,
		407, 401, 1, 0, 0, 0, 408, 61, 1, 0, 0, 0, 409, 410, 5, 8, 0, 0, 410, 63,
		1, 0, 0, 0, 411, 412, 5, 7, 0, 0, 412, 65, 1, 0, 0, 0, 413, 414, 5, 19,
		0, 0, 414, 67, 1, 0, 0, 0, 415, 417, 5, 17, 0, 0, 416, 418, 3, 140, 70,
		0, 417, 416, 1, 0, 0, 0, 417, 418, 1, 0, 0, 0, 418, 420, 1, 0, 0, 0, 419,
		421, 3, 70, 35, 0, 420, 419, 1, 0, 0, 0, 420, 421, 1, 0, 0, 0, 421, 423,
		1, 0, 0, 0, 422, 424, 3, 72, 36, 0, 423, 422, 1, 0, 0, 0, 423, 424, 1,
		0, 0, 0, 424, 426, 1, 0, 0, 0, 425, 427, 3, 74, 37, 0, 426, 425, 1, 0,
		0, 0, 426, 427, 1, 0, 0, 0, 427, 428, 1, 0, 0, 0, 428, 429, 5, 18, 0, 0,
		429, 69, 1, 0, 0, 0, 430, 431, 5, 25, 0, 0, 431, 439, 3, 144, 72, 0, 432,
		434, 5, 26, 0, 0, 433, 435, 5, 25, 0, 0, 434, 433, 1, 0, 0, 0, 434, 435,
		1, 0, 0, 0, 435, 436, 1, 0, 0, 0, 436, 438, 3, 144, 72, 0, 437, 432, 1,
		0, 0, 0, 438, 441, 1, 0, 0, 0, 439, 437, 1, 0, 0, 0, 439, 440, 1, 0, 0,
		0, 440, 71, 1, 0, 0, 0, 441, 439, 1, 0, 0, 0, 442, 444, 5, 24, 0, 0, 443,
		445, 3, 126, 63, 0, 444, 443, 1, 0, 0, 0, 444, 445, 1, 0, 0, 0, 445, 450,
		1, 0, 0, 0, 446, 448, 5, 9, 0, 0, 447, 449, 3, 126, 63, 0, 448, 447, 1,
		0, 0, 0, 448, 449, 1, 0, 0, 0, 449, 451, 1, 0, 0, 0, 450, 446, 1, 0, 0,
		0, 450, 451, 1, 0, 0, 0, 451, 73, 1, 0, 0, 0, 452, 453, 3, 134, 67, 0,
		453, 75, 1, 0, 0, 0, 454, 455, 3, 78, 39, 0, 455, 77, 1, 0, 0, 0, 456,
		461, 3, 80, 40, 0, 457, 458, 5, 68, 0, 0, 458, 460, 3, 80, 40, 0, 459,
		457, 1, 0, 0, 0, 460, 463, 1, 0, 0, 0, 461, 459, 1, 0, 0, 0, 461, 462,
		1, 0, 0, 0, 462, 79, 1, 0, 0, 0, 463, 461, 1, 0, 0, 0, 464, 469, 3, 82,
		41, 0, 465, 466, 5, 70, 0, 0, 466, 468, 3, 82, 41, 0, 467, 465, 1, 0, 0,
		0, 468, 471, 1, 0, 0, 0, 469, 467, 1, 0, 0, 0, 469, 470, 1, 0, 0, 0, 470,
		81, 1, 0, 0, 0, 471, 469, 1, 0, 0, 0, 472, 477, 3, 84, 42, 0, 473, 474,
		5, 60, 0, 0, 474, 476, 3, 84, 42, 0, 475, 473, 1, 0, 0, 0, 476, 479, 1,
		0, 0, 0, 477, 475, 1, 0, 0, 0, 477, 478, 1, 0, 0, 0, 478, 83, 1, 0, 0,
		0, 479, 477, 1, 0, 0, 0, 480, 481, 5, 67, 0, 0, 481, 484, 3, 84, 42, 0,
		482, 484, 3, 86, 43, 0, 483, 480, 1, 0, 0, 0, 483, 482, 1, 0, 0, 0, 484,
		85, 1, 0, 0, 0, 485, 491, 3, 90, 45, 0, 486, 487, 3, 88, 44, 0, 487, 488,
		3, 90, 45, 0, 488, 490, 1, 0, 0, 0, 489, 486, 1, 0, 0, 0, 490, 493, 1,
		0, 0, 0, 491, 489, 1, 0, 0, 0, 491, 492, 1, 0, 0, 0, 492, 87, 1, 0, 0,
		0, 493, 491, 1, 0, 0, 0, 494, 495, 7, 1, 0, 0, 495, 89, 1, 0, 0, 0, 496,
		500, 3, 94, 47, 0, 497, 499, 3, 92, 46, 0, 498, 497, 1, 0, 0, 0, 499, 502,
		1, 0, 0, 0, 500, 498, 1, 0, 0, 0, 500, 501, 1, 0, 0, 0, 501, 91, 1, 0,
		0, 0, 502, 500, 1, 0, 0, 0, 503, 504, 5, 69, 0, 0, 504, 505, 5, 57, 0,
		0, 505, 532, 3, 94, 47, 0, 506, 507, 5, 64, 0, 0, 507, 508, 5, 57, 0, 0,
		508, 532, 3, 94, 47, 0, 509, 510, 5, 62, 0, 0, 510, 532, 3, 94, 47, 0,
		511, 512, 5, 65, 0, 0, 512, 532, 3, 94, 47, 0, 513, 514, 5, 3, 0, 0, 514,
		532, 3, 94, 47, 0, 515, 516, 5, 66, 0, 0, 516, 517, 5, 67, 0, 0, 517, 532,
		5, 73, 0, 0, 518, 519, 5, 66, 0, 0, 519, 532, 5, 73, 0, 0, 520, 521, 5,
		67, 0, 0, 521, 522, 5, 62, 0, 0, 522, 532, 3, 94, 47, 0, 523, 524, 5, 67,
		0, 0, 524, 525, 5, 69, 0, 0, 525, 526, 5, 57, 0, 0, 526, 532, 3, 94, 47,
		0, 527, 528, 5, 67, 0, 0, 528, 529, 5, 64, 0, 0, 529, 530, 5, 57, 0, 0,
		530, 532, 3, 94, 47, 0, 531, 503, 1, 0, 0, 0, 531, 506, 1, 0, 0, 0, 531,
		509, 1, 0, 0, 0, 531, 511, 1, 0, 0, 0, 531, 513, 1, 0, 0, 0, 531, 515,
		1, 0, 0, 0, 531, 518, 1, 0, 0, 0, 531, 520, 1, 0, 0, 0, 531, 523, 1, 0,
		0, 0, 531, 527, 1, 0, 0, 0, 532, 93, 1, 0, 0, 0, 533, 538, 3, 96, 48, 0,
		534, 535, 7, 2, 0, 0, 535, 537, 3, 96, 48, 0, 536, 534, 1, 0, 0, 0, 537,
		540, 1, 0, 0, 0, 538, 536, 1, 0, 0, 0, 538, 539, 1, 0, 0, 0, 539, 95, 1,
		0, 0, 0, 540, 538, 1, 0, 0, 0, 541, 546, 3, 98, 49, 0, 542, 543, 7, 3,
		0, 0, 543, 545, 3, 98, 49, 0, 544, 542, 1, 0, 0, 0, 545, 548, 1, 0, 0,
		0, 546, 544, 1, 0, 0, 0, 546, 547, 1, 0, 0, 0, 547, 97, 1, 0, 0, 0, 548,
		546, 1, 0, 0, 0, 549, 552, 3, 100, 50, 0, 550, 551, 5, 23, 0, 0, 551, 553,
		3, 98, 49, 0, 552, 550, 1, 0, 0, 0, 552, 553, 1, 0, 0, 0, 553, 99, 1, 0,
		0, 0, 554, 555, 5, 19, 0, 0, 555, 560, 3, 100, 50, 0, 556, 557, 5, 20,
		0, 0, 557, 560, 3, 100, 50, 0, 558, 560, 3, 102, 51, 0, 559, 554, 1, 0,
		0, 0, 559, 556, 1, 0, 0, 0, 559, 558, 1, 0, 0, 0, 560, 101, 1, 0, 0, 0,
		561, 570, 3, 106, 53, 0, 562, 563, 5, 11, 0, 0, 563, 569, 3, 146, 73, 0,
		564, 565, 5, 17, 0, 0, 565, 566, 3, 104, 52, 0, 566, 567, 5, 18, 0, 0,
		567, 569, 1, 0, 0, 0, 568, 562, 1, 0, 0, 0, 568, 564, 1, 0, 0, 0, 569,
		572, 1, 0, 0, 0, 570, 568, 1, 0, 0, 0, 570, 571, 1, 0, 0, 0, 571, 103,
		1, 0, 0, 0, 572, 570, 1, 0, 0, 0, 573, 574, 3, 76, 38, 0, 574, 576, 5,
		9, 0, 0, 575, 577, 3, 76, 38, 0, 576, 575, 1, 0, 0, 0, 576, 577, 1, 0,
		0, 0, 577, 582, 1, 0, 0, 0, 578, 579, 5, 9, 0, 0, 579, 582, 3, 76, 38,
		0, 580, 582, 3, 76, 38, 0, 581, 573, 1, 0, 0, 0, 581, 578, 1, 0, 0, 0,
		581, 580, 1, 0, 0, 0, 582, 105, 1, 0, 0, 0, 583, 606, 3, 122, 61, 0, 584,
		606, 3, 112, 56, 0, 585, 586, 5, 32, 0, 0, 586, 587, 5, 13, 0, 0, 587,
		588, 5, 24, 0, 0, 588, 606, 5, 14, 0, 0, 589, 606, 3, 118, 59, 0, 590,
		606, 3, 120, 60, 0, 591, 592, 5, 45, 0, 0, 592, 593, 5, 13, 0, 0, 593,
		594, 3, 76, 38, 0, 594, 595, 5, 14, 0, 0, 595, 606, 1, 0, 0, 0, 596, 606,
		3, 108, 54, 0, 597, 598, 5, 13, 0, 0, 598, 599, 3, 76, 38, 0, 599, 600,
		5, 14, 0, 0, 600, 606, 1, 0, 0, 0, 601, 602, 5, 63, 0, 0, 602, 606, 3,
		100, 50, 0, 603, 606, 3, 124, 62, 0, 604, 606, 3, 140, 70, 0, 605, 583,
		1, 0, 0, 0, 605, 584, 1, 0, 0, 0, 605, 585, 1, 0, 0, 0, 605, 589, 1, 0,
		0, 0, 605, 590, 1, 0, 0, 0, 605, 591, 1, 0, 0, 0, 605, 596, 1, 0, 0, 0,
		605, 597, 1, 0, 0, 0, 605, 601, 1, 0, 0, 0, 605, 603, 1, 0, 0, 0, 605,
		604, 1, 0, 0, 0, 606, 107, 1, 0, 0, 0, 607, 608, 3, 110, 55, 0, 608, 610,
		5, 13, 0, 0, 609, 611, 5, 63, 0, 0, 610, 609, 1, 0, 0, 0, 610, 611, 1,
		0, 0, 0, 611, 613, 1, 0, 0, 0, 612, 614, 3, 138, 69, 0, 613, 612, 1, 0,
		0, 0, 613, 614, 1, 0, 0, 0, 614, 615, 1, 0, 0, 0, 615, 616, 5, 14, 0, 0,
		616, 109, 1, 0, 0, 0, 617, 622, 3, 148, 74, 0, 618, 619, 5, 11, 0, 0, 619,
		621, 3, 148, 74, 0, 620, 618, 1, 0, 0, 0, 621, 624, 1, 0, 0, 0, 622, 620,
		1, 0, 0, 0, 622, 623, 1, 0, 0, 0, 623, 111, 1, 0, 0, 0, 624, 622, 1, 0,
		0, 0, 625, 627, 5, 79, 0, 0, 626, 628, 3, 76, 38, 0, 627, 626, 1, 0, 0,
		0, 627, 628, 1, 0, 0, 0, 628, 630, 1, 0, 0, 0, 629, 631, 3, 114, 57, 0,
		630, 629, 1, 0, 0, 0, 631, 632, 1, 0, 0, 0, 632, 630, 1, 0, 0, 0, 632,
		633, 1, 0, 0, 0, 633, 635, 1, 0, 0, 0, 634, 636, 3, 116, 58, 0, 635, 634,
		1, 0, 0, 0, 635, 636, 1, 0, 0, 0, 636, 637, 1, 0, 0, 0, 637, 638, 5, 83,
		0, 0, 638, 113, 1, 0, 0, 0, 639, 640, 5, 80, 0, 0, 640, 641, 3, 76, 38,
		0, 641, 642, 5, 81, 0, 0, 642, 643, 3, 76, 38, 0, 643, 115, 1, 0, 0, 0,
		644, 645, 5, 82, 0, 0, 645, 646, 3, 76, 38, 0, 646, 117, 1, 0, 0, 0, 647,
		648, 5, 17, 0, 0, 648, 649, 3, 140, 70, 0, 649, 650, 5, 65, 0, 0, 650,
		653, 3, 76, 38, 0, 651, 652, 5, 56, 0, 0, 652, 654, 3, 76, 38, 0, 653,
		651, 1, 0, 0, 0, 653, 654, 1, 0, 0, 0, 654, 657, 1, 0, 0, 0, 655, 656,
		5, 26, 0, 0, 656, 658, 3, 76, 38, 0, 657, 655, 1, 0, 0, 0, 657, 658, 1,
		0, 0, 0, 658, 659, 1, 0, 0, 0, 659, 660, 5, 18, 0, 0, 660, 119, 1, 0, 0,
		0, 661, 662, 7, 4, 0, 0, 662, 663, 5, 13, 0, 0, 663, 664, 3, 140, 70, 0,
		664, 665, 5, 65, 0, 0, 665, 668, 3, 76, 38, 0, 666, 667, 5, 56, 0, 0, 667,
		669, 3, 76, 38, 0, 668, 666, 1, 0, 0, 0, 668, 669, 1, 0, 0, 0, 669, 670,
		1, 0, 0, 0, 670, 671, 5, 14, 0, 0, 671, 121, 1, 0, 0, 0, 672, 673, 5, 27,
		0, 0, 673, 674, 3, 148, 74, 0, 674, 123, 1, 0, 0, 0, 675, 684, 5, 72, 0,
		0, 676, 684, 5, 71, 0, 0, 677, 684, 5, 73, 0, 0, 678, 684, 3, 126, 63,
		0, 679, 684, 3, 128, 64, 0, 680, 684, 3, 130, 65, 0, 681, 684, 3, 132,
		66, 0, 682, 684, 3, 134, 67, 0, 683, 675, 1, 0, 0, 0, 683, 676, 1, 0, 0,
		0, 683, 677, 1, 0, 0, 0, 683, 678, 1, 0, 0, 0, 683, 679, 1, 0, 0, 0, 683,
		680, 1, 0, 0, 0, 683, 681, 1, 0, 0, 0, 683, 682, 1, 0, 0, 0, 684, 125,
		1, 0, 0, 0, 685, 686, 7, 5, 0, 0, 686, 127, 1, 0, 0, 0, 687, 688, 5, 94,
		0, 0, 688, 129, 1, 0, 0, 0, 689, 690, 5, 91, 0, 0, 690, 131, 1, 0, 0, 0,
		691, 693, 5, 17, 0, 0, 692, 694, 3, 138, 69, 0, 693, 692, 1, 0, 0, 0, 693,
		694, 1, 0, 0, 0, 694, 695, 1, 0, 0, 0, 695, 696, 5, 18, 0, 0, 696, 133,
		1, 0, 0, 0, 697, 706, 5, 15, 0, 0, 698, 703, 3, 136, 68, 0, 699, 700, 5,
		12, 0, 0, 700, 702, 3, 136, 68, 0, 701, 699, 1, 0, 0, 0, 702, 705, 1, 0,
		0, 0, 703, 701, 1, 0, 0, 0, 703, 704, 1, 0, 0, 0, 704, 707, 1, 0, 0, 0,
		705, 703, 1, 0, 0, 0, 706, 698, 1, 0, 0, 0, 706, 707, 1, 0, 0, 0, 707,
		708, 1, 0, 0, 0, 708, 709, 5, 16, 0, 0, 709, 135, 1, 0, 0, 0, 710, 711,
		3, 146, 73, 0, 711, 712, 5, 25, 0, 0, 712, 713, 3, 76, 38, 0, 713, 137,
		1, 0, 0, 0, 714, 719, 3, 76, 38, 0, 715, 716, 5, 12, 0, 0, 716, 718, 3,
		76, 38, 0, 717, 715, 1, 0, 0, 0, 718, 721, 1, 0, 0, 0, 719, 717, 1, 0,
		0, 0, 719, 720, 1, 0, 0, 0, 720, 139, 1, 0, 0, 0, 721, 719, 1, 0, 0, 0,
		722, 723, 3, 148, 74, 0, 723, 141, 1, 0, 0, 0, 724, 725, 3, 148, 74, 0,
		725, 143, 1, 0, 0, 0, 726, 727, 3, 148, 74, 0, 727, 145, 1, 0, 0, 0, 728,
		729, 3, 148, 74, 0, 729, 147, 1, 0, 0, 0, 730, 731, 7, 6, 0, 0, 731, 149,
		1, 0, 0, 0, 78, 155, 159, 173, 178, 183, 194, 197, 202, 211, 215, 218,
		221, 224, 228, 232, 235, 238, 250, 255, 269, 272, 277, 283, 294, 314, 322,
		332, 342, 348, 358, 363, 366, 369, 377, 382, 390, 396, 403, 407, 417, 420,
		423, 426, 434, 439, 444, 448, 450, 461, 469, 477, 483, 491, 500, 531, 538,
		546, 552, 559, 568, 570, 576, 581, 605, 610, 613, 622, 627, 632, 635, 653,
		657, 668, 683, 693, 703, 706, 719,
	}
	deserializer := antlr.NewATNDeserializer(nil)
	staticData.atn = deserializer.Deserialize(staticData.serializedATN)
	atn := staticData.atn
	staticData.decisionToDFA = make([]*antlr.DFA, len(atn.DecisionToState))
	decisionToDFA := staticData.decisionToDFA
	for index, state := range atn.DecisionToState {
		decisionToDFA[index] = antlr.NewDFA(state, index)
	}
}

// CypherParserInit initializes any static state used to implement CypherParser. By default the
// static state used to implement the parser is lazily initialized during the first call to
// NewCypherParser(). You can call this function if you wish to initialize the static state ahead
// of time.
func CypherParserInit() {
	staticData := &CypherParserParserStaticData
	staticData.once.Do(cypherparserParserInit)
}

// NewCypherParser produces a new parser instance for the optional input antlr.TokenStream.
func NewCypherParser(input antlr.TokenStream) *CypherParser {
	CypherParserInit()
	this := new(CypherParser)
	this.BaseParser = antlr.NewBaseParser(input)
	staticData := &CypherParserParserStaticData
	this.Interpreter = antlr.NewParserATNSimulator(this, staticData.atn, staticData.decisionToDFA, staticData.PredictionContextCache)
	this.RuleNames = staticData.RuleNames
	this.LiteralNames = staticData.LiteralNames
	this.SymbolicNames = staticData.SymbolicNames
	this.GrammarFileName = "CypherParser.g4"

	return this
}

// CypherParser tokens.
const (
	CypherParserEOF             = antlr.TokenEOF
	CypherParserASSIGN          = 1
	CypherParserADD_ASSIGN      = 2
	CypherParserREGEX           = 3
	CypherParserLE              = 4
	CypherParserGE              = 5
	CypherParserNOT_EQUAL       = 6
	CypherParserGT              = 7
	CypherParserLT              = 8
	CypherParserRANGE           = 9
	CypherParserSEMI            = 10
	CypherParserDOT             = 11
	CypherParserCOMMA           = 12
	CypherParserLPAREN          = 13
	CypherParserRPAREN          = 14
	CypherParserLBRACE          = 15
	CypherParserRBRACE          = 16
	CypherParserLBRACK          = 17
	CypherParserRBRACK          = 18
	CypherParserSUB             = 19
	CypherParserPLUS            = 20
	CypherParserDIV             = 21
	CypherParserMOD             = 22
	CypherParserCARET           = 23
	CypherParserMULT            = 24
	CypherParserCOLON           = 25
	CypherParserSTICK           = 26
	CypherParserDOLLAR          = 27
	CypherParserCALL            = 28
	CypherParserYIELD           = 29
	CypherParserFILTER          = 30
	CypherParserEXTRACT         = 31
	CypherParserCOUNT           = 32
	CypherParserANY             = 33
	CypherParserNONE            = 34
	CypherParserSINGLE          = 35
	CypherParserALL             = 36
	CypherParserASC             = 37
	CypherParserASCENDING       = 38
	CypherParserBY              = 39
	CypherParserCREATE          = 40
	CypherParserDELETE          = 41
	CypherParserDESC            = 42
	CypherParserDESCENDING      = 43
	CypherParserDETACH          = 44
	CypherParserEXISTS          = 45
	CypherParserLIMIT           = 46
	CypherParserMATCH           = 47
	CypherParserMERGE           = 48
	CypherParserON              = 49
	CypherParserOPTIONAL        = 50
	CypherParserORDER           = 51
	CypherParserREMOVE          = 52
	CypherParserRETURN          = 53
	CypherParserSET             = 54
	CypherParserSKIP_W          = 55
	CypherParserWHERE           = 56
	CypherParserWITH            = 57
	CypherParserUNION           = 58
	CypherParserUNWIND          = 59
	CypherParserAND             = 60
	CypherParserAS              = 61
	CypherParserCONTAINS        = 62
	CypherParserDISTINCT        = 63
	CypherParserENDS            = 64
	CypherParserIN              = 65
	CypherParserIS              = 66
	CypherParserNOT             = 67
	CypherParserOR              = 68
	CypherParserSTARTS          = 69
	CypherParserXOR             = 70
	CypherParserFALSE           = 71
	CypherParserTRUE            = 72
	CypherParserNULL_W          = 73
	CypherParserCONSTRAINT      = 74
	CypherParserDO              = 75
	CypherParserFOR             = 76
	CypherParserREQUIRE         = 77
	CypherParserUNIQUE          = 78
	CypherParserCASE            = 79
	CypherParserWHEN            = 80
	CypherParserTHEN            = 81
	CypherParserELSE            = 82
	CypherParserEND             = 83
	CypherParserMANDATORY       = 84
	CypherParserSCALAR          = 85
	CypherParserOF              = 86
	CypherParserADD             = 87
	CypherParserDROP            = 88
	CypherParserID              = 89
	CypherParserESC_LITERAL     = 90
	CypherParserSTRING_LITERAL  = 91
	CypherParserHEX_INTEGER     = 92
	CypherParserOCTAL_INTEGER   = 93
	CypherParserFLOAT_LITERAL   = 94
	CypherParserINTEGER_LITERAL = 95
	CypherParserWS              = 96
	CypherParserCOMMENT         = 97
	CypherParserLINE_COMMENT    = 98
)

// CypherParser rules.
const (
	CypherParserRULE_script                    = 0
	CypherParserRULE_statement                 = 1
	CypherParserRULE_query                     = 2
	CypherParserRULE_regularQuery              = 3
	CypherParserRULE_unionSt                   = 4
	CypherParserRULE_singleQuery               = 5
	CypherParserRULE_clause                    = 6
	CypherParserRULE_matchSt                   = 7
	CypherParserRULE_unwindSt                  = 8
	CypherParserRULE_withSt                    = 9
	CypherParserRULE_returnSt                  = 10
	CypherParserRULE_whereSt                   = 11
	CypherParserRULE_orderBySt                 = 12
	CypherParserRULE_orderItem                 = 13
	CypherParserRULE_skipSt                    = 14
	CypherParserRULE_limitSt                   = 15
	CypherParserRULE_returnItems               = 16
	CypherParserRULE_returnItem                = 17
	CypherParserRULE_createSt                  = 18
	CypherParserRULE_deleteSt                  = 19
	CypherParserRULE_setSt                     = 20
	CypherParserRULE_setItem                   = 21
	CypherParserRULE_removeSt                  = 22
	CypherParserRULE_removeItem                = 23
	CypherParserRULE_mergeSt                   = 24
	CypherParserRULE_patternList               = 25
	CypherParserRULE_patternPart               = 26
	CypherParserRULE_patternElement            = 27
	CypherParserRULE_nodePattern               = 28
	CypherParserRULE_nodeLabels                = 29
	CypherParserRULE_relationshipPattern       = 30
	CypherParserRULE_leftArrow                 = 31
	CypherParserRULE_rightArrow                = 32
	CypherParserRULE_dash                      = 33
	CypherParserRULE_relationDetail            = 34
	CypherParserRULE_relationshipTypes         = 35
	CypherParserRULE_rangeLiteral              = 36
	CypherParserRULE_properties                = 37
	CypherParserRULE_expression                = 38
	CypherParserRULE_orExpression              = 39
	CypherParserRULE_xorExpression             = 40
	CypherParserRULE_andExpression             = 41
	CypherParserRULE_notExpression             = 42
	CypherParserRULE_comparisonExpression      = 43
	CypherParserRULE_compOp                    = 44
	CypherParserRULE_stringPredicateExpression = 45
	CypherParserRULE_stringPredicateSuffix     = 46
	CypherParserRULE_addSubExpression          = 47
	CypherParserRULE_multDivExpression         = 48
	CypherParserRULE_powerExpression           = 49
	CypherParserRULE_unaryExpression           = 50
	CypherParserRULE_postfixExpression         = 51
	CypherParserRULE_subscriptOrSlice          = 52
	CypherParserRULE_atomExpression            = 53
	CypherParserRULE_functionInvocation        = 54
	CypherParserRULE_functionName              = 55
	CypherParserRULE_caseExpression            = 56
	CypherParserRULE_caseWhen                  = 57
	CypherParserRULE_caseElse                  = 58
	CypherParserRULE_listComprehension         = 59
	CypherParserRULE_predicateFunction         = 60
	CypherParserRULE_parameter                 = 61
	CypherParserRULE_literal                   = 62
	CypherParserRULE_integerLiteral            = 63
	CypherParserRULE_floatLiteral              = 64
	CypherParserRULE_stringLiteral             = 65
	CypherParserRULE_listLiteral               = 66
	CypherParserRULE_mapLiteral                = 67
	CypherParserRULE_mapPair                   = 68
	CypherParserRULE_expressionList            = 69
	CypherParserRULE_variable                  = 70
	CypherParserRULE_labelName                 = 71
	CypherParserRULE_relTypeName               = 72
	CypherParserRULE_propertyKeyName           = 73
	CypherParserRULE_symbolicName              = 74
)

// IScriptContext is an interface to support dynamic dispatch.
type IScriptContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllStatement() []IStatementContext
	Statement(i int) IStatementContext
	EOF() antlr.TerminalNode
	AllSEMI() []antlr.TerminalNode
	SEMI(i int) antlr.TerminalNode

	// IsScriptContext differentiates from other interfaces.
	IsScriptContext()
}

type ScriptContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyScriptContext() *ScriptContext {
	var p = new(ScriptContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_script
	return p
}

func InitEmptyScriptContext(p *ScriptContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_script
}

func (*ScriptContext) IsScriptContext() {}

func NewScriptContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ScriptContext {
	var p = new(ScriptContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_script

	return p
}

func (s *ScriptContext) GetParser() antlr.Parser { return s.parser }

func (s *ScriptContext) AllStatement() []IStatementContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IStatementContext); ok {
			len++
		}
	}

	tst := make([]IStatementContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IStatementContext); ok {
			tst[i] = t.(IStatementContext)
			i++
		}
	}

	return tst
}

func (s *ScriptContext) Statement(i int) IStatementContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IStatementContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IStatementContext)
}

func (s *ScriptContext) EOF() antlr.TerminalNode {
	return s.GetToken(CypherParserEOF, 0)
}

func (s *ScriptContext) AllSEMI() []antlr.TerminalNode {
	return s.GetTokens(CypherParserSEMI)
}

func (s *ScriptContext) SEMI(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserSEMI, i)
}

func (s *ScriptContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ScriptContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ScriptContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitScript(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Script() (localctx IScriptContext) {
	localctx = NewScriptContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 0, CypherParserRULE_script)
	var _la int

	var _alt int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(150)
		p.Statement()
	}
	p.SetState(155)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_alt = p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 0, p.GetParserRuleContext())
	if p.HasError() {
		goto errorExit
	}
	for _alt != 2 && _alt != antlr.ATNInvalidAltNumber {
		if _alt == 1 {
			{
				p.SetState(151)
				p.Match(CypherParserSEMI)
				if p.HasError() {
					// Recognition error - abort rule
					goto errorExit
				}
			}
			{
				p.SetState(152)
				p.Statement()
			}

		}
		p.SetState(157)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_alt = p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 0, p.GetParserRuleContext())
		if p.HasError() {
			goto errorExit
		}
	}
	p.SetState(159)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserSEMI {
		{
			p.SetState(158)
			p.Match(CypherParserSEMI)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	}
	{
		p.SetState(161)
		p.Match(CypherParserEOF)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IStatementContext is an interface to support dynamic dispatch.
type IStatementContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	Query() IQueryContext

	// IsStatementContext differentiates from other interfaces.
	IsStatementContext()
}

type StatementContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyStatementContext() *StatementContext {
	var p = new(StatementContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_statement
	return p
}

func InitEmptyStatementContext(p *StatementContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_statement
}

func (*StatementContext) IsStatementContext() {}

func NewStatementContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *StatementContext {
	var p = new(StatementContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_statement

	return p
}

func (s *StatementContext) GetParser() antlr.Parser { return s.parser }

func (s *StatementContext) Query() IQueryContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IQueryContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IQueryContext)
}

func (s *StatementContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *StatementContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *StatementContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitStatement(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Statement() (localctx IStatementContext) {
	localctx = NewStatementContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 2, CypherParserRULE_statement)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(163)
		p.Query()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IQueryContext is an interface to support dynamic dispatch.
type IQueryContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	RegularQuery() IRegularQueryContext

	// IsQueryContext differentiates from other interfaces.
	IsQueryContext()
}

type QueryContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyQueryContext() *QueryContext {
	var p = new(QueryContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_query
	return p
}

func InitEmptyQueryContext(p *QueryContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_query
}

func (*QueryContext) IsQueryContext() {}

func NewQueryContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *QueryContext {
	var p = new(QueryContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_query

	return p
}

func (s *QueryContext) GetParser() antlr.Parser { return s.parser }

func (s *QueryContext) RegularQuery() IRegularQueryContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRegularQueryContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRegularQueryContext)
}

func (s *QueryContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *QueryContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *QueryContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitQuery(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Query() (localctx IQueryContext) {
	localctx = NewQueryContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 4, CypherParserRULE_query)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(165)
		p.RegularQuery()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRegularQueryContext is an interface to support dynamic dispatch.
type IRegularQueryContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllSingleQuery() []ISingleQueryContext
	SingleQuery(i int) ISingleQueryContext
	AllUnionSt() []IUnionStContext
	UnionSt(i int) IUnionStContext

	// IsRegularQueryContext differentiates from other interfaces.
	IsRegularQueryContext()
}

type RegularQueryContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRegularQueryContext() *RegularQueryContext {
	var p = new(RegularQueryContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_regularQuery
	return p
}

func InitEmptyRegularQueryContext(p *RegularQueryContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_regularQuery
}

func (*RegularQueryContext) IsRegularQueryContext() {}

func NewRegularQueryContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RegularQueryContext {
	var p = new(RegularQueryContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_regularQuery

	return p
}

func (s *RegularQueryContext) GetParser() antlr.Parser { return s.parser }

func (s *RegularQueryContext) AllSingleQuery() []ISingleQueryContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(ISingleQueryContext); ok {
			len++
		}
	}

	tst := make([]ISingleQueryContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(ISingleQueryContext); ok {
			tst[i] = t.(ISingleQueryContext)
			i++
		}
	}

	return tst
}

func (s *RegularQueryContext) SingleQuery(i int) ISingleQueryContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISingleQueryContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISingleQueryContext)
}

func (s *RegularQueryContext) AllUnionSt() []IUnionStContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IUnionStContext); ok {
			len++
		}
	}

	tst := make([]IUnionStContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IUnionStContext); ok {
			tst[i] = t.(IUnionStContext)
			i++
		}
	}

	return tst
}

func (s *RegularQueryContext) UnionSt(i int) IUnionStContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IUnionStContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IUnionStContext)
}

func (s *RegularQueryContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RegularQueryContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *RegularQueryContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRegularQuery(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RegularQuery() (localctx IRegularQueryContext) {
	localctx = NewRegularQueryContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 6, CypherParserRULE_regularQuery)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(167)
		p.SingleQuery()
	}
	p.SetState(173)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserUNION {
		{
			p.SetState(168)
			p.UnionSt()
		}
		{
			p.SetState(169)
			p.SingleQuery()
		}

		p.SetState(175)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IUnionStContext is an interface to support dynamic dispatch.
type IUnionStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	UNION() antlr.TerminalNode
	ALL() antlr.TerminalNode

	// IsUnionStContext differentiates from other interfaces.
	IsUnionStContext()
}

type UnionStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyUnionStContext() *UnionStContext {
	var p = new(UnionStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_unionSt
	return p
}

func InitEmptyUnionStContext(p *UnionStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_unionSt
}

func (*UnionStContext) IsUnionStContext() {}

func NewUnionStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *UnionStContext {
	var p = new(UnionStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_unionSt

	return p
}

func (s *UnionStContext) GetParser() antlr.Parser { return s.parser }

func (s *UnionStContext) UNION() antlr.TerminalNode {
	return s.GetToken(CypherParserUNION, 0)
}

func (s *UnionStContext) ALL() antlr.TerminalNode {
	return s.GetToken(CypherParserALL, 0)
}

func (s *UnionStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *UnionStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *UnionStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitUnionSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) UnionSt() (localctx IUnionStContext) {
	localctx = NewUnionStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 8, CypherParserRULE_unionSt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(176)
		p.Match(CypherParserUNION)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(178)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserALL {
		{
			p.SetState(177)
			p.Match(CypherParserALL)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ISingleQueryContext is an interface to support dynamic dispatch.
type ISingleQueryContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllClause() []IClauseContext
	Clause(i int) IClauseContext

	// IsSingleQueryContext differentiates from other interfaces.
	IsSingleQueryContext()
}

type SingleQueryContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptySingleQueryContext() *SingleQueryContext {
	var p = new(SingleQueryContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_singleQuery
	return p
}

func InitEmptySingleQueryContext(p *SingleQueryContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_singleQuery
}

func (*SingleQueryContext) IsSingleQueryContext() {}

func NewSingleQueryContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *SingleQueryContext {
	var p = new(SingleQueryContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_singleQuery

	return p
}

func (s *SingleQueryContext) GetParser() antlr.Parser { return s.parser }

func (s *SingleQueryContext) AllClause() []IClauseContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IClauseContext); ok {
			len++
		}
	}

	tst := make([]IClauseContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IClauseContext); ok {
			tst[i] = t.(IClauseContext)
			i++
		}
	}

	return tst
}

func (s *SingleQueryContext) Clause(i int) IClauseContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IClauseContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IClauseContext)
}

func (s *SingleQueryContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SingleQueryContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *SingleQueryContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSingleQuery(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) SingleQuery() (localctx ISingleQueryContext) {
	localctx = NewSingleQueryContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 10, CypherParserRULE_singleQuery)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	p.SetState(181)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for ok := true; ok; ok = ((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&753670140863709184) != 0) {
		{
			p.SetState(180)
			p.Clause()
		}

		p.SetState(183)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IClauseContext is an interface to support dynamic dispatch.
type IClauseContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	MatchSt() IMatchStContext
	UnwindSt() IUnwindStContext
	WithSt() IWithStContext
	ReturnSt() IReturnStContext
	CreateSt() ICreateStContext
	DeleteSt() IDeleteStContext
	SetSt() ISetStContext
	RemoveSt() IRemoveStContext
	MergeSt() IMergeStContext

	// IsClauseContext differentiates from other interfaces.
	IsClauseContext()
}

type ClauseContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyClauseContext() *ClauseContext {
	var p = new(ClauseContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_clause
	return p
}

func InitEmptyClauseContext(p *ClauseContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_clause
}

func (*ClauseContext) IsClauseContext() {}

func NewClauseContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ClauseContext {
	var p = new(ClauseContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_clause

	return p
}

func (s *ClauseContext) GetParser() antlr.Parser { return s.parser }

func (s *ClauseContext) MatchSt() IMatchStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IMatchStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IMatchStContext)
}

func (s *ClauseContext) UnwindSt() IUnwindStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IUnwindStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IUnwindStContext)
}

func (s *ClauseContext) WithSt() IWithStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IWithStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IWithStContext)
}

func (s *ClauseContext) ReturnSt() IReturnStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IReturnStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IReturnStContext)
}

func (s *ClauseContext) CreateSt() ICreateStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ICreateStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ICreateStContext)
}

func (s *ClauseContext) DeleteSt() IDeleteStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IDeleteStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IDeleteStContext)
}

func (s *ClauseContext) SetSt() ISetStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISetStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISetStContext)
}

func (s *ClauseContext) RemoveSt() IRemoveStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRemoveStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRemoveStContext)
}

func (s *ClauseContext) MergeSt() IMergeStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IMergeStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IMergeStContext)
}

func (s *ClauseContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ClauseContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ClauseContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitClause(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Clause() (localctx IClauseContext) {
	localctx = NewClauseContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 12, CypherParserRULE_clause)
	p.SetState(194)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetTokenStream().LA(1) {
	case CypherParserMATCH, CypherParserOPTIONAL:
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(185)
			p.MatchSt()
		}

	case CypherParserUNWIND:
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(186)
			p.UnwindSt()
		}

	case CypherParserWITH:
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(187)
			p.WithSt()
		}

	case CypherParserRETURN:
		p.EnterOuterAlt(localctx, 4)
		{
			p.SetState(188)
			p.ReturnSt()
		}

	case CypherParserCREATE:
		p.EnterOuterAlt(localctx, 5)
		{
			p.SetState(189)
			p.CreateSt()
		}

	case CypherParserDELETE, CypherParserDETACH:
		p.EnterOuterAlt(localctx, 6)
		{
			p.SetState(190)
			p.DeleteSt()
		}

	case CypherParserSET:
		p.EnterOuterAlt(localctx, 7)
		{
			p.SetState(191)
			p.SetSt()
		}

	case CypherParserREMOVE:
		p.EnterOuterAlt(localctx, 8)
		{
			p.SetState(192)
			p.RemoveSt()
		}

	case CypherParserMERGE:
		p.EnterOuterAlt(localctx, 9)
		{
			p.SetState(193)
			p.MergeSt()
		}

	default:
		p.SetError(antlr.NewNoViableAltException(p, nil, nil, nil, nil, nil))
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IMatchStContext is an interface to support dynamic dispatch.
type IMatchStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	MATCH() antlr.TerminalNode
	PatternList() IPatternListContext
	OPTIONAL() antlr.TerminalNode
	WhereSt() IWhereStContext

	// IsMatchStContext differentiates from other interfaces.
	IsMatchStContext()
}

type MatchStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyMatchStContext() *MatchStContext {
	var p = new(MatchStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_matchSt
	return p
}

func InitEmptyMatchStContext(p *MatchStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_matchSt
}

func (*MatchStContext) IsMatchStContext() {}

func NewMatchStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *MatchStContext {
	var p = new(MatchStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_matchSt

	return p
}

func (s *MatchStContext) GetParser() antlr.Parser { return s.parser }

func (s *MatchStContext) MATCH() antlr.TerminalNode {
	return s.GetToken(CypherParserMATCH, 0)
}

func (s *MatchStContext) PatternList() IPatternListContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPatternListContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPatternListContext)
}

func (s *MatchStContext) OPTIONAL() antlr.TerminalNode {
	return s.GetToken(CypherParserOPTIONAL, 0)
}

func (s *MatchStContext) WhereSt() IWhereStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IWhereStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IWhereStContext)
}

func (s *MatchStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *MatchStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *MatchStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitMatchSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) MatchSt() (localctx IMatchStContext) {
	localctx = NewMatchStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 14, CypherParserRULE_matchSt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	p.SetState(197)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserOPTIONAL {
		{
			p.SetState(196)
			p.Match(CypherParserOPTIONAL)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	}
	{
		p.SetState(199)
		p.Match(CypherParserMATCH)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(200)
		p.PatternList()
	}
	p.SetState(202)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserWHERE {
		{
			p.SetState(201)
			p.WhereSt()
		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IUnwindStContext is an interface to support dynamic dispatch.
type IUnwindStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	UNWIND() antlr.TerminalNode
	Expression() IExpressionContext
	AS() antlr.TerminalNode
	Variable() IVariableContext

	// IsUnwindStContext differentiates from other interfaces.
	IsUnwindStContext()
}

type UnwindStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyUnwindStContext() *UnwindStContext {
	var p = new(UnwindStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_unwindSt
	return p
}

func InitEmptyUnwindStContext(p *UnwindStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_unwindSt
}

func (*UnwindStContext) IsUnwindStContext() {}

func NewUnwindStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *UnwindStContext {
	var p = new(UnwindStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_unwindSt

	return p
}

func (s *UnwindStContext) GetParser() antlr.Parser { return s.parser }

func (s *UnwindStContext) UNWIND() antlr.TerminalNode {
	return s.GetToken(CypherParserUNWIND, 0)
}

func (s *UnwindStContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *UnwindStContext) AS() antlr.TerminalNode {
	return s.GetToken(CypherParserAS, 0)
}

func (s *UnwindStContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *UnwindStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *UnwindStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *UnwindStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitUnwindSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) UnwindSt() (localctx IUnwindStContext) {
	localctx = NewUnwindStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 16, CypherParserRULE_unwindSt)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(204)
		p.Match(CypherParserUNWIND)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(205)
		p.Expression()
	}
	{
		p.SetState(206)
		p.Match(CypherParserAS)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(207)
		p.Variable()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IWithStContext is an interface to support dynamic dispatch.
type IWithStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	WITH() antlr.TerminalNode
	ReturnItems() IReturnItemsContext
	DISTINCT() antlr.TerminalNode
	WhereSt() IWhereStContext
	OrderBySt() IOrderByStContext
	SkipSt() ISkipStContext
	LimitSt() ILimitStContext

	// IsWithStContext differentiates from other interfaces.
	IsWithStContext()
}

type WithStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyWithStContext() *WithStContext {
	var p = new(WithStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_withSt
	return p
}

func InitEmptyWithStContext(p *WithStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_withSt
}

func (*WithStContext) IsWithStContext() {}

func NewWithStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *WithStContext {
	var p = new(WithStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_withSt

	return p
}

func (s *WithStContext) GetParser() antlr.Parser { return s.parser }

func (s *WithStContext) WITH() antlr.TerminalNode {
	return s.GetToken(CypherParserWITH, 0)
}

func (s *WithStContext) ReturnItems() IReturnItemsContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IReturnItemsContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IReturnItemsContext)
}

func (s *WithStContext) DISTINCT() antlr.TerminalNode {
	return s.GetToken(CypherParserDISTINCT, 0)
}

func (s *WithStContext) WhereSt() IWhereStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IWhereStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IWhereStContext)
}

func (s *WithStContext) OrderBySt() IOrderByStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IOrderByStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IOrderByStContext)
}

func (s *WithStContext) SkipSt() ISkipStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISkipStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISkipStContext)
}

func (s *WithStContext) LimitSt() ILimitStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ILimitStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ILimitStContext)
}

func (s *WithStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *WithStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *WithStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitWithSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) WithSt() (localctx IWithStContext) {
	localctx = NewWithStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 18, CypherParserRULE_withSt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(209)
		p.Match(CypherParserWITH)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(211)
	p.GetErrorHandler().Sync(p)

	if p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 8, p.GetParserRuleContext()) == 1 {
		{
			p.SetState(210)
			p.Match(CypherParserDISTINCT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	} else if p.HasError() { // JIM
		goto errorExit
	}
	{
		p.SetState(213)
		p.ReturnItems()
	}
	p.SetState(215)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserWHERE {
		{
			p.SetState(214)
			p.WhereSt()
		}

	}
	p.SetState(218)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserORDER {
		{
			p.SetState(217)
			p.OrderBySt()
		}

	}
	p.SetState(221)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserSKIP_W {
		{
			p.SetState(220)
			p.SkipSt()
		}

	}
	p.SetState(224)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserLIMIT {
		{
			p.SetState(223)
			p.LimitSt()
		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IReturnStContext is an interface to support dynamic dispatch.
type IReturnStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	RETURN() antlr.TerminalNode
	ReturnItems() IReturnItemsContext
	DISTINCT() antlr.TerminalNode
	OrderBySt() IOrderByStContext
	SkipSt() ISkipStContext
	LimitSt() ILimitStContext

	// IsReturnStContext differentiates from other interfaces.
	IsReturnStContext()
}

type ReturnStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyReturnStContext() *ReturnStContext {
	var p = new(ReturnStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_returnSt
	return p
}

func InitEmptyReturnStContext(p *ReturnStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_returnSt
}

func (*ReturnStContext) IsReturnStContext() {}

func NewReturnStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ReturnStContext {
	var p = new(ReturnStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_returnSt

	return p
}

func (s *ReturnStContext) GetParser() antlr.Parser { return s.parser }

func (s *ReturnStContext) RETURN() antlr.TerminalNode {
	return s.GetToken(CypherParserRETURN, 0)
}

func (s *ReturnStContext) ReturnItems() IReturnItemsContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IReturnItemsContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IReturnItemsContext)
}

func (s *ReturnStContext) DISTINCT() antlr.TerminalNode {
	return s.GetToken(CypherParserDISTINCT, 0)
}

func (s *ReturnStContext) OrderBySt() IOrderByStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IOrderByStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IOrderByStContext)
}

func (s *ReturnStContext) SkipSt() ISkipStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISkipStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISkipStContext)
}

func (s *ReturnStContext) LimitSt() ILimitStContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ILimitStContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ILimitStContext)
}

func (s *ReturnStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ReturnStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ReturnStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitReturnSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) ReturnSt() (localctx IReturnStContext) {
	localctx = NewReturnStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 20, CypherParserRULE_returnSt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(226)
		p.Match(CypherParserRETURN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(228)
	p.GetErrorHandler().Sync(p)

	if p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 13, p.GetParserRuleContext()) == 1 {
		{
			p.SetState(227)
			p.Match(CypherParserDISTINCT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	} else if p.HasError() { // JIM
		goto errorExit
	}
	{
		p.SetState(230)
		p.ReturnItems()
	}
	p.SetState(232)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserORDER {
		{
			p.SetState(231)
			p.OrderBySt()
		}

	}
	p.SetState(235)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserSKIP_W {
		{
			p.SetState(234)
			p.SkipSt()
		}

	}
	p.SetState(238)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserLIMIT {
		{
			p.SetState(237)
			p.LimitSt()
		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IWhereStContext is an interface to support dynamic dispatch.
type IWhereStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	WHERE() antlr.TerminalNode
	Expression() IExpressionContext

	// IsWhereStContext differentiates from other interfaces.
	IsWhereStContext()
}

type WhereStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyWhereStContext() *WhereStContext {
	var p = new(WhereStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_whereSt
	return p
}

func InitEmptyWhereStContext(p *WhereStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_whereSt
}

func (*WhereStContext) IsWhereStContext() {}

func NewWhereStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *WhereStContext {
	var p = new(WhereStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_whereSt

	return p
}

func (s *WhereStContext) GetParser() antlr.Parser { return s.parser }

func (s *WhereStContext) WHERE() antlr.TerminalNode {
	return s.GetToken(CypherParserWHERE, 0)
}

func (s *WhereStContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *WhereStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *WhereStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *WhereStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitWhereSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) WhereSt() (localctx IWhereStContext) {
	localctx = NewWhereStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 22, CypherParserRULE_whereSt)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(240)
		p.Match(CypherParserWHERE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(241)
		p.Expression()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IOrderByStContext is an interface to support dynamic dispatch.
type IOrderByStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	ORDER() antlr.TerminalNode
	BY() antlr.TerminalNode
	AllOrderItem() []IOrderItemContext
	OrderItem(i int) IOrderItemContext
	AllCOMMA() []antlr.TerminalNode
	COMMA(i int) antlr.TerminalNode

	// IsOrderByStContext differentiates from other interfaces.
	IsOrderByStContext()
}

type OrderByStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyOrderByStContext() *OrderByStContext {
	var p = new(OrderByStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_orderBySt
	return p
}

func InitEmptyOrderByStContext(p *OrderByStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_orderBySt
}

func (*OrderByStContext) IsOrderByStContext() {}

func NewOrderByStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *OrderByStContext {
	var p = new(OrderByStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_orderBySt

	return p
}

func (s *OrderByStContext) GetParser() antlr.Parser { return s.parser }

func (s *OrderByStContext) ORDER() antlr.TerminalNode {
	return s.GetToken(CypherParserORDER, 0)
}

func (s *OrderByStContext) BY() antlr.TerminalNode {
	return s.GetToken(CypherParserBY, 0)
}

func (s *OrderByStContext) AllOrderItem() []IOrderItemContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IOrderItemContext); ok {
			len++
		}
	}

	tst := make([]IOrderItemContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IOrderItemContext); ok {
			tst[i] = t.(IOrderItemContext)
			i++
		}
	}

	return tst
}

func (s *OrderByStContext) OrderItem(i int) IOrderItemContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IOrderItemContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IOrderItemContext)
}

func (s *OrderByStContext) AllCOMMA() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOMMA)
}

func (s *OrderByStContext) COMMA(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOMMA, i)
}

func (s *OrderByStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *OrderByStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *OrderByStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitOrderBySt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) OrderBySt() (localctx IOrderByStContext) {
	localctx = NewOrderByStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 24, CypherParserRULE_orderBySt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(243)
		p.Match(CypherParserORDER)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(244)
		p.Match(CypherParserBY)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(245)
		p.OrderItem()
	}
	p.SetState(250)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserCOMMA {
		{
			p.SetState(246)
			p.Match(CypherParserCOMMA)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(247)
			p.OrderItem()
		}

		p.SetState(252)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IOrderItemContext is an interface to support dynamic dispatch.
type IOrderItemContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	Expression() IExpressionContext
	ASCENDING() antlr.TerminalNode
	ASC() antlr.TerminalNode
	DESCENDING() antlr.TerminalNode
	DESC() antlr.TerminalNode

	// IsOrderItemContext differentiates from other interfaces.
	IsOrderItemContext()
}

type OrderItemContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyOrderItemContext() *OrderItemContext {
	var p = new(OrderItemContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_orderItem
	return p
}

func InitEmptyOrderItemContext(p *OrderItemContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_orderItem
}

func (*OrderItemContext) IsOrderItemContext() {}

func NewOrderItemContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *OrderItemContext {
	var p = new(OrderItemContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_orderItem

	return p
}

func (s *OrderItemContext) GetParser() antlr.Parser { return s.parser }

func (s *OrderItemContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *OrderItemContext) ASCENDING() antlr.TerminalNode {
	return s.GetToken(CypherParserASCENDING, 0)
}

func (s *OrderItemContext) ASC() antlr.TerminalNode {
	return s.GetToken(CypherParserASC, 0)
}

func (s *OrderItemContext) DESCENDING() antlr.TerminalNode {
	return s.GetToken(CypherParserDESCENDING, 0)
}

func (s *OrderItemContext) DESC() antlr.TerminalNode {
	return s.GetToken(CypherParserDESC, 0)
}

func (s *OrderItemContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *OrderItemContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *OrderItemContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitOrderItem(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) OrderItem() (localctx IOrderItemContext) {
	localctx = NewOrderItemContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 26, CypherParserRULE_orderItem)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(253)
		p.Expression()
	}
	p.SetState(255)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if (int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&13606456393728) != 0 {
		{
			p.SetState(254)
			_la = p.GetTokenStream().LA(1)

			if !((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&13606456393728) != 0) {
				p.GetErrorHandler().RecoverInline(p)
			} else {
				p.GetErrorHandler().ReportMatch(p)
				p.Consume()
			}
		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ISkipStContext is an interface to support dynamic dispatch.
type ISkipStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SKIP_W() antlr.TerminalNode
	Expression() IExpressionContext

	// IsSkipStContext differentiates from other interfaces.
	IsSkipStContext()
}

type SkipStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptySkipStContext() *SkipStContext {
	var p = new(SkipStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_skipSt
	return p
}

func InitEmptySkipStContext(p *SkipStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_skipSt
}

func (*SkipStContext) IsSkipStContext() {}

func NewSkipStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *SkipStContext {
	var p = new(SkipStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_skipSt

	return p
}

func (s *SkipStContext) GetParser() antlr.Parser { return s.parser }

func (s *SkipStContext) SKIP_W() antlr.TerminalNode {
	return s.GetToken(CypherParserSKIP_W, 0)
}

func (s *SkipStContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *SkipStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SkipStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *SkipStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSkipSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) SkipSt() (localctx ISkipStContext) {
	localctx = NewSkipStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 28, CypherParserRULE_skipSt)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(257)
		p.Match(CypherParserSKIP_W)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(258)
		p.Expression()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ILimitStContext is an interface to support dynamic dispatch.
type ILimitStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LIMIT() antlr.TerminalNode
	Expression() IExpressionContext

	// IsLimitStContext differentiates from other interfaces.
	IsLimitStContext()
}

type LimitStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyLimitStContext() *LimitStContext {
	var p = new(LimitStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_limitSt
	return p
}

func InitEmptyLimitStContext(p *LimitStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_limitSt
}

func (*LimitStContext) IsLimitStContext() {}

func NewLimitStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *LimitStContext {
	var p = new(LimitStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_limitSt

	return p
}

func (s *LimitStContext) GetParser() antlr.Parser { return s.parser }

func (s *LimitStContext) LIMIT() antlr.TerminalNode {
	return s.GetToken(CypherParserLIMIT, 0)
}

func (s *LimitStContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *LimitStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *LimitStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *LimitStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitLimitSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) LimitSt() (localctx ILimitStContext) {
	localctx = NewLimitStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 30, CypherParserRULE_limitSt)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(260)
		p.Match(CypherParserLIMIT)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(261)
		p.Expression()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IReturnItemsContext is an interface to support dynamic dispatch.
type IReturnItemsContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	MULT() antlr.TerminalNode
	AllReturnItem() []IReturnItemContext
	ReturnItem(i int) IReturnItemContext
	AllCOMMA() []antlr.TerminalNode
	COMMA(i int) antlr.TerminalNode

	// IsReturnItemsContext differentiates from other interfaces.
	IsReturnItemsContext()
}

type ReturnItemsContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyReturnItemsContext() *ReturnItemsContext {
	var p = new(ReturnItemsContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_returnItems
	return p
}

func InitEmptyReturnItemsContext(p *ReturnItemsContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_returnItems
}

func (*ReturnItemsContext) IsReturnItemsContext() {}

func NewReturnItemsContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ReturnItemsContext {
	var p = new(ReturnItemsContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_returnItems

	return p
}

func (s *ReturnItemsContext) GetParser() antlr.Parser { return s.parser }

func (s *ReturnItemsContext) MULT() antlr.TerminalNode {
	return s.GetToken(CypherParserMULT, 0)
}

func (s *ReturnItemsContext) AllReturnItem() []IReturnItemContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IReturnItemContext); ok {
			len++
		}
	}

	tst := make([]IReturnItemContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IReturnItemContext); ok {
			tst[i] = t.(IReturnItemContext)
			i++
		}
	}

	return tst
}

func (s *ReturnItemsContext) ReturnItem(i int) IReturnItemContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IReturnItemContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IReturnItemContext)
}

func (s *ReturnItemsContext) AllCOMMA() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOMMA)
}

func (s *ReturnItemsContext) COMMA(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOMMA, i)
}

func (s *ReturnItemsContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ReturnItemsContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ReturnItemsContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitReturnItems(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) ReturnItems() (localctx IReturnItemsContext) {
	localctx = NewReturnItemsContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 32, CypherParserRULE_returnItems)
	var _la int

	p.SetState(272)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetTokenStream().LA(1) {
	case CypherParserMULT:
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(263)
			p.Match(CypherParserMULT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case CypherParserLPAREN, CypherParserLBRACE, CypherParserLBRACK, CypherParserSUB, CypherParserPLUS, CypherParserDOLLAR, CypherParserCALL, CypherParserYIELD, CypherParserFILTER, CypherParserEXTRACT, CypherParserCOUNT, CypherParserANY, CypherParserNONE, CypherParserSINGLE, CypherParserALL, CypherParserASC, CypherParserASCENDING, CypherParserBY, CypherParserCREATE, CypherParserDELETE, CypherParserDESC, CypherParserDESCENDING, CypherParserDETACH, CypherParserEXISTS, CypherParserLIMIT, CypherParserMATCH, CypherParserMERGE, CypherParserON, CypherParserOPTIONAL, CypherParserORDER, CypherParserREMOVE, CypherParserRETURN, CypherParserSET, CypherParserSKIP_W, CypherParserWHERE, CypherParserWITH, CypherParserUNION, CypherParserUNWIND, CypherParserAND, CypherParserAS, CypherParserCONTAINS, CypherParserDISTINCT, CypherParserENDS, CypherParserIN, CypherParserIS, CypherParserNOT, CypherParserOR, CypherParserSTARTS, CypherParserXOR, CypherParserFALSE, CypherParserTRUE, CypherParserNULL_W, CypherParserCONSTRAINT, CypherParserDO, CypherParserFOR, CypherParserREQUIRE, CypherParserUNIQUE, CypherParserCASE, CypherParserWHEN, CypherParserTHEN, CypherParserELSE, CypherParserEND, CypherParserMANDATORY, CypherParserSCALAR, CypherParserOF, CypherParserADD, CypherParserDROP, CypherParserID, CypherParserESC_LITERAL, CypherParserSTRING_LITERAL, CypherParserHEX_INTEGER, CypherParserOCTAL_INTEGER, CypherParserFLOAT_LITERAL, CypherParserINTEGER_LITERAL:
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(264)
			p.ReturnItem()
		}
		p.SetState(269)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		for _la == CypherParserCOMMA {
			{
				p.SetState(265)
				p.Match(CypherParserCOMMA)
				if p.HasError() {
					// Recognition error - abort rule
					goto errorExit
				}
			}
			{
				p.SetState(266)
				p.ReturnItem()
			}

			p.SetState(271)
			p.GetErrorHandler().Sync(p)
			if p.HasError() {
				goto errorExit
			}
			_la = p.GetTokenStream().LA(1)
		}

	default:
		p.SetError(antlr.NewNoViableAltException(p, nil, nil, nil, nil, nil))
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IReturnItemContext is an interface to support dynamic dispatch.
type IReturnItemContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	Expression() IExpressionContext
	AS() antlr.TerminalNode
	Variable() IVariableContext

	// IsReturnItemContext differentiates from other interfaces.
	IsReturnItemContext()
}

type ReturnItemContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyReturnItemContext() *ReturnItemContext {
	var p = new(ReturnItemContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_returnItem
	return p
}

func InitEmptyReturnItemContext(p *ReturnItemContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_returnItem
}

func (*ReturnItemContext) IsReturnItemContext() {}

func NewReturnItemContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ReturnItemContext {
	var p = new(ReturnItemContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_returnItem

	return p
}

func (s *ReturnItemContext) GetParser() antlr.Parser { return s.parser }

func (s *ReturnItemContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *ReturnItemContext) AS() antlr.TerminalNode {
	return s.GetToken(CypherParserAS, 0)
}

func (s *ReturnItemContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *ReturnItemContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ReturnItemContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ReturnItemContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitReturnItem(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) ReturnItem() (localctx IReturnItemContext) {
	localctx = NewReturnItemContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 34, CypherParserRULE_returnItem)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(274)
		p.Expression()
	}
	p.SetState(277)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserAS {
		{
			p.SetState(275)
			p.Match(CypherParserAS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(276)
			p.Variable()
		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ICreateStContext is an interface to support dynamic dispatch.
type ICreateStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	CREATE() antlr.TerminalNode
	PatternList() IPatternListContext

	// IsCreateStContext differentiates from other interfaces.
	IsCreateStContext()
}

type CreateStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyCreateStContext() *CreateStContext {
	var p = new(CreateStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_createSt
	return p
}

func InitEmptyCreateStContext(p *CreateStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_createSt
}

func (*CreateStContext) IsCreateStContext() {}

func NewCreateStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *CreateStContext {
	var p = new(CreateStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_createSt

	return p
}

func (s *CreateStContext) GetParser() antlr.Parser { return s.parser }

func (s *CreateStContext) CREATE() antlr.TerminalNode {
	return s.GetToken(CypherParserCREATE, 0)
}

func (s *CreateStContext) PatternList() IPatternListContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPatternListContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPatternListContext)
}

func (s *CreateStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *CreateStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *CreateStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitCreateSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) CreateSt() (localctx ICreateStContext) {
	localctx = NewCreateStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 36, CypherParserRULE_createSt)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(279)
		p.Match(CypherParserCREATE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(280)
		p.PatternList()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IDeleteStContext is an interface to support dynamic dispatch.
type IDeleteStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	DELETE() antlr.TerminalNode
	ExpressionList() IExpressionListContext
	DETACH() antlr.TerminalNode

	// IsDeleteStContext differentiates from other interfaces.
	IsDeleteStContext()
}

type DeleteStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyDeleteStContext() *DeleteStContext {
	var p = new(DeleteStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_deleteSt
	return p
}

func InitEmptyDeleteStContext(p *DeleteStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_deleteSt
}

func (*DeleteStContext) IsDeleteStContext() {}

func NewDeleteStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *DeleteStContext {
	var p = new(DeleteStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_deleteSt

	return p
}

func (s *DeleteStContext) GetParser() antlr.Parser { return s.parser }

func (s *DeleteStContext) DELETE() antlr.TerminalNode {
	return s.GetToken(CypherParserDELETE, 0)
}

func (s *DeleteStContext) ExpressionList() IExpressionListContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionListContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionListContext)
}

func (s *DeleteStContext) DETACH() antlr.TerminalNode {
	return s.GetToken(CypherParserDETACH, 0)
}

func (s *DeleteStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *DeleteStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *DeleteStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitDeleteSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) DeleteSt() (localctx IDeleteStContext) {
	localctx = NewDeleteStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 38, CypherParserRULE_deleteSt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	p.SetState(283)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserDETACH {
		{
			p.SetState(282)
			p.Match(CypherParserDETACH)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	}
	{
		p.SetState(285)
		p.Match(CypherParserDELETE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(286)
		p.ExpressionList()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ISetStContext is an interface to support dynamic dispatch.
type ISetStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SET() antlr.TerminalNode
	AllSetItem() []ISetItemContext
	SetItem(i int) ISetItemContext
	AllCOMMA() []antlr.TerminalNode
	COMMA(i int) antlr.TerminalNode

	// IsSetStContext differentiates from other interfaces.
	IsSetStContext()
}

type SetStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptySetStContext() *SetStContext {
	var p = new(SetStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_setSt
	return p
}

func InitEmptySetStContext(p *SetStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_setSt
}

func (*SetStContext) IsSetStContext() {}

func NewSetStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *SetStContext {
	var p = new(SetStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_setSt

	return p
}

func (s *SetStContext) GetParser() antlr.Parser { return s.parser }

func (s *SetStContext) SET() antlr.TerminalNode {
	return s.GetToken(CypherParserSET, 0)
}

func (s *SetStContext) AllSetItem() []ISetItemContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(ISetItemContext); ok {
			len++
		}
	}

	tst := make([]ISetItemContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(ISetItemContext); ok {
			tst[i] = t.(ISetItemContext)
			i++
		}
	}

	return tst
}

func (s *SetStContext) SetItem(i int) ISetItemContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISetItemContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISetItemContext)
}

func (s *SetStContext) AllCOMMA() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOMMA)
}

func (s *SetStContext) COMMA(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOMMA, i)
}

func (s *SetStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SetStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *SetStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSetSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) SetSt() (localctx ISetStContext) {
	localctx = NewSetStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 40, CypherParserRULE_setSt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(288)
		p.Match(CypherParserSET)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(289)
		p.SetItem()
	}
	p.SetState(294)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserCOMMA {
		{
			p.SetState(290)
			p.Match(CypherParserCOMMA)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(291)
			p.SetItem()
		}

		p.SetState(296)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ISetItemContext is an interface to support dynamic dispatch.
type ISetItemContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser
	// IsSetItemContext differentiates from other interfaces.
	IsSetItemContext()
}

type SetItemContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptySetItemContext() *SetItemContext {
	var p = new(SetItemContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_setItem
	return p
}

func InitEmptySetItemContext(p *SetItemContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_setItem
}

func (*SetItemContext) IsSetItemContext() {}

func NewSetItemContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *SetItemContext {
	var p = new(SetItemContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_setItem

	return p
}

func (s *SetItemContext) GetParser() antlr.Parser { return s.parser }

func (s *SetItemContext) CopyAll(ctx *SetItemContext) {
	s.CopyFrom(&ctx.BaseParserRuleContext)
}

func (s *SetItemContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SetItemContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

type SetMergePropertiesContext struct {
	SetItemContext
}

func NewSetMergePropertiesContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *SetMergePropertiesContext {
	var p = new(SetMergePropertiesContext)

	InitEmptySetItemContext(&p.SetItemContext)
	p.parser = parser
	p.CopyAll(ctx.(*SetItemContext))

	return p
}

func (s *SetMergePropertiesContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SetMergePropertiesContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *SetMergePropertiesContext) ADD_ASSIGN() antlr.TerminalNode {
	return s.GetToken(CypherParserADD_ASSIGN, 0)
}

func (s *SetMergePropertiesContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *SetMergePropertiesContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSetMergeProperties(s)

	default:
		return t.VisitChildren(s)
	}
}

type SetPropertyContext struct {
	SetItemContext
}

func NewSetPropertyContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *SetPropertyContext {
	var p = new(SetPropertyContext)

	InitEmptySetItemContext(&p.SetItemContext)
	p.parser = parser
	p.CopyAll(ctx.(*SetItemContext))

	return p
}

func (s *SetPropertyContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SetPropertyContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *SetPropertyContext) DOT() antlr.TerminalNode {
	return s.GetToken(CypherParserDOT, 0)
}

func (s *SetPropertyContext) PropertyKeyName() IPropertyKeyNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPropertyKeyNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPropertyKeyNameContext)
}

func (s *SetPropertyContext) ASSIGN() antlr.TerminalNode {
	return s.GetToken(CypherParserASSIGN, 0)
}

func (s *SetPropertyContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *SetPropertyContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSetProperty(s)

	default:
		return t.VisitChildren(s)
	}
}

type SetAllPropertiesContext struct {
	SetItemContext
}

func NewSetAllPropertiesContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *SetAllPropertiesContext {
	var p = new(SetAllPropertiesContext)

	InitEmptySetItemContext(&p.SetItemContext)
	p.parser = parser
	p.CopyAll(ctx.(*SetItemContext))

	return p
}

func (s *SetAllPropertiesContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SetAllPropertiesContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *SetAllPropertiesContext) ASSIGN() antlr.TerminalNode {
	return s.GetToken(CypherParserASSIGN, 0)
}

func (s *SetAllPropertiesContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *SetAllPropertiesContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSetAllProperties(s)

	default:
		return t.VisitChildren(s)
	}
}

type SetLabelsContext struct {
	SetItemContext
}

func NewSetLabelsContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *SetLabelsContext {
	var p = new(SetLabelsContext)

	InitEmptySetItemContext(&p.SetItemContext)
	p.parser = parser
	p.CopyAll(ctx.(*SetItemContext))

	return p
}

func (s *SetLabelsContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SetLabelsContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *SetLabelsContext) NodeLabels() INodeLabelsContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(INodeLabelsContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(INodeLabelsContext)
}

func (s *SetLabelsContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSetLabels(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) SetItem() (localctx ISetItemContext) {
	localctx = NewSetItemContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 42, CypherParserRULE_setItem)
	p.SetState(314)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 24, p.GetParserRuleContext()) {
	case 1:
		localctx = NewSetPropertyContext(p, localctx)
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(297)
			p.Variable()
		}
		{
			p.SetState(298)
			p.Match(CypherParserDOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(299)
			p.PropertyKeyName()
		}
		{
			p.SetState(300)
			p.Match(CypherParserASSIGN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(301)
			p.Expression()
		}

	case 2:
		localctx = NewSetMergePropertiesContext(p, localctx)
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(303)
			p.Variable()
		}
		{
			p.SetState(304)
			p.Match(CypherParserADD_ASSIGN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(305)
			p.Expression()
		}

	case 3:
		localctx = NewSetAllPropertiesContext(p, localctx)
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(307)
			p.Variable()
		}
		{
			p.SetState(308)
			p.Match(CypherParserASSIGN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(309)
			p.Expression()
		}

	case 4:
		localctx = NewSetLabelsContext(p, localctx)
		p.EnterOuterAlt(localctx, 4)
		{
			p.SetState(311)
			p.Variable()
		}
		{
			p.SetState(312)
			p.NodeLabels()
		}

	case antlr.ATNInvalidAltNumber:
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRemoveStContext is an interface to support dynamic dispatch.
type IRemoveStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	REMOVE() antlr.TerminalNode
	AllRemoveItem() []IRemoveItemContext
	RemoveItem(i int) IRemoveItemContext
	AllCOMMA() []antlr.TerminalNode
	COMMA(i int) antlr.TerminalNode

	// IsRemoveStContext differentiates from other interfaces.
	IsRemoveStContext()
}

type RemoveStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRemoveStContext() *RemoveStContext {
	var p = new(RemoveStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_removeSt
	return p
}

func InitEmptyRemoveStContext(p *RemoveStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_removeSt
}

func (*RemoveStContext) IsRemoveStContext() {}

func NewRemoveStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RemoveStContext {
	var p = new(RemoveStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_removeSt

	return p
}

func (s *RemoveStContext) GetParser() antlr.Parser { return s.parser }

func (s *RemoveStContext) REMOVE() antlr.TerminalNode {
	return s.GetToken(CypherParserREMOVE, 0)
}

func (s *RemoveStContext) AllRemoveItem() []IRemoveItemContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IRemoveItemContext); ok {
			len++
		}
	}

	tst := make([]IRemoveItemContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IRemoveItemContext); ok {
			tst[i] = t.(IRemoveItemContext)
			i++
		}
	}

	return tst
}

func (s *RemoveStContext) RemoveItem(i int) IRemoveItemContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRemoveItemContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRemoveItemContext)
}

func (s *RemoveStContext) AllCOMMA() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOMMA)
}

func (s *RemoveStContext) COMMA(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOMMA, i)
}

func (s *RemoveStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RemoveStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *RemoveStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRemoveSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RemoveSt() (localctx IRemoveStContext) {
	localctx = NewRemoveStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 44, CypherParserRULE_removeSt)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(316)
		p.Match(CypherParserREMOVE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(317)
		p.RemoveItem()
	}
	p.SetState(322)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserCOMMA {
		{
			p.SetState(318)
			p.Match(CypherParserCOMMA)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(319)
			p.RemoveItem()
		}

		p.SetState(324)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRemoveItemContext is an interface to support dynamic dispatch.
type IRemoveItemContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser
	// IsRemoveItemContext differentiates from other interfaces.
	IsRemoveItemContext()
}

type RemoveItemContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRemoveItemContext() *RemoveItemContext {
	var p = new(RemoveItemContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_removeItem
	return p
}

func InitEmptyRemoveItemContext(p *RemoveItemContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_removeItem
}

func (*RemoveItemContext) IsRemoveItemContext() {}

func NewRemoveItemContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RemoveItemContext {
	var p = new(RemoveItemContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_removeItem

	return p
}

func (s *RemoveItemContext) GetParser() antlr.Parser { return s.parser }

func (s *RemoveItemContext) CopyAll(ctx *RemoveItemContext) {
	s.CopyFrom(&ctx.BaseParserRuleContext)
}

func (s *RemoveItemContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RemoveItemContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

type RemovePropertyContext struct {
	RemoveItemContext
}

func NewRemovePropertyContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *RemovePropertyContext {
	var p = new(RemovePropertyContext)

	InitEmptyRemoveItemContext(&p.RemoveItemContext)
	p.parser = parser
	p.CopyAll(ctx.(*RemoveItemContext))

	return p
}

func (s *RemovePropertyContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RemovePropertyContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *RemovePropertyContext) DOT() antlr.TerminalNode {
	return s.GetToken(CypherParserDOT, 0)
}

func (s *RemovePropertyContext) PropertyKeyName() IPropertyKeyNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPropertyKeyNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPropertyKeyNameContext)
}

func (s *RemovePropertyContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRemoveProperty(s)

	default:
		return t.VisitChildren(s)
	}
}

type RemoveLabelsContext struct {
	RemoveItemContext
}

func NewRemoveLabelsContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *RemoveLabelsContext {
	var p = new(RemoveLabelsContext)

	InitEmptyRemoveItemContext(&p.RemoveItemContext)
	p.parser = parser
	p.CopyAll(ctx.(*RemoveItemContext))

	return p
}

func (s *RemoveLabelsContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RemoveLabelsContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *RemoveLabelsContext) NodeLabels() INodeLabelsContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(INodeLabelsContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(INodeLabelsContext)
}

func (s *RemoveLabelsContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRemoveLabels(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RemoveItem() (localctx IRemoveItemContext) {
	localctx = NewRemoveItemContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 46, CypherParserRULE_removeItem)
	p.SetState(332)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 26, p.GetParserRuleContext()) {
	case 1:
		localctx = NewRemovePropertyContext(p, localctx)
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(325)
			p.Variable()
		}
		{
			p.SetState(326)
			p.Match(CypherParserDOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(327)
			p.PropertyKeyName()
		}

	case 2:
		localctx = NewRemoveLabelsContext(p, localctx)
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(329)
			p.Variable()
		}
		{
			p.SetState(330)
			p.NodeLabels()
		}

	case antlr.ATNInvalidAltNumber:
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IMergeStContext is an interface to support dynamic dispatch.
type IMergeStContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	MERGE() antlr.TerminalNode
	PatternPart() IPatternPartContext

	// IsMergeStContext differentiates from other interfaces.
	IsMergeStContext()
}

type MergeStContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyMergeStContext() *MergeStContext {
	var p = new(MergeStContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_mergeSt
	return p
}

func InitEmptyMergeStContext(p *MergeStContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_mergeSt
}

func (*MergeStContext) IsMergeStContext() {}

func NewMergeStContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *MergeStContext {
	var p = new(MergeStContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_mergeSt

	return p
}

func (s *MergeStContext) GetParser() antlr.Parser { return s.parser }

func (s *MergeStContext) MERGE() antlr.TerminalNode {
	return s.GetToken(CypherParserMERGE, 0)
}

func (s *MergeStContext) PatternPart() IPatternPartContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPatternPartContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPatternPartContext)
}

func (s *MergeStContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *MergeStContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *MergeStContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitMergeSt(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) MergeSt() (localctx IMergeStContext) {
	localctx = NewMergeStContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 48, CypherParserRULE_mergeSt)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(334)
		p.Match(CypherParserMERGE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(335)
		p.PatternPart()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPatternListContext is an interface to support dynamic dispatch.
type IPatternListContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllPatternPart() []IPatternPartContext
	PatternPart(i int) IPatternPartContext
	AllCOMMA() []antlr.TerminalNode
	COMMA(i int) antlr.TerminalNode

	// IsPatternListContext differentiates from other interfaces.
	IsPatternListContext()
}

type PatternListContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPatternListContext() *PatternListContext {
	var p = new(PatternListContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_patternList
	return p
}

func InitEmptyPatternListContext(p *PatternListContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_patternList
}

func (*PatternListContext) IsPatternListContext() {}

func NewPatternListContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PatternListContext {
	var p = new(PatternListContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_patternList

	return p
}

func (s *PatternListContext) GetParser() antlr.Parser { return s.parser }

func (s *PatternListContext) AllPatternPart() []IPatternPartContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IPatternPartContext); ok {
			len++
		}
	}

	tst := make([]IPatternPartContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IPatternPartContext); ok {
			tst[i] = t.(IPatternPartContext)
			i++
		}
	}

	return tst
}

func (s *PatternListContext) PatternPart(i int) IPatternPartContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPatternPartContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPatternPartContext)
}

func (s *PatternListContext) AllCOMMA() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOMMA)
}

func (s *PatternListContext) COMMA(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOMMA, i)
}

func (s *PatternListContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PatternListContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PatternListContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPatternList(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) PatternList() (localctx IPatternListContext) {
	localctx = NewPatternListContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 50, CypherParserRULE_patternList)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(337)
		p.PatternPart()
	}
	p.SetState(342)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserCOMMA {
		{
			p.SetState(338)
			p.Match(CypherParserCOMMA)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(339)
			p.PatternPart()
		}

		p.SetState(344)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPatternPartContext is an interface to support dynamic dispatch.
type IPatternPartContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	PatternElement() IPatternElementContext
	Variable() IVariableContext
	ASSIGN() antlr.TerminalNode

	// IsPatternPartContext differentiates from other interfaces.
	IsPatternPartContext()
}

type PatternPartContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPatternPartContext() *PatternPartContext {
	var p = new(PatternPartContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_patternPart
	return p
}

func InitEmptyPatternPartContext(p *PatternPartContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_patternPart
}

func (*PatternPartContext) IsPatternPartContext() {}

func NewPatternPartContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PatternPartContext {
	var p = new(PatternPartContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_patternPart

	return p
}

func (s *PatternPartContext) GetParser() antlr.Parser { return s.parser }

func (s *PatternPartContext) PatternElement() IPatternElementContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPatternElementContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPatternElementContext)
}

func (s *PatternPartContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *PatternPartContext) ASSIGN() antlr.TerminalNode {
	return s.GetToken(CypherParserASSIGN, 0)
}

func (s *PatternPartContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PatternPartContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PatternPartContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPatternPart(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) PatternPart() (localctx IPatternPartContext) {
	localctx = NewPatternPartContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 52, CypherParserRULE_patternPart)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	p.SetState(348)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if (int64((_la-28)) & ^0x3f) == 0 && ((int64(1)<<(_la-28))&9223372036854775807) != 0 {
		{
			p.SetState(345)
			p.Variable()
		}
		{
			p.SetState(346)
			p.Match(CypherParserASSIGN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	}
	{
		p.SetState(350)
		p.PatternElement()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPatternElementContext is an interface to support dynamic dispatch.
type IPatternElementContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllNodePattern() []INodePatternContext
	NodePattern(i int) INodePatternContext
	AllRelationshipPattern() []IRelationshipPatternContext
	RelationshipPattern(i int) IRelationshipPatternContext

	// IsPatternElementContext differentiates from other interfaces.
	IsPatternElementContext()
}

type PatternElementContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPatternElementContext() *PatternElementContext {
	var p = new(PatternElementContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_patternElement
	return p
}

func InitEmptyPatternElementContext(p *PatternElementContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_patternElement
}

func (*PatternElementContext) IsPatternElementContext() {}

func NewPatternElementContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PatternElementContext {
	var p = new(PatternElementContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_patternElement

	return p
}

func (s *PatternElementContext) GetParser() antlr.Parser { return s.parser }

func (s *PatternElementContext) AllNodePattern() []INodePatternContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(INodePatternContext); ok {
			len++
		}
	}

	tst := make([]INodePatternContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(INodePatternContext); ok {
			tst[i] = t.(INodePatternContext)
			i++
		}
	}

	return tst
}

func (s *PatternElementContext) NodePattern(i int) INodePatternContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(INodePatternContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(INodePatternContext)
}

func (s *PatternElementContext) AllRelationshipPattern() []IRelationshipPatternContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IRelationshipPatternContext); ok {
			len++
		}
	}

	tst := make([]IRelationshipPatternContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IRelationshipPatternContext); ok {
			tst[i] = t.(IRelationshipPatternContext)
			i++
		}
	}

	return tst
}

func (s *PatternElementContext) RelationshipPattern(i int) IRelationshipPatternContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRelationshipPatternContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRelationshipPatternContext)
}

func (s *PatternElementContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PatternElementContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PatternElementContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPatternElement(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) PatternElement() (localctx IPatternElementContext) {
	localctx = NewPatternElementContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 54, CypherParserRULE_patternElement)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(352)
		p.NodePattern()
	}
	p.SetState(358)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserLT || _la == CypherParserSUB {
		{
			p.SetState(353)
			p.RelationshipPattern()
		}
		{
			p.SetState(354)
			p.NodePattern()
		}

		p.SetState(360)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// INodePatternContext is an interface to support dynamic dispatch.
type INodePatternContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LPAREN() antlr.TerminalNode
	RPAREN() antlr.TerminalNode
	Variable() IVariableContext
	NodeLabels() INodeLabelsContext
	Properties() IPropertiesContext

	// IsNodePatternContext differentiates from other interfaces.
	IsNodePatternContext()
}

type NodePatternContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyNodePatternContext() *NodePatternContext {
	var p = new(NodePatternContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_nodePattern
	return p
}

func InitEmptyNodePatternContext(p *NodePatternContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_nodePattern
}

func (*NodePatternContext) IsNodePatternContext() {}

func NewNodePatternContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *NodePatternContext {
	var p = new(NodePatternContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_nodePattern

	return p
}

func (s *NodePatternContext) GetParser() antlr.Parser { return s.parser }

func (s *NodePatternContext) LPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserLPAREN, 0)
}

func (s *NodePatternContext) RPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserRPAREN, 0)
}

func (s *NodePatternContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *NodePatternContext) NodeLabels() INodeLabelsContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(INodeLabelsContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(INodeLabelsContext)
}

func (s *NodePatternContext) Properties() IPropertiesContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPropertiesContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPropertiesContext)
}

func (s *NodePatternContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *NodePatternContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *NodePatternContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitNodePattern(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) NodePattern() (localctx INodePatternContext) {
	localctx = NewNodePatternContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 56, CypherParserRULE_nodePattern)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(361)
		p.Match(CypherParserLPAREN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(363)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if (int64((_la-28)) & ^0x3f) == 0 && ((int64(1)<<(_la-28))&9223372036854775807) != 0 {
		{
			p.SetState(362)
			p.Variable()
		}

	}
	p.SetState(366)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserCOLON {
		{
			p.SetState(365)
			p.NodeLabels()
		}

	}
	p.SetState(369)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserLBRACE {
		{
			p.SetState(368)
			p.Properties()
		}

	}
	{
		p.SetState(371)
		p.Match(CypherParserRPAREN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// INodeLabelsContext is an interface to support dynamic dispatch.
type INodeLabelsContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllCOLON() []antlr.TerminalNode
	COLON(i int) antlr.TerminalNode
	AllLabelName() []ILabelNameContext
	LabelName(i int) ILabelNameContext

	// IsNodeLabelsContext differentiates from other interfaces.
	IsNodeLabelsContext()
}

type NodeLabelsContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyNodeLabelsContext() *NodeLabelsContext {
	var p = new(NodeLabelsContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_nodeLabels
	return p
}

func InitEmptyNodeLabelsContext(p *NodeLabelsContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_nodeLabels
}

func (*NodeLabelsContext) IsNodeLabelsContext() {}

func NewNodeLabelsContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *NodeLabelsContext {
	var p = new(NodeLabelsContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_nodeLabels

	return p
}

func (s *NodeLabelsContext) GetParser() antlr.Parser { return s.parser }

func (s *NodeLabelsContext) AllCOLON() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOLON)
}

func (s *NodeLabelsContext) COLON(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOLON, i)
}

func (s *NodeLabelsContext) AllLabelName() []ILabelNameContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(ILabelNameContext); ok {
			len++
		}
	}

	tst := make([]ILabelNameContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(ILabelNameContext); ok {
			tst[i] = t.(ILabelNameContext)
			i++
		}
	}

	return tst
}

func (s *NodeLabelsContext) LabelName(i int) ILabelNameContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ILabelNameContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(ILabelNameContext)
}

func (s *NodeLabelsContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *NodeLabelsContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *NodeLabelsContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitNodeLabels(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) NodeLabels() (localctx INodeLabelsContext) {
	localctx = NewNodeLabelsContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 58, CypherParserRULE_nodeLabels)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	p.SetState(375)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for ok := true; ok; ok = _la == CypherParserCOLON {
		{
			p.SetState(373)
			p.Match(CypherParserCOLON)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(374)
			p.LabelName()
		}

		p.SetState(377)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRelationshipPatternContext is an interface to support dynamic dispatch.
type IRelationshipPatternContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser
	// IsRelationshipPatternContext differentiates from other interfaces.
	IsRelationshipPatternContext()
}

type RelationshipPatternContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRelationshipPatternContext() *RelationshipPatternContext {
	var p = new(RelationshipPatternContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relationshipPattern
	return p
}

func InitEmptyRelationshipPatternContext(p *RelationshipPatternContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relationshipPattern
}

func (*RelationshipPatternContext) IsRelationshipPatternContext() {}

func NewRelationshipPatternContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RelationshipPatternContext {
	var p = new(RelationshipPatternContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_relationshipPattern

	return p
}

func (s *RelationshipPatternContext) GetParser() antlr.Parser { return s.parser }

func (s *RelationshipPatternContext) CopyAll(ctx *RelationshipPatternContext) {
	s.CopyFrom(&ctx.BaseParserRuleContext)
}

func (s *RelationshipPatternContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelationshipPatternContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

type RelLeftPatternContext struct {
	RelationshipPatternContext
}

func NewRelLeftPatternContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *RelLeftPatternContext {
	var p = new(RelLeftPatternContext)

	InitEmptyRelationshipPatternContext(&p.RelationshipPatternContext)
	p.parser = parser
	p.CopyAll(ctx.(*RelationshipPatternContext))

	return p
}

func (s *RelLeftPatternContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelLeftPatternContext) LeftArrow() ILeftArrowContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ILeftArrowContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ILeftArrowContext)
}

func (s *RelLeftPatternContext) AllDash() []IDashContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IDashContext); ok {
			len++
		}
	}

	tst := make([]IDashContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IDashContext); ok {
			tst[i] = t.(IDashContext)
			i++
		}
	}

	return tst
}

func (s *RelLeftPatternContext) Dash(i int) IDashContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IDashContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IDashContext)
}

func (s *RelLeftPatternContext) RelationDetail() IRelationDetailContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRelationDetailContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRelationDetailContext)
}

func (s *RelLeftPatternContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRelLeftPattern(s)

	default:
		return t.VisitChildren(s)
	}
}

type RelRightPatternContext struct {
	RelationshipPatternContext
}

func NewRelRightPatternContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *RelRightPatternContext {
	var p = new(RelRightPatternContext)

	InitEmptyRelationshipPatternContext(&p.RelationshipPatternContext)
	p.parser = parser
	p.CopyAll(ctx.(*RelationshipPatternContext))

	return p
}

func (s *RelRightPatternContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelRightPatternContext) AllDash() []IDashContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IDashContext); ok {
			len++
		}
	}

	tst := make([]IDashContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IDashContext); ok {
			tst[i] = t.(IDashContext)
			i++
		}
	}

	return tst
}

func (s *RelRightPatternContext) Dash(i int) IDashContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IDashContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IDashContext)
}

func (s *RelRightPatternContext) RightArrow() IRightArrowContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRightArrowContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRightArrowContext)
}

func (s *RelRightPatternContext) RelationDetail() IRelationDetailContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRelationDetailContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRelationDetailContext)
}

func (s *RelRightPatternContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRelRightPattern(s)

	default:
		return t.VisitChildren(s)
	}
}

type RelBothPatternContext struct {
	RelationshipPatternContext
}

func NewRelBothPatternContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *RelBothPatternContext {
	var p = new(RelBothPatternContext)

	InitEmptyRelationshipPatternContext(&p.RelationshipPatternContext)
	p.parser = parser
	p.CopyAll(ctx.(*RelationshipPatternContext))

	return p
}

func (s *RelBothPatternContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelBothPatternContext) AllDash() []IDashContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IDashContext); ok {
			len++
		}
	}

	tst := make([]IDashContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IDashContext); ok {
			tst[i] = t.(IDashContext)
			i++
		}
	}

	return tst
}

func (s *RelBothPatternContext) Dash(i int) IDashContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IDashContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IDashContext)
}

func (s *RelBothPatternContext) RelationDetail() IRelationDetailContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRelationDetailContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRelationDetailContext)
}

func (s *RelBothPatternContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRelBothPattern(s)

	default:
		return t.VisitChildren(s)
	}
}

type RelFullPatternContext struct {
	RelationshipPatternContext
}

func NewRelFullPatternContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *RelFullPatternContext {
	var p = new(RelFullPatternContext)

	InitEmptyRelationshipPatternContext(&p.RelationshipPatternContext)
	p.parser = parser
	p.CopyAll(ctx.(*RelationshipPatternContext))

	return p
}

func (s *RelFullPatternContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelFullPatternContext) LeftArrow() ILeftArrowContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ILeftArrowContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ILeftArrowContext)
}

func (s *RelFullPatternContext) AllDash() []IDashContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IDashContext); ok {
			len++
		}
	}

	tst := make([]IDashContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IDashContext); ok {
			tst[i] = t.(IDashContext)
			i++
		}
	}

	return tst
}

func (s *RelFullPatternContext) Dash(i int) IDashContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IDashContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IDashContext)
}

func (s *RelFullPatternContext) RightArrow() IRightArrowContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRightArrowContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRightArrowContext)
}

func (s *RelFullPatternContext) RelationDetail() IRelationDetailContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRelationDetailContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRelationDetailContext)
}

func (s *RelFullPatternContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRelFullPattern(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RelationshipPattern() (localctx IRelationshipPatternContext) {
	localctx = NewRelationshipPatternContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 60, CypherParserRULE_relationshipPattern)
	var _la int

	p.SetState(407)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 38, p.GetParserRuleContext()) {
	case 1:
		localctx = NewRelFullPatternContext(p, localctx)
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(379)
			p.LeftArrow()
		}
		{
			p.SetState(380)
			p.Dash()
		}
		p.SetState(382)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		if _la == CypherParserLBRACK {
			{
				p.SetState(381)
				p.RelationDetail()
			}

		}
		{
			p.SetState(384)
			p.Dash()
		}
		{
			p.SetState(385)
			p.RightArrow()
		}

	case 2:
		localctx = NewRelLeftPatternContext(p, localctx)
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(387)
			p.LeftArrow()
		}
		{
			p.SetState(388)
			p.Dash()
		}
		p.SetState(390)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		if _la == CypherParserLBRACK {
			{
				p.SetState(389)
				p.RelationDetail()
			}

		}
		{
			p.SetState(392)
			p.Dash()
		}

	case 3:
		localctx = NewRelRightPatternContext(p, localctx)
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(394)
			p.Dash()
		}
		p.SetState(396)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		if _la == CypherParserLBRACK {
			{
				p.SetState(395)
				p.RelationDetail()
			}

		}
		{
			p.SetState(398)
			p.Dash()
		}
		{
			p.SetState(399)
			p.RightArrow()
		}

	case 4:
		localctx = NewRelBothPatternContext(p, localctx)
		p.EnterOuterAlt(localctx, 4)
		{
			p.SetState(401)
			p.Dash()
		}
		p.SetState(403)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		if _la == CypherParserLBRACK {
			{
				p.SetState(402)
				p.RelationDetail()
			}

		}
		{
			p.SetState(405)
			p.Dash()
		}

	case antlr.ATNInvalidAltNumber:
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ILeftArrowContext is an interface to support dynamic dispatch.
type ILeftArrowContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LT() antlr.TerminalNode

	// IsLeftArrowContext differentiates from other interfaces.
	IsLeftArrowContext()
}

type LeftArrowContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyLeftArrowContext() *LeftArrowContext {
	var p = new(LeftArrowContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_leftArrow
	return p
}

func InitEmptyLeftArrowContext(p *LeftArrowContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_leftArrow
}

func (*LeftArrowContext) IsLeftArrowContext() {}

func NewLeftArrowContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *LeftArrowContext {
	var p = new(LeftArrowContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_leftArrow

	return p
}

func (s *LeftArrowContext) GetParser() antlr.Parser { return s.parser }

func (s *LeftArrowContext) LT() antlr.TerminalNode {
	return s.GetToken(CypherParserLT, 0)
}

func (s *LeftArrowContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *LeftArrowContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *LeftArrowContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitLeftArrow(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) LeftArrow() (localctx ILeftArrowContext) {
	localctx = NewLeftArrowContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 62, CypherParserRULE_leftArrow)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(409)
		p.Match(CypherParserLT)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRightArrowContext is an interface to support dynamic dispatch.
type IRightArrowContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	GT() antlr.TerminalNode

	// IsRightArrowContext differentiates from other interfaces.
	IsRightArrowContext()
}

type RightArrowContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRightArrowContext() *RightArrowContext {
	var p = new(RightArrowContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_rightArrow
	return p
}

func InitEmptyRightArrowContext(p *RightArrowContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_rightArrow
}

func (*RightArrowContext) IsRightArrowContext() {}

func NewRightArrowContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RightArrowContext {
	var p = new(RightArrowContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_rightArrow

	return p
}

func (s *RightArrowContext) GetParser() antlr.Parser { return s.parser }

func (s *RightArrowContext) GT() antlr.TerminalNode {
	return s.GetToken(CypherParserGT, 0)
}

func (s *RightArrowContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RightArrowContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *RightArrowContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRightArrow(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RightArrow() (localctx IRightArrowContext) {
	localctx = NewRightArrowContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 64, CypherParserRULE_rightArrow)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(411)
		p.Match(CypherParserGT)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IDashContext is an interface to support dynamic dispatch.
type IDashContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SUB() antlr.TerminalNode

	// IsDashContext differentiates from other interfaces.
	IsDashContext()
}

type DashContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyDashContext() *DashContext {
	var p = new(DashContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_dash
	return p
}

func InitEmptyDashContext(p *DashContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_dash
}

func (*DashContext) IsDashContext() {}

func NewDashContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *DashContext {
	var p = new(DashContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_dash

	return p
}

func (s *DashContext) GetParser() antlr.Parser { return s.parser }

func (s *DashContext) SUB() antlr.TerminalNode {
	return s.GetToken(CypherParserSUB, 0)
}

func (s *DashContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *DashContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *DashContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitDash(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Dash() (localctx IDashContext) {
	localctx = NewDashContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 66, CypherParserRULE_dash)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(413)
		p.Match(CypherParserSUB)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRelationDetailContext is an interface to support dynamic dispatch.
type IRelationDetailContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LBRACK() antlr.TerminalNode
	RBRACK() antlr.TerminalNode
	Variable() IVariableContext
	RelationshipTypes() IRelationshipTypesContext
	RangeLiteral() IRangeLiteralContext
	Properties() IPropertiesContext

	// IsRelationDetailContext differentiates from other interfaces.
	IsRelationDetailContext()
}

type RelationDetailContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRelationDetailContext() *RelationDetailContext {
	var p = new(RelationDetailContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relationDetail
	return p
}

func InitEmptyRelationDetailContext(p *RelationDetailContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relationDetail
}

func (*RelationDetailContext) IsRelationDetailContext() {}

func NewRelationDetailContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RelationDetailContext {
	var p = new(RelationDetailContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_relationDetail

	return p
}

func (s *RelationDetailContext) GetParser() antlr.Parser { return s.parser }

func (s *RelationDetailContext) LBRACK() antlr.TerminalNode {
	return s.GetToken(CypherParserLBRACK, 0)
}

func (s *RelationDetailContext) RBRACK() antlr.TerminalNode {
	return s.GetToken(CypherParserRBRACK, 0)
}

func (s *RelationDetailContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *RelationDetailContext) RelationshipTypes() IRelationshipTypesContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRelationshipTypesContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRelationshipTypesContext)
}

func (s *RelationDetailContext) RangeLiteral() IRangeLiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRangeLiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRangeLiteralContext)
}

func (s *RelationDetailContext) Properties() IPropertiesContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPropertiesContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPropertiesContext)
}

func (s *RelationDetailContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelationDetailContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *RelationDetailContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRelationDetail(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RelationDetail() (localctx IRelationDetailContext) {
	localctx = NewRelationDetailContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 68, CypherParserRULE_relationDetail)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(415)
		p.Match(CypherParserLBRACK)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(417)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if (int64((_la-28)) & ^0x3f) == 0 && ((int64(1)<<(_la-28))&9223372036854775807) != 0 {
		{
			p.SetState(416)
			p.Variable()
		}

	}
	p.SetState(420)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserCOLON {
		{
			p.SetState(419)
			p.RelationshipTypes()
		}

	}
	p.SetState(423)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserMULT {
		{
			p.SetState(422)
			p.RangeLiteral()
		}

	}
	p.SetState(426)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserLBRACE {
		{
			p.SetState(425)
			p.Properties()
		}

	}
	{
		p.SetState(428)
		p.Match(CypherParserRBRACK)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRelationshipTypesContext is an interface to support dynamic dispatch.
type IRelationshipTypesContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllCOLON() []antlr.TerminalNode
	COLON(i int) antlr.TerminalNode
	AllRelTypeName() []IRelTypeNameContext
	RelTypeName(i int) IRelTypeNameContext
	AllSTICK() []antlr.TerminalNode
	STICK(i int) antlr.TerminalNode

	// IsRelationshipTypesContext differentiates from other interfaces.
	IsRelationshipTypesContext()
}

type RelationshipTypesContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRelationshipTypesContext() *RelationshipTypesContext {
	var p = new(RelationshipTypesContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relationshipTypes
	return p
}

func InitEmptyRelationshipTypesContext(p *RelationshipTypesContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relationshipTypes
}

func (*RelationshipTypesContext) IsRelationshipTypesContext() {}

func NewRelationshipTypesContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RelationshipTypesContext {
	var p = new(RelationshipTypesContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_relationshipTypes

	return p
}

func (s *RelationshipTypesContext) GetParser() antlr.Parser { return s.parser }

func (s *RelationshipTypesContext) AllCOLON() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOLON)
}

func (s *RelationshipTypesContext) COLON(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOLON, i)
}

func (s *RelationshipTypesContext) AllRelTypeName() []IRelTypeNameContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IRelTypeNameContext); ok {
			len++
		}
	}

	tst := make([]IRelTypeNameContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IRelTypeNameContext); ok {
			tst[i] = t.(IRelTypeNameContext)
			i++
		}
	}

	return tst
}

func (s *RelationshipTypesContext) RelTypeName(i int) IRelTypeNameContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IRelTypeNameContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IRelTypeNameContext)
}

func (s *RelationshipTypesContext) AllSTICK() []antlr.TerminalNode {
	return s.GetTokens(CypherParserSTICK)
}

func (s *RelationshipTypesContext) STICK(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserSTICK, i)
}

func (s *RelationshipTypesContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelationshipTypesContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *RelationshipTypesContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRelationshipTypes(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RelationshipTypes() (localctx IRelationshipTypesContext) {
	localctx = NewRelationshipTypesContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 70, CypherParserRULE_relationshipTypes)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(430)
		p.Match(CypherParserCOLON)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(431)
		p.RelTypeName()
	}
	p.SetState(439)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserSTICK {
		{
			p.SetState(432)
			p.Match(CypherParserSTICK)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		p.SetState(434)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		if _la == CypherParserCOLON {
			{
				p.SetState(433)
				p.Match(CypherParserCOLON)
				if p.HasError() {
					// Recognition error - abort rule
					goto errorExit
				}
			}

		}
		{
			p.SetState(436)
			p.RelTypeName()
		}

		p.SetState(441)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRangeLiteralContext is an interface to support dynamic dispatch.
type IRangeLiteralContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	MULT() antlr.TerminalNode
	AllIntegerLiteral() []IIntegerLiteralContext
	IntegerLiteral(i int) IIntegerLiteralContext
	RANGE() antlr.TerminalNode

	// IsRangeLiteralContext differentiates from other interfaces.
	IsRangeLiteralContext()
}

type RangeLiteralContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRangeLiteralContext() *RangeLiteralContext {
	var p = new(RangeLiteralContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_rangeLiteral
	return p
}

func InitEmptyRangeLiteralContext(p *RangeLiteralContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_rangeLiteral
}

func (*RangeLiteralContext) IsRangeLiteralContext() {}

func NewRangeLiteralContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RangeLiteralContext {
	var p = new(RangeLiteralContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_rangeLiteral

	return p
}

func (s *RangeLiteralContext) GetParser() antlr.Parser { return s.parser }

func (s *RangeLiteralContext) MULT() antlr.TerminalNode {
	return s.GetToken(CypherParserMULT, 0)
}

func (s *RangeLiteralContext) AllIntegerLiteral() []IIntegerLiteralContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IIntegerLiteralContext); ok {
			len++
		}
	}

	tst := make([]IIntegerLiteralContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IIntegerLiteralContext); ok {
			tst[i] = t.(IIntegerLiteralContext)
			i++
		}
	}

	return tst
}

func (s *RangeLiteralContext) IntegerLiteral(i int) IIntegerLiteralContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IIntegerLiteralContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IIntegerLiteralContext)
}

func (s *RangeLiteralContext) RANGE() antlr.TerminalNode {
	return s.GetToken(CypherParserRANGE, 0)
}

func (s *RangeLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RangeLiteralContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *RangeLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRangeLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RangeLiteral() (localctx IRangeLiteralContext) {
	localctx = NewRangeLiteralContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 72, CypherParserRULE_rangeLiteral)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(442)
		p.Match(CypherParserMULT)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(444)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if (int64((_la-92)) & ^0x3f) == 0 && ((int64(1)<<(_la-92))&11) != 0 {
		{
			p.SetState(443)
			p.IntegerLiteral()
		}

	}
	p.SetState(450)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserRANGE {
		{
			p.SetState(446)
			p.Match(CypherParserRANGE)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		p.SetState(448)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		if (int64((_la-92)) & ^0x3f) == 0 && ((int64(1)<<(_la-92))&11) != 0 {
			{
				p.SetState(447)
				p.IntegerLiteral()
			}

		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPropertiesContext is an interface to support dynamic dispatch.
type IPropertiesContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	MapLiteral() IMapLiteralContext

	// IsPropertiesContext differentiates from other interfaces.
	IsPropertiesContext()
}

type PropertiesContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPropertiesContext() *PropertiesContext {
	var p = new(PropertiesContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_properties
	return p
}

func InitEmptyPropertiesContext(p *PropertiesContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_properties
}

func (*PropertiesContext) IsPropertiesContext() {}

func NewPropertiesContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PropertiesContext {
	var p = new(PropertiesContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_properties

	return p
}

func (s *PropertiesContext) GetParser() antlr.Parser { return s.parser }

func (s *PropertiesContext) MapLiteral() IMapLiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IMapLiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IMapLiteralContext)
}

func (s *PropertiesContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PropertiesContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PropertiesContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitProperties(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Properties() (localctx IPropertiesContext) {
	localctx = NewPropertiesContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 74, CypherParserRULE_properties)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(452)
		p.MapLiteral()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IExpressionContext is an interface to support dynamic dispatch.
type IExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	OrExpression() IOrExpressionContext

	// IsExpressionContext differentiates from other interfaces.
	IsExpressionContext()
}

type ExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyExpressionContext() *ExpressionContext {
	var p = new(ExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_expression
	return p
}

func InitEmptyExpressionContext(p *ExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_expression
}

func (*ExpressionContext) IsExpressionContext() {}

func NewExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ExpressionContext {
	var p = new(ExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_expression

	return p
}

func (s *ExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *ExpressionContext) OrExpression() IOrExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IOrExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IOrExpressionContext)
}

func (s *ExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Expression() (localctx IExpressionContext) {
	localctx = NewExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 76, CypherParserRULE_expression)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(454)
		p.OrExpression()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IOrExpressionContext is an interface to support dynamic dispatch.
type IOrExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllXorExpression() []IXorExpressionContext
	XorExpression(i int) IXorExpressionContext
	AllOR() []antlr.TerminalNode
	OR(i int) antlr.TerminalNode

	// IsOrExpressionContext differentiates from other interfaces.
	IsOrExpressionContext()
}

type OrExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyOrExpressionContext() *OrExpressionContext {
	var p = new(OrExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_orExpression
	return p
}

func InitEmptyOrExpressionContext(p *OrExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_orExpression
}

func (*OrExpressionContext) IsOrExpressionContext() {}

func NewOrExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *OrExpressionContext {
	var p = new(OrExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_orExpression

	return p
}

func (s *OrExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *OrExpressionContext) AllXorExpression() []IXorExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IXorExpressionContext); ok {
			len++
		}
	}

	tst := make([]IXorExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IXorExpressionContext); ok {
			tst[i] = t.(IXorExpressionContext)
			i++
		}
	}

	return tst
}

func (s *OrExpressionContext) XorExpression(i int) IXorExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IXorExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IXorExpressionContext)
}

func (s *OrExpressionContext) AllOR() []antlr.TerminalNode {
	return s.GetTokens(CypherParserOR)
}

func (s *OrExpressionContext) OR(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserOR, i)
}

func (s *OrExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *OrExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *OrExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitOrExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) OrExpression() (localctx IOrExpressionContext) {
	localctx = NewOrExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 78, CypherParserRULE_orExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(456)
		p.XorExpression()
	}
	p.SetState(461)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserOR {
		{
			p.SetState(457)
			p.Match(CypherParserOR)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(458)
			p.XorExpression()
		}

		p.SetState(463)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IXorExpressionContext is an interface to support dynamic dispatch.
type IXorExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllAndExpression() []IAndExpressionContext
	AndExpression(i int) IAndExpressionContext
	AllXOR() []antlr.TerminalNode
	XOR(i int) antlr.TerminalNode

	// IsXorExpressionContext differentiates from other interfaces.
	IsXorExpressionContext()
}

type XorExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyXorExpressionContext() *XorExpressionContext {
	var p = new(XorExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_xorExpression
	return p
}

func InitEmptyXorExpressionContext(p *XorExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_xorExpression
}

func (*XorExpressionContext) IsXorExpressionContext() {}

func NewXorExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *XorExpressionContext {
	var p = new(XorExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_xorExpression

	return p
}

func (s *XorExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *XorExpressionContext) AllAndExpression() []IAndExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IAndExpressionContext); ok {
			len++
		}
	}

	tst := make([]IAndExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IAndExpressionContext); ok {
			tst[i] = t.(IAndExpressionContext)
			i++
		}
	}

	return tst
}

func (s *XorExpressionContext) AndExpression(i int) IAndExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAndExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAndExpressionContext)
}

func (s *XorExpressionContext) AllXOR() []antlr.TerminalNode {
	return s.GetTokens(CypherParserXOR)
}

func (s *XorExpressionContext) XOR(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserXOR, i)
}

func (s *XorExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *XorExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *XorExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitXorExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) XorExpression() (localctx IXorExpressionContext) {
	localctx = NewXorExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 80, CypherParserRULE_xorExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(464)
		p.AndExpression()
	}
	p.SetState(469)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserXOR {
		{
			p.SetState(465)
			p.Match(CypherParserXOR)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(466)
			p.AndExpression()
		}

		p.SetState(471)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IAndExpressionContext is an interface to support dynamic dispatch.
type IAndExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllNotExpression() []INotExpressionContext
	NotExpression(i int) INotExpressionContext
	AllAND() []antlr.TerminalNode
	AND(i int) antlr.TerminalNode

	// IsAndExpressionContext differentiates from other interfaces.
	IsAndExpressionContext()
}

type AndExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyAndExpressionContext() *AndExpressionContext {
	var p = new(AndExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_andExpression
	return p
}

func InitEmptyAndExpressionContext(p *AndExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_andExpression
}

func (*AndExpressionContext) IsAndExpressionContext() {}

func NewAndExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *AndExpressionContext {
	var p = new(AndExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_andExpression

	return p
}

func (s *AndExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *AndExpressionContext) AllNotExpression() []INotExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(INotExpressionContext); ok {
			len++
		}
	}

	tst := make([]INotExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(INotExpressionContext); ok {
			tst[i] = t.(INotExpressionContext)
			i++
		}
	}

	return tst
}

func (s *AndExpressionContext) NotExpression(i int) INotExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(INotExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(INotExpressionContext)
}

func (s *AndExpressionContext) AllAND() []antlr.TerminalNode {
	return s.GetTokens(CypherParserAND)
}

func (s *AndExpressionContext) AND(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserAND, i)
}

func (s *AndExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *AndExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *AndExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitAndExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) AndExpression() (localctx IAndExpressionContext) {
	localctx = NewAndExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 82, CypherParserRULE_andExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(472)
		p.NotExpression()
	}
	p.SetState(477)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserAND {
		{
			p.SetState(473)
			p.Match(CypherParserAND)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(474)
			p.NotExpression()
		}

		p.SetState(479)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// INotExpressionContext is an interface to support dynamic dispatch.
type INotExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	NOT() antlr.TerminalNode
	NotExpression() INotExpressionContext
	ComparisonExpression() IComparisonExpressionContext

	// IsNotExpressionContext differentiates from other interfaces.
	IsNotExpressionContext()
}

type NotExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyNotExpressionContext() *NotExpressionContext {
	var p = new(NotExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_notExpression
	return p
}

func InitEmptyNotExpressionContext(p *NotExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_notExpression
}

func (*NotExpressionContext) IsNotExpressionContext() {}

func NewNotExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *NotExpressionContext {
	var p = new(NotExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_notExpression

	return p
}

func (s *NotExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *NotExpressionContext) NOT() antlr.TerminalNode {
	return s.GetToken(CypherParserNOT, 0)
}

func (s *NotExpressionContext) NotExpression() INotExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(INotExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(INotExpressionContext)
}

func (s *NotExpressionContext) ComparisonExpression() IComparisonExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IComparisonExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IComparisonExpressionContext)
}

func (s *NotExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *NotExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *NotExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitNotExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) NotExpression() (localctx INotExpressionContext) {
	localctx = NewNotExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 84, CypherParserRULE_notExpression)
	p.SetState(483)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 51, p.GetParserRuleContext()) {
	case 1:
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(480)
			p.Match(CypherParserNOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(481)
			p.NotExpression()
		}

	case 2:
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(482)
			p.ComparisonExpression()
		}

	case antlr.ATNInvalidAltNumber:
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IComparisonExpressionContext is an interface to support dynamic dispatch.
type IComparisonExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllStringPredicateExpression() []IStringPredicateExpressionContext
	StringPredicateExpression(i int) IStringPredicateExpressionContext
	AllCompOp() []ICompOpContext
	CompOp(i int) ICompOpContext

	// IsComparisonExpressionContext differentiates from other interfaces.
	IsComparisonExpressionContext()
}

type ComparisonExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyComparisonExpressionContext() *ComparisonExpressionContext {
	var p = new(ComparisonExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_comparisonExpression
	return p
}

func InitEmptyComparisonExpressionContext(p *ComparisonExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_comparisonExpression
}

func (*ComparisonExpressionContext) IsComparisonExpressionContext() {}

func NewComparisonExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ComparisonExpressionContext {
	var p = new(ComparisonExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_comparisonExpression

	return p
}

func (s *ComparisonExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *ComparisonExpressionContext) AllStringPredicateExpression() []IStringPredicateExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IStringPredicateExpressionContext); ok {
			len++
		}
	}

	tst := make([]IStringPredicateExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IStringPredicateExpressionContext); ok {
			tst[i] = t.(IStringPredicateExpressionContext)
			i++
		}
	}

	return tst
}

func (s *ComparisonExpressionContext) StringPredicateExpression(i int) IStringPredicateExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IStringPredicateExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IStringPredicateExpressionContext)
}

func (s *ComparisonExpressionContext) AllCompOp() []ICompOpContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(ICompOpContext); ok {
			len++
		}
	}

	tst := make([]ICompOpContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(ICompOpContext); ok {
			tst[i] = t.(ICompOpContext)
			i++
		}
	}

	return tst
}

func (s *ComparisonExpressionContext) CompOp(i int) ICompOpContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ICompOpContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(ICompOpContext)
}

func (s *ComparisonExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ComparisonExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ComparisonExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitComparisonExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) ComparisonExpression() (localctx IComparisonExpressionContext) {
	localctx = NewComparisonExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 86, CypherParserRULE_comparisonExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(485)
		p.StringPredicateExpression()
	}
	p.SetState(491)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for (int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&498) != 0 {
		{
			p.SetState(486)
			p.CompOp()
		}
		{
			p.SetState(487)
			p.StringPredicateExpression()
		}

		p.SetState(493)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ICompOpContext is an interface to support dynamic dispatch.
type ICompOpContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	ASSIGN() antlr.TerminalNode
	NOT_EQUAL() antlr.TerminalNode
	LT() antlr.TerminalNode
	GT() antlr.TerminalNode
	LE() antlr.TerminalNode
	GE() antlr.TerminalNode

	// IsCompOpContext differentiates from other interfaces.
	IsCompOpContext()
}

type CompOpContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyCompOpContext() *CompOpContext {
	var p = new(CompOpContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_compOp
	return p
}

func InitEmptyCompOpContext(p *CompOpContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_compOp
}

func (*CompOpContext) IsCompOpContext() {}

func NewCompOpContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *CompOpContext {
	var p = new(CompOpContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_compOp

	return p
}

func (s *CompOpContext) GetParser() antlr.Parser { return s.parser }

func (s *CompOpContext) ASSIGN() antlr.TerminalNode {
	return s.GetToken(CypherParserASSIGN, 0)
}

func (s *CompOpContext) NOT_EQUAL() antlr.TerminalNode {
	return s.GetToken(CypherParserNOT_EQUAL, 0)
}

func (s *CompOpContext) LT() antlr.TerminalNode {
	return s.GetToken(CypherParserLT, 0)
}

func (s *CompOpContext) GT() antlr.TerminalNode {
	return s.GetToken(CypherParserGT, 0)
}

func (s *CompOpContext) LE() antlr.TerminalNode {
	return s.GetToken(CypherParserLE, 0)
}

func (s *CompOpContext) GE() antlr.TerminalNode {
	return s.GetToken(CypherParserGE, 0)
}

func (s *CompOpContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *CompOpContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *CompOpContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitCompOp(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) CompOp() (localctx ICompOpContext) {
	localctx = NewCompOpContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 88, CypherParserRULE_compOp)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(494)
		_la = p.GetTokenStream().LA(1)

		if !((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&498) != 0) {
			p.GetErrorHandler().RecoverInline(p)
		} else {
			p.GetErrorHandler().ReportMatch(p)
			p.Consume()
		}
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IStringPredicateExpressionContext is an interface to support dynamic dispatch.
type IStringPredicateExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AddSubExpression() IAddSubExpressionContext
	AllStringPredicateSuffix() []IStringPredicateSuffixContext
	StringPredicateSuffix(i int) IStringPredicateSuffixContext

	// IsStringPredicateExpressionContext differentiates from other interfaces.
	IsStringPredicateExpressionContext()
}

type StringPredicateExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyStringPredicateExpressionContext() *StringPredicateExpressionContext {
	var p = new(StringPredicateExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_stringPredicateExpression
	return p
}

func InitEmptyStringPredicateExpressionContext(p *StringPredicateExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_stringPredicateExpression
}

func (*StringPredicateExpressionContext) IsStringPredicateExpressionContext() {}

func NewStringPredicateExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *StringPredicateExpressionContext {
	var p = new(StringPredicateExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_stringPredicateExpression

	return p
}

func (s *StringPredicateExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *StringPredicateExpressionContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *StringPredicateExpressionContext) AllStringPredicateSuffix() []IStringPredicateSuffixContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IStringPredicateSuffixContext); ok {
			len++
		}
	}

	tst := make([]IStringPredicateSuffixContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IStringPredicateSuffixContext); ok {
			tst[i] = t.(IStringPredicateSuffixContext)
			i++
		}
	}

	return tst
}

func (s *StringPredicateExpressionContext) StringPredicateSuffix(i int) IStringPredicateSuffixContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IStringPredicateSuffixContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IStringPredicateSuffixContext)
}

func (s *StringPredicateExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *StringPredicateExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *StringPredicateExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitStringPredicateExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) StringPredicateExpression() (localctx IStringPredicateExpressionContext) {
	localctx = NewStringPredicateExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 90, CypherParserRULE_stringPredicateExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(496)
		p.AddSubExpression()
	}
	p.SetState(500)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserREGEX || _la == CypherParserCONTAINS || ((int64((_la-64)) & ^0x3f) == 0 && ((int64(1)<<(_la-64))&47) != 0) {
		{
			p.SetState(497)
			p.StringPredicateSuffix()
		}

		p.SetState(502)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IStringPredicateSuffixContext is an interface to support dynamic dispatch.
type IStringPredicateSuffixContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser
	// IsStringPredicateSuffixContext differentiates from other interfaces.
	IsStringPredicateSuffixContext()
}

type StringPredicateSuffixContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyStringPredicateSuffixContext() *StringPredicateSuffixContext {
	var p = new(StringPredicateSuffixContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_stringPredicateSuffix
	return p
}

func InitEmptyStringPredicateSuffixContext(p *StringPredicateSuffixContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_stringPredicateSuffix
}

func (*StringPredicateSuffixContext) IsStringPredicateSuffixContext() {}

func NewStringPredicateSuffixContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *StringPredicateSuffixContext {
	var p = new(StringPredicateSuffixContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_stringPredicateSuffix

	return p
}

func (s *StringPredicateSuffixContext) GetParser() antlr.Parser { return s.parser }

func (s *StringPredicateSuffixContext) CopyAll(ctx *StringPredicateSuffixContext) {
	s.CopyFrom(&ctx.BaseParserRuleContext)
}

func (s *StringPredicateSuffixContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *StringPredicateSuffixContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

type ContainsPredicateContext struct {
	StringPredicateSuffixContext
}

func NewContainsPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *ContainsPredicateContext {
	var p = new(ContainsPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *ContainsPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ContainsPredicateContext) CONTAINS() antlr.TerminalNode {
	return s.GetToken(CypherParserCONTAINS, 0)
}

func (s *ContainsPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *ContainsPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitContainsPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type RegexPredicateContext struct {
	StringPredicateSuffixContext
}

func NewRegexPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *RegexPredicateContext {
	var p = new(RegexPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *RegexPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RegexPredicateContext) REGEX() antlr.TerminalNode {
	return s.GetToken(CypherParserREGEX, 0)
}

func (s *RegexPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *RegexPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRegexPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type NotContainsPredicateContext struct {
	StringPredicateSuffixContext
}

func NewNotContainsPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *NotContainsPredicateContext {
	var p = new(NotContainsPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *NotContainsPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *NotContainsPredicateContext) NOT() antlr.TerminalNode {
	return s.GetToken(CypherParserNOT, 0)
}

func (s *NotContainsPredicateContext) CONTAINS() antlr.TerminalNode {
	return s.GetToken(CypherParserCONTAINS, 0)
}

func (s *NotContainsPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *NotContainsPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitNotContainsPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type EndsWithPredicateContext struct {
	StringPredicateSuffixContext
}

func NewEndsWithPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *EndsWithPredicateContext {
	var p = new(EndsWithPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *EndsWithPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *EndsWithPredicateContext) ENDS() antlr.TerminalNode {
	return s.GetToken(CypherParserENDS, 0)
}

func (s *EndsWithPredicateContext) WITH() antlr.TerminalNode {
	return s.GetToken(CypherParserWITH, 0)
}

func (s *EndsWithPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *EndsWithPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitEndsWithPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type IsNullPredicateContext struct {
	StringPredicateSuffixContext
}

func NewIsNullPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *IsNullPredicateContext {
	var p = new(IsNullPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *IsNullPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *IsNullPredicateContext) IS() antlr.TerminalNode {
	return s.GetToken(CypherParserIS, 0)
}

func (s *IsNullPredicateContext) NULL_W() antlr.TerminalNode {
	return s.GetToken(CypherParserNULL_W, 0)
}

func (s *IsNullPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitIsNullPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type StartsWithPredicateContext struct {
	StringPredicateSuffixContext
}

func NewStartsWithPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *StartsWithPredicateContext {
	var p = new(StartsWithPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *StartsWithPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *StartsWithPredicateContext) STARTS() antlr.TerminalNode {
	return s.GetToken(CypherParserSTARTS, 0)
}

func (s *StartsWithPredicateContext) WITH() antlr.TerminalNode {
	return s.GetToken(CypherParserWITH, 0)
}

func (s *StartsWithPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *StartsWithPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitStartsWithPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type InPredicateContext struct {
	StringPredicateSuffixContext
}

func NewInPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *InPredicateContext {
	var p = new(InPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *InPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *InPredicateContext) IN() antlr.TerminalNode {
	return s.GetToken(CypherParserIN, 0)
}

func (s *InPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *InPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitInPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type NotEndsWithPredicateContext struct {
	StringPredicateSuffixContext
}

func NewNotEndsWithPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *NotEndsWithPredicateContext {
	var p = new(NotEndsWithPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *NotEndsWithPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *NotEndsWithPredicateContext) NOT() antlr.TerminalNode {
	return s.GetToken(CypherParserNOT, 0)
}

func (s *NotEndsWithPredicateContext) ENDS() antlr.TerminalNode {
	return s.GetToken(CypherParserENDS, 0)
}

func (s *NotEndsWithPredicateContext) WITH() antlr.TerminalNode {
	return s.GetToken(CypherParserWITH, 0)
}

func (s *NotEndsWithPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *NotEndsWithPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitNotEndsWithPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type IsNotNullPredicateContext struct {
	StringPredicateSuffixContext
}

func NewIsNotNullPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *IsNotNullPredicateContext {
	var p = new(IsNotNullPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *IsNotNullPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *IsNotNullPredicateContext) IS() antlr.TerminalNode {
	return s.GetToken(CypherParserIS, 0)
}

func (s *IsNotNullPredicateContext) NOT() antlr.TerminalNode {
	return s.GetToken(CypherParserNOT, 0)
}

func (s *IsNotNullPredicateContext) NULL_W() antlr.TerminalNode {
	return s.GetToken(CypherParserNULL_W, 0)
}

func (s *IsNotNullPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitIsNotNullPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

type NotStartsWithPredicateContext struct {
	StringPredicateSuffixContext
}

func NewNotStartsWithPredicateContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *NotStartsWithPredicateContext {
	var p = new(NotStartsWithPredicateContext)

	InitEmptyStringPredicateSuffixContext(&p.StringPredicateSuffixContext)
	p.parser = parser
	p.CopyAll(ctx.(*StringPredicateSuffixContext))

	return p
}

func (s *NotStartsWithPredicateContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *NotStartsWithPredicateContext) NOT() antlr.TerminalNode {
	return s.GetToken(CypherParserNOT, 0)
}

func (s *NotStartsWithPredicateContext) STARTS() antlr.TerminalNode {
	return s.GetToken(CypherParserSTARTS, 0)
}

func (s *NotStartsWithPredicateContext) WITH() antlr.TerminalNode {
	return s.GetToken(CypherParserWITH, 0)
}

func (s *NotStartsWithPredicateContext) AddSubExpression() IAddSubExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAddSubExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAddSubExpressionContext)
}

func (s *NotStartsWithPredicateContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitNotStartsWithPredicate(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) StringPredicateSuffix() (localctx IStringPredicateSuffixContext) {
	localctx = NewStringPredicateSuffixContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 92, CypherParserRULE_stringPredicateSuffix)
	p.SetState(531)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 54, p.GetParserRuleContext()) {
	case 1:
		localctx = NewStartsWithPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(503)
			p.Match(CypherParserSTARTS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(504)
			p.Match(CypherParserWITH)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(505)
			p.AddSubExpression()
		}

	case 2:
		localctx = NewEndsWithPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(506)
			p.Match(CypherParserENDS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(507)
			p.Match(CypherParserWITH)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(508)
			p.AddSubExpression()
		}

	case 3:
		localctx = NewContainsPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(509)
			p.Match(CypherParserCONTAINS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(510)
			p.AddSubExpression()
		}

	case 4:
		localctx = NewInPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 4)
		{
			p.SetState(511)
			p.Match(CypherParserIN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(512)
			p.AddSubExpression()
		}

	case 5:
		localctx = NewRegexPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 5)
		{
			p.SetState(513)
			p.Match(CypherParserREGEX)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(514)
			p.AddSubExpression()
		}

	case 6:
		localctx = NewIsNotNullPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 6)
		{
			p.SetState(515)
			p.Match(CypherParserIS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(516)
			p.Match(CypherParserNOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(517)
			p.Match(CypherParserNULL_W)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case 7:
		localctx = NewIsNullPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 7)
		{
			p.SetState(518)
			p.Match(CypherParserIS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(519)
			p.Match(CypherParserNULL_W)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case 8:
		localctx = NewNotContainsPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 8)
		{
			p.SetState(520)
			p.Match(CypherParserNOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(521)
			p.Match(CypherParserCONTAINS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(522)
			p.AddSubExpression()
		}

	case 9:
		localctx = NewNotStartsWithPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 9)
		{
			p.SetState(523)
			p.Match(CypherParserNOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(524)
			p.Match(CypherParserSTARTS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(525)
			p.Match(CypherParserWITH)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(526)
			p.AddSubExpression()
		}

	case 10:
		localctx = NewNotEndsWithPredicateContext(p, localctx)
		p.EnterOuterAlt(localctx, 10)
		{
			p.SetState(527)
			p.Match(CypherParserNOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(528)
			p.Match(CypherParserENDS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(529)
			p.Match(CypherParserWITH)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(530)
			p.AddSubExpression()
		}

	case antlr.ATNInvalidAltNumber:
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IAddSubExpressionContext is an interface to support dynamic dispatch.
type IAddSubExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllMultDivExpression() []IMultDivExpressionContext
	MultDivExpression(i int) IMultDivExpressionContext
	AllPLUS() []antlr.TerminalNode
	PLUS(i int) antlr.TerminalNode
	AllSUB() []antlr.TerminalNode
	SUB(i int) antlr.TerminalNode

	// IsAddSubExpressionContext differentiates from other interfaces.
	IsAddSubExpressionContext()
}

type AddSubExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyAddSubExpressionContext() *AddSubExpressionContext {
	var p = new(AddSubExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_addSubExpression
	return p
}

func InitEmptyAddSubExpressionContext(p *AddSubExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_addSubExpression
}

func (*AddSubExpressionContext) IsAddSubExpressionContext() {}

func NewAddSubExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *AddSubExpressionContext {
	var p = new(AddSubExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_addSubExpression

	return p
}

func (s *AddSubExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *AddSubExpressionContext) AllMultDivExpression() []IMultDivExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IMultDivExpressionContext); ok {
			len++
		}
	}

	tst := make([]IMultDivExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IMultDivExpressionContext); ok {
			tst[i] = t.(IMultDivExpressionContext)
			i++
		}
	}

	return tst
}

func (s *AddSubExpressionContext) MultDivExpression(i int) IMultDivExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IMultDivExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IMultDivExpressionContext)
}

func (s *AddSubExpressionContext) AllPLUS() []antlr.TerminalNode {
	return s.GetTokens(CypherParserPLUS)
}

func (s *AddSubExpressionContext) PLUS(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserPLUS, i)
}

func (s *AddSubExpressionContext) AllSUB() []antlr.TerminalNode {
	return s.GetTokens(CypherParserSUB)
}

func (s *AddSubExpressionContext) SUB(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserSUB, i)
}

func (s *AddSubExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *AddSubExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *AddSubExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitAddSubExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) AddSubExpression() (localctx IAddSubExpressionContext) {
	localctx = NewAddSubExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 94, CypherParserRULE_addSubExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(533)
		p.MultDivExpression()
	}
	p.SetState(538)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserSUB || _la == CypherParserPLUS {
		{
			p.SetState(534)
			_la = p.GetTokenStream().LA(1)

			if !(_la == CypherParserSUB || _la == CypherParserPLUS) {
				p.GetErrorHandler().RecoverInline(p)
			} else {
				p.GetErrorHandler().ReportMatch(p)
				p.Consume()
			}
		}
		{
			p.SetState(535)
			p.MultDivExpression()
		}

		p.SetState(540)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IMultDivExpressionContext is an interface to support dynamic dispatch.
type IMultDivExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllPowerExpression() []IPowerExpressionContext
	PowerExpression(i int) IPowerExpressionContext
	AllMULT() []antlr.TerminalNode
	MULT(i int) antlr.TerminalNode
	AllDIV() []antlr.TerminalNode
	DIV(i int) antlr.TerminalNode
	AllMOD() []antlr.TerminalNode
	MOD(i int) antlr.TerminalNode

	// IsMultDivExpressionContext differentiates from other interfaces.
	IsMultDivExpressionContext()
}

type MultDivExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyMultDivExpressionContext() *MultDivExpressionContext {
	var p = new(MultDivExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_multDivExpression
	return p
}

func InitEmptyMultDivExpressionContext(p *MultDivExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_multDivExpression
}

func (*MultDivExpressionContext) IsMultDivExpressionContext() {}

func NewMultDivExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *MultDivExpressionContext {
	var p = new(MultDivExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_multDivExpression

	return p
}

func (s *MultDivExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *MultDivExpressionContext) AllPowerExpression() []IPowerExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IPowerExpressionContext); ok {
			len++
		}
	}

	tst := make([]IPowerExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IPowerExpressionContext); ok {
			tst[i] = t.(IPowerExpressionContext)
			i++
		}
	}

	return tst
}

func (s *MultDivExpressionContext) PowerExpression(i int) IPowerExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPowerExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPowerExpressionContext)
}

func (s *MultDivExpressionContext) AllMULT() []antlr.TerminalNode {
	return s.GetTokens(CypherParserMULT)
}

func (s *MultDivExpressionContext) MULT(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserMULT, i)
}

func (s *MultDivExpressionContext) AllDIV() []antlr.TerminalNode {
	return s.GetTokens(CypherParserDIV)
}

func (s *MultDivExpressionContext) DIV(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserDIV, i)
}

func (s *MultDivExpressionContext) AllMOD() []antlr.TerminalNode {
	return s.GetTokens(CypherParserMOD)
}

func (s *MultDivExpressionContext) MOD(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserMOD, i)
}

func (s *MultDivExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *MultDivExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *MultDivExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitMultDivExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) MultDivExpression() (localctx IMultDivExpressionContext) {
	localctx = NewMultDivExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 96, CypherParserRULE_multDivExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(541)
		p.PowerExpression()
	}
	p.SetState(546)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for (int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&23068672) != 0 {
		{
			p.SetState(542)
			_la = p.GetTokenStream().LA(1)

			if !((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&23068672) != 0) {
				p.GetErrorHandler().RecoverInline(p)
			} else {
				p.GetErrorHandler().ReportMatch(p)
				p.Consume()
			}
		}
		{
			p.SetState(543)
			p.PowerExpression()
		}

		p.SetState(548)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPowerExpressionContext is an interface to support dynamic dispatch.
type IPowerExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	UnaryExpression() IUnaryExpressionContext
	CARET() antlr.TerminalNode
	PowerExpression() IPowerExpressionContext

	// IsPowerExpressionContext differentiates from other interfaces.
	IsPowerExpressionContext()
}

type PowerExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPowerExpressionContext() *PowerExpressionContext {
	var p = new(PowerExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_powerExpression
	return p
}

func InitEmptyPowerExpressionContext(p *PowerExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_powerExpression
}

func (*PowerExpressionContext) IsPowerExpressionContext() {}

func NewPowerExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PowerExpressionContext {
	var p = new(PowerExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_powerExpression

	return p
}

func (s *PowerExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *PowerExpressionContext) UnaryExpression() IUnaryExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IUnaryExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IUnaryExpressionContext)
}

func (s *PowerExpressionContext) CARET() antlr.TerminalNode {
	return s.GetToken(CypherParserCARET, 0)
}

func (s *PowerExpressionContext) PowerExpression() IPowerExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPowerExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPowerExpressionContext)
}

func (s *PowerExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PowerExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PowerExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPowerExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) PowerExpression() (localctx IPowerExpressionContext) {
	localctx = NewPowerExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 98, CypherParserRULE_powerExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(549)
		p.UnaryExpression()
	}
	p.SetState(552)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserCARET {
		{
			p.SetState(550)
			p.Match(CypherParserCARET)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(551)
			p.PowerExpression()
		}

	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IUnaryExpressionContext is an interface to support dynamic dispatch.
type IUnaryExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SUB() antlr.TerminalNode
	UnaryExpression() IUnaryExpressionContext
	PLUS() antlr.TerminalNode
	PostfixExpression() IPostfixExpressionContext

	// IsUnaryExpressionContext differentiates from other interfaces.
	IsUnaryExpressionContext()
}

type UnaryExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyUnaryExpressionContext() *UnaryExpressionContext {
	var p = new(UnaryExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_unaryExpression
	return p
}

func InitEmptyUnaryExpressionContext(p *UnaryExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_unaryExpression
}

func (*UnaryExpressionContext) IsUnaryExpressionContext() {}

func NewUnaryExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *UnaryExpressionContext {
	var p = new(UnaryExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_unaryExpression

	return p
}

func (s *UnaryExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *UnaryExpressionContext) SUB() antlr.TerminalNode {
	return s.GetToken(CypherParserSUB, 0)
}

func (s *UnaryExpressionContext) UnaryExpression() IUnaryExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IUnaryExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IUnaryExpressionContext)
}

func (s *UnaryExpressionContext) PLUS() antlr.TerminalNode {
	return s.GetToken(CypherParserPLUS, 0)
}

func (s *UnaryExpressionContext) PostfixExpression() IPostfixExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPostfixExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPostfixExpressionContext)
}

func (s *UnaryExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *UnaryExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *UnaryExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitUnaryExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) UnaryExpression() (localctx IUnaryExpressionContext) {
	localctx = NewUnaryExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 100, CypherParserRULE_unaryExpression)
	p.SetState(559)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetTokenStream().LA(1) {
	case CypherParserSUB:
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(554)
			p.Match(CypherParserSUB)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(555)
			p.UnaryExpression()
		}

	case CypherParserPLUS:
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(556)
			p.Match(CypherParserPLUS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(557)
			p.UnaryExpression()
		}

	case CypherParserLPAREN, CypherParserLBRACE, CypherParserLBRACK, CypherParserDOLLAR, CypherParserCALL, CypherParserYIELD, CypherParserFILTER, CypherParserEXTRACT, CypherParserCOUNT, CypherParserANY, CypherParserNONE, CypherParserSINGLE, CypherParserALL, CypherParserASC, CypherParserASCENDING, CypherParserBY, CypherParserCREATE, CypherParserDELETE, CypherParserDESC, CypherParserDESCENDING, CypherParserDETACH, CypherParserEXISTS, CypherParserLIMIT, CypherParserMATCH, CypherParserMERGE, CypherParserON, CypherParserOPTIONAL, CypherParserORDER, CypherParserREMOVE, CypherParserRETURN, CypherParserSET, CypherParserSKIP_W, CypherParserWHERE, CypherParserWITH, CypherParserUNION, CypherParserUNWIND, CypherParserAND, CypherParserAS, CypherParserCONTAINS, CypherParserDISTINCT, CypherParserENDS, CypherParserIN, CypherParserIS, CypherParserNOT, CypherParserOR, CypherParserSTARTS, CypherParserXOR, CypherParserFALSE, CypherParserTRUE, CypherParserNULL_W, CypherParserCONSTRAINT, CypherParserDO, CypherParserFOR, CypherParserREQUIRE, CypherParserUNIQUE, CypherParserCASE, CypherParserWHEN, CypherParserTHEN, CypherParserELSE, CypherParserEND, CypherParserMANDATORY, CypherParserSCALAR, CypherParserOF, CypherParserADD, CypherParserDROP, CypherParserID, CypherParserESC_LITERAL, CypherParserSTRING_LITERAL, CypherParserHEX_INTEGER, CypherParserOCTAL_INTEGER, CypherParserFLOAT_LITERAL, CypherParserINTEGER_LITERAL:
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(558)
			p.PostfixExpression()
		}

	default:
		p.SetError(antlr.NewNoViableAltException(p, nil, nil, nil, nil, nil))
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPostfixExpressionContext is an interface to support dynamic dispatch.
type IPostfixExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AtomExpression() IAtomExpressionContext
	AllDOT() []antlr.TerminalNode
	DOT(i int) antlr.TerminalNode
	AllPropertyKeyName() []IPropertyKeyNameContext
	PropertyKeyName(i int) IPropertyKeyNameContext
	AllLBRACK() []antlr.TerminalNode
	LBRACK(i int) antlr.TerminalNode
	AllSubscriptOrSlice() []ISubscriptOrSliceContext
	SubscriptOrSlice(i int) ISubscriptOrSliceContext
	AllRBRACK() []antlr.TerminalNode
	RBRACK(i int) antlr.TerminalNode

	// IsPostfixExpressionContext differentiates from other interfaces.
	IsPostfixExpressionContext()
}

type PostfixExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPostfixExpressionContext() *PostfixExpressionContext {
	var p = new(PostfixExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_postfixExpression
	return p
}

func InitEmptyPostfixExpressionContext(p *PostfixExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_postfixExpression
}

func (*PostfixExpressionContext) IsPostfixExpressionContext() {}

func NewPostfixExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PostfixExpressionContext {
	var p = new(PostfixExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_postfixExpression

	return p
}

func (s *PostfixExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *PostfixExpressionContext) AtomExpression() IAtomExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IAtomExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IAtomExpressionContext)
}

func (s *PostfixExpressionContext) AllDOT() []antlr.TerminalNode {
	return s.GetTokens(CypherParserDOT)
}

func (s *PostfixExpressionContext) DOT(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserDOT, i)
}

func (s *PostfixExpressionContext) AllPropertyKeyName() []IPropertyKeyNameContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IPropertyKeyNameContext); ok {
			len++
		}
	}

	tst := make([]IPropertyKeyNameContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IPropertyKeyNameContext); ok {
			tst[i] = t.(IPropertyKeyNameContext)
			i++
		}
	}

	return tst
}

func (s *PostfixExpressionContext) PropertyKeyName(i int) IPropertyKeyNameContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPropertyKeyNameContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPropertyKeyNameContext)
}

func (s *PostfixExpressionContext) AllLBRACK() []antlr.TerminalNode {
	return s.GetTokens(CypherParserLBRACK)
}

func (s *PostfixExpressionContext) LBRACK(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserLBRACK, i)
}

func (s *PostfixExpressionContext) AllSubscriptOrSlice() []ISubscriptOrSliceContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(ISubscriptOrSliceContext); ok {
			len++
		}
	}

	tst := make([]ISubscriptOrSliceContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(ISubscriptOrSliceContext); ok {
			tst[i] = t.(ISubscriptOrSliceContext)
			i++
		}
	}

	return tst
}

func (s *PostfixExpressionContext) SubscriptOrSlice(i int) ISubscriptOrSliceContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISubscriptOrSliceContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISubscriptOrSliceContext)
}

func (s *PostfixExpressionContext) AllRBRACK() []antlr.TerminalNode {
	return s.GetTokens(CypherParserRBRACK)
}

func (s *PostfixExpressionContext) RBRACK(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserRBRACK, i)
}

func (s *PostfixExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PostfixExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PostfixExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPostfixExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) PostfixExpression() (localctx IPostfixExpressionContext) {
	localctx = NewPostfixExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 102, CypherParserRULE_postfixExpression)
	var _alt int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(561)
		p.AtomExpression()
	}
	p.SetState(570)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_alt = p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 60, p.GetParserRuleContext())
	if p.HasError() {
		goto errorExit
	}
	for _alt != 2 && _alt != antlr.ATNInvalidAltNumber {
		if _alt == 1 {
			p.SetState(568)
			p.GetErrorHandler().Sync(p)
			if p.HasError() {
				goto errorExit
			}

			switch p.GetTokenStream().LA(1) {
			case CypherParserDOT:
				{
					p.SetState(562)
					p.Match(CypherParserDOT)
					if p.HasError() {
						// Recognition error - abort rule
						goto errorExit
					}
				}
				{
					p.SetState(563)
					p.PropertyKeyName()
				}

			case CypherParserLBRACK:
				{
					p.SetState(564)
					p.Match(CypherParserLBRACK)
					if p.HasError() {
						// Recognition error - abort rule
						goto errorExit
					}
				}
				{
					p.SetState(565)
					p.SubscriptOrSlice()
				}
				{
					p.SetState(566)
					p.Match(CypherParserRBRACK)
					if p.HasError() {
						// Recognition error - abort rule
						goto errorExit
					}
				}

			default:
				p.SetError(antlr.NewNoViableAltException(p, nil, nil, nil, nil, nil))
				goto errorExit
			}

		}
		p.SetState(572)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_alt = p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 60, p.GetParserRuleContext())
		if p.HasError() {
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ISubscriptOrSliceContext is an interface to support dynamic dispatch.
type ISubscriptOrSliceContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser
	// IsSubscriptOrSliceContext differentiates from other interfaces.
	IsSubscriptOrSliceContext()
}

type SubscriptOrSliceContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptySubscriptOrSliceContext() *SubscriptOrSliceContext {
	var p = new(SubscriptOrSliceContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_subscriptOrSlice
	return p
}

func InitEmptySubscriptOrSliceContext(p *SubscriptOrSliceContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_subscriptOrSlice
}

func (*SubscriptOrSliceContext) IsSubscriptOrSliceContext() {}

func NewSubscriptOrSliceContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *SubscriptOrSliceContext {
	var p = new(SubscriptOrSliceContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_subscriptOrSlice

	return p
}

func (s *SubscriptOrSliceContext) GetParser() antlr.Parser { return s.parser }

func (s *SubscriptOrSliceContext) CopyAll(ctx *SubscriptOrSliceContext) {
	s.CopyFrom(&ctx.BaseParserRuleContext)
}

func (s *SubscriptOrSliceContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SubscriptOrSliceContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

type SubscriptIndexContext struct {
	SubscriptOrSliceContext
}

func NewSubscriptIndexContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *SubscriptIndexContext {
	var p = new(SubscriptIndexContext)

	InitEmptySubscriptOrSliceContext(&p.SubscriptOrSliceContext)
	p.parser = parser
	p.CopyAll(ctx.(*SubscriptOrSliceContext))

	return p
}

func (s *SubscriptIndexContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SubscriptIndexContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *SubscriptIndexContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSubscriptIndex(s)

	default:
		return t.VisitChildren(s)
	}
}

type SliceToContext struct {
	SubscriptOrSliceContext
}

func NewSliceToContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *SliceToContext {
	var p = new(SliceToContext)

	InitEmptySubscriptOrSliceContext(&p.SubscriptOrSliceContext)
	p.parser = parser
	p.CopyAll(ctx.(*SubscriptOrSliceContext))

	return p
}

func (s *SliceToContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SliceToContext) RANGE() antlr.TerminalNode {
	return s.GetToken(CypherParserRANGE, 0)
}

func (s *SliceToContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *SliceToContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSliceTo(s)

	default:
		return t.VisitChildren(s)
	}
}

type SliceFromToContext struct {
	SubscriptOrSliceContext
}

func NewSliceFromToContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *SliceFromToContext {
	var p = new(SliceFromToContext)

	InitEmptySubscriptOrSliceContext(&p.SubscriptOrSliceContext)
	p.parser = parser
	p.CopyAll(ctx.(*SubscriptOrSliceContext))

	return p
}

func (s *SliceFromToContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SliceFromToContext) AllExpression() []IExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IExpressionContext); ok {
			len++
		}
	}

	tst := make([]IExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IExpressionContext); ok {
			tst[i] = t.(IExpressionContext)
			i++
		}
	}

	return tst
}

func (s *SliceFromToContext) Expression(i int) IExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *SliceFromToContext) RANGE() antlr.TerminalNode {
	return s.GetToken(CypherParserRANGE, 0)
}

func (s *SliceFromToContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSliceFromTo(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) SubscriptOrSlice() (localctx ISubscriptOrSliceContext) {
	localctx = NewSubscriptOrSliceContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 104, CypherParserRULE_subscriptOrSlice)
	var _la int

	p.SetState(581)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 62, p.GetParserRuleContext()) {
	case 1:
		localctx = NewSliceFromToContext(p, localctx)
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(573)
			p.Expression()
		}
		{
			p.SetState(574)
			p.Match(CypherParserRANGE)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		p.SetState(576)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		if ((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&-132472832) != 0) || ((int64((_la-64)) & ^0x3f) == 0 && ((int64(1)<<(_la-64))&4294967295) != 0) {
			{
				p.SetState(575)
				p.Expression()
			}

		}

	case 2:
		localctx = NewSliceToContext(p, localctx)
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(578)
			p.Match(CypherParserRANGE)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(579)
			p.Expression()
		}

	case 3:
		localctx = NewSubscriptIndexContext(p, localctx)
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(580)
			p.Expression()
		}

	case antlr.ATNInvalidAltNumber:
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IAtomExpressionContext is an interface to support dynamic dispatch.
type IAtomExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser
	// IsAtomExpressionContext differentiates from other interfaces.
	IsAtomExpressionContext()
}

type AtomExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyAtomExpressionContext() *AtomExpressionContext {
	var p = new(AtomExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_atomExpression
	return p
}

func InitEmptyAtomExpressionContext(p *AtomExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_atomExpression
}

func (*AtomExpressionContext) IsAtomExpressionContext() {}

func NewAtomExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *AtomExpressionContext {
	var p = new(AtomExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_atomExpression

	return p
}

func (s *AtomExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *AtomExpressionContext) CopyAll(ctx *AtomExpressionContext) {
	s.CopyFrom(&ctx.BaseParserRuleContext)
}

func (s *AtomExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *AtomExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

type ExistsAtomContext struct {
	AtomExpressionContext
}

func NewExistsAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *ExistsAtomContext {
	var p = new(ExistsAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *ExistsAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ExistsAtomContext) EXISTS() antlr.TerminalNode {
	return s.GetToken(CypherParserEXISTS, 0)
}

func (s *ExistsAtomContext) LPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserLPAREN, 0)
}

func (s *ExistsAtomContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *ExistsAtomContext) RPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserRPAREN, 0)
}

func (s *ExistsAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitExistsAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type ParameterAtomContext struct {
	AtomExpressionContext
}

func NewParameterAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *ParameterAtomContext {
	var p = new(ParameterAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *ParameterAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ParameterAtomContext) Parameter() IParameterContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IParameterContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IParameterContext)
}

func (s *ParameterAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitParameterAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type VariableAtomContext struct {
	AtomExpressionContext
}

func NewVariableAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *VariableAtomContext {
	var p = new(VariableAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *VariableAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *VariableAtomContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *VariableAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitVariableAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type FunctionAtomContext struct {
	AtomExpressionContext
}

func NewFunctionAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *FunctionAtomContext {
	var p = new(FunctionAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *FunctionAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *FunctionAtomContext) FunctionInvocation() IFunctionInvocationContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IFunctionInvocationContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IFunctionInvocationContext)
}

func (s *FunctionAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitFunctionAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type CaseAtomContext struct {
	AtomExpressionContext
}

func NewCaseAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *CaseAtomContext {
	var p = new(CaseAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *CaseAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *CaseAtomContext) CaseExpression() ICaseExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ICaseExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ICaseExpressionContext)
}

func (s *CaseAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitCaseAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type PredicateFunctionAtomContext struct {
	AtomExpressionContext
}

func NewPredicateFunctionAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *PredicateFunctionAtomContext {
	var p = new(PredicateFunctionAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *PredicateFunctionAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PredicateFunctionAtomContext) PredicateFunction() IPredicateFunctionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPredicateFunctionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPredicateFunctionContext)
}

func (s *PredicateFunctionAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPredicateFunctionAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type CountStarAtomContext struct {
	AtomExpressionContext
}

func NewCountStarAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *CountStarAtomContext {
	var p = new(CountStarAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *CountStarAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *CountStarAtomContext) COUNT() antlr.TerminalNode {
	return s.GetToken(CypherParserCOUNT, 0)
}

func (s *CountStarAtomContext) LPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserLPAREN, 0)
}

func (s *CountStarAtomContext) MULT() antlr.TerminalNode {
	return s.GetToken(CypherParserMULT, 0)
}

func (s *CountStarAtomContext) RPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserRPAREN, 0)
}

func (s *CountStarAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitCountStarAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type ParenAtomContext struct {
	AtomExpressionContext
}

func NewParenAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *ParenAtomContext {
	var p = new(ParenAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *ParenAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ParenAtomContext) LPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserLPAREN, 0)
}

func (s *ParenAtomContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *ParenAtomContext) RPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserRPAREN, 0)
}

func (s *ParenAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitParenAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type DistinctAtomContext struct {
	AtomExpressionContext
}

func NewDistinctAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *DistinctAtomContext {
	var p = new(DistinctAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *DistinctAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *DistinctAtomContext) DISTINCT() antlr.TerminalNode {
	return s.GetToken(CypherParserDISTINCT, 0)
}

func (s *DistinctAtomContext) UnaryExpression() IUnaryExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IUnaryExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IUnaryExpressionContext)
}

func (s *DistinctAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitDistinctAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type ListComprehensionAtomContext struct {
	AtomExpressionContext
}

func NewListComprehensionAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *ListComprehensionAtomContext {
	var p = new(ListComprehensionAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *ListComprehensionAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ListComprehensionAtomContext) ListComprehension() IListComprehensionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IListComprehensionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IListComprehensionContext)
}

func (s *ListComprehensionAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitListComprehensionAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

type LiteralAtomContext struct {
	AtomExpressionContext
}

func NewLiteralAtomContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *LiteralAtomContext {
	var p = new(LiteralAtomContext)

	InitEmptyAtomExpressionContext(&p.AtomExpressionContext)
	p.parser = parser
	p.CopyAll(ctx.(*AtomExpressionContext))

	return p
}

func (s *LiteralAtomContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *LiteralAtomContext) Literal() ILiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ILiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ILiteralContext)
}

func (s *LiteralAtomContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitLiteralAtom(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) AtomExpression() (localctx IAtomExpressionContext) {
	localctx = NewAtomExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 106, CypherParserRULE_atomExpression)
	p.SetState(605)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 63, p.GetParserRuleContext()) {
	case 1:
		localctx = NewParameterAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(583)
			p.Parameter()
		}

	case 2:
		localctx = NewCaseAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(584)
			p.CaseExpression()
		}

	case 3:
		localctx = NewCountStarAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(585)
			p.Match(CypherParserCOUNT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(586)
			p.Match(CypherParserLPAREN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(587)
			p.Match(CypherParserMULT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(588)
			p.Match(CypherParserRPAREN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case 4:
		localctx = NewListComprehensionAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 4)
		{
			p.SetState(589)
			p.ListComprehension()
		}

	case 5:
		localctx = NewPredicateFunctionAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 5)
		{
			p.SetState(590)
			p.PredicateFunction()
		}

	case 6:
		localctx = NewExistsAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 6)
		{
			p.SetState(591)
			p.Match(CypherParserEXISTS)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(592)
			p.Match(CypherParserLPAREN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(593)
			p.Expression()
		}
		{
			p.SetState(594)
			p.Match(CypherParserRPAREN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case 7:
		localctx = NewFunctionAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 7)
		{
			p.SetState(596)
			p.FunctionInvocation()
		}

	case 8:
		localctx = NewParenAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 8)
		{
			p.SetState(597)
			p.Match(CypherParserLPAREN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(598)
			p.Expression()
		}
		{
			p.SetState(599)
			p.Match(CypherParserRPAREN)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case 9:
		localctx = NewDistinctAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 9)
		{
			p.SetState(601)
			p.Match(CypherParserDISTINCT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(602)
			p.UnaryExpression()
		}

	case 10:
		localctx = NewLiteralAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 10)
		{
			p.SetState(603)
			p.Literal()
		}

	case 11:
		localctx = NewVariableAtomContext(p, localctx)
		p.EnterOuterAlt(localctx, 11)
		{
			p.SetState(604)
			p.Variable()
		}

	case antlr.ATNInvalidAltNumber:
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IFunctionInvocationContext is an interface to support dynamic dispatch.
type IFunctionInvocationContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	FunctionName() IFunctionNameContext
	LPAREN() antlr.TerminalNode
	RPAREN() antlr.TerminalNode
	DISTINCT() antlr.TerminalNode
	ExpressionList() IExpressionListContext

	// IsFunctionInvocationContext differentiates from other interfaces.
	IsFunctionInvocationContext()
}

type FunctionInvocationContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyFunctionInvocationContext() *FunctionInvocationContext {
	var p = new(FunctionInvocationContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_functionInvocation
	return p
}

func InitEmptyFunctionInvocationContext(p *FunctionInvocationContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_functionInvocation
}

func (*FunctionInvocationContext) IsFunctionInvocationContext() {}

func NewFunctionInvocationContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *FunctionInvocationContext {
	var p = new(FunctionInvocationContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_functionInvocation

	return p
}

func (s *FunctionInvocationContext) GetParser() antlr.Parser { return s.parser }

func (s *FunctionInvocationContext) FunctionName() IFunctionNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IFunctionNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IFunctionNameContext)
}

func (s *FunctionInvocationContext) LPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserLPAREN, 0)
}

func (s *FunctionInvocationContext) RPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserRPAREN, 0)
}

func (s *FunctionInvocationContext) DISTINCT() antlr.TerminalNode {
	return s.GetToken(CypherParserDISTINCT, 0)
}

func (s *FunctionInvocationContext) ExpressionList() IExpressionListContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionListContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionListContext)
}

func (s *FunctionInvocationContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *FunctionInvocationContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *FunctionInvocationContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitFunctionInvocation(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) FunctionInvocation() (localctx IFunctionInvocationContext) {
	localctx = NewFunctionInvocationContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 108, CypherParserRULE_functionInvocation)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(607)
		p.FunctionName()
	}
	{
		p.SetState(608)
		p.Match(CypherParserLPAREN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(610)
	p.GetErrorHandler().Sync(p)

	if p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 64, p.GetParserRuleContext()) == 1 {
		{
			p.SetState(609)
			p.Match(CypherParserDISTINCT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	} else if p.HasError() { // JIM
		goto errorExit
	}
	p.SetState(613)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if ((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&-132472832) != 0) || ((int64((_la-64)) & ^0x3f) == 0 && ((int64(1)<<(_la-64))&4294967295) != 0) {
		{
			p.SetState(612)
			p.ExpressionList()
		}

	}
	{
		p.SetState(615)
		p.Match(CypherParserRPAREN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IFunctionNameContext is an interface to support dynamic dispatch.
type IFunctionNameContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllSymbolicName() []ISymbolicNameContext
	SymbolicName(i int) ISymbolicNameContext
	AllDOT() []antlr.TerminalNode
	DOT(i int) antlr.TerminalNode

	// IsFunctionNameContext differentiates from other interfaces.
	IsFunctionNameContext()
}

type FunctionNameContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyFunctionNameContext() *FunctionNameContext {
	var p = new(FunctionNameContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_functionName
	return p
}

func InitEmptyFunctionNameContext(p *FunctionNameContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_functionName
}

func (*FunctionNameContext) IsFunctionNameContext() {}

func NewFunctionNameContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *FunctionNameContext {
	var p = new(FunctionNameContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_functionName

	return p
}

func (s *FunctionNameContext) GetParser() antlr.Parser { return s.parser }

func (s *FunctionNameContext) AllSymbolicName() []ISymbolicNameContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(ISymbolicNameContext); ok {
			len++
		}
	}

	tst := make([]ISymbolicNameContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(ISymbolicNameContext); ok {
			tst[i] = t.(ISymbolicNameContext)
			i++
		}
	}

	return tst
}

func (s *FunctionNameContext) SymbolicName(i int) ISymbolicNameContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISymbolicNameContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISymbolicNameContext)
}

func (s *FunctionNameContext) AllDOT() []antlr.TerminalNode {
	return s.GetTokens(CypherParserDOT)
}

func (s *FunctionNameContext) DOT(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserDOT, i)
}

func (s *FunctionNameContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *FunctionNameContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *FunctionNameContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitFunctionName(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) FunctionName() (localctx IFunctionNameContext) {
	localctx = NewFunctionNameContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 110, CypherParserRULE_functionName)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(617)
		p.SymbolicName()
	}
	p.SetState(622)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserDOT {
		{
			p.SetState(618)
			p.Match(CypherParserDOT)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(619)
			p.SymbolicName()
		}

		p.SetState(624)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ICaseExpressionContext is an interface to support dynamic dispatch.
type ICaseExpressionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	CASE() antlr.TerminalNode
	END() antlr.TerminalNode
	Expression() IExpressionContext
	AllCaseWhen() []ICaseWhenContext
	CaseWhen(i int) ICaseWhenContext
	CaseElse() ICaseElseContext

	// IsCaseExpressionContext differentiates from other interfaces.
	IsCaseExpressionContext()
}

type CaseExpressionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyCaseExpressionContext() *CaseExpressionContext {
	var p = new(CaseExpressionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_caseExpression
	return p
}

func InitEmptyCaseExpressionContext(p *CaseExpressionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_caseExpression
}

func (*CaseExpressionContext) IsCaseExpressionContext() {}

func NewCaseExpressionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *CaseExpressionContext {
	var p = new(CaseExpressionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_caseExpression

	return p
}

func (s *CaseExpressionContext) GetParser() antlr.Parser { return s.parser }

func (s *CaseExpressionContext) CASE() antlr.TerminalNode {
	return s.GetToken(CypherParserCASE, 0)
}

func (s *CaseExpressionContext) END() antlr.TerminalNode {
	return s.GetToken(CypherParserEND, 0)
}

func (s *CaseExpressionContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *CaseExpressionContext) AllCaseWhen() []ICaseWhenContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(ICaseWhenContext); ok {
			len++
		}
	}

	tst := make([]ICaseWhenContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(ICaseWhenContext); ok {
			tst[i] = t.(ICaseWhenContext)
			i++
		}
	}

	return tst
}

func (s *CaseExpressionContext) CaseWhen(i int) ICaseWhenContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ICaseWhenContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(ICaseWhenContext)
}

func (s *CaseExpressionContext) CaseElse() ICaseElseContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ICaseElseContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ICaseElseContext)
}

func (s *CaseExpressionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *CaseExpressionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *CaseExpressionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitCaseExpression(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) CaseExpression() (localctx ICaseExpressionContext) {
	localctx = NewCaseExpressionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 112, CypherParserRULE_caseExpression)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(625)
		p.Match(CypherParserCASE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(627)
	p.GetErrorHandler().Sync(p)

	if p.GetInterpreter().AdaptivePredict(p.BaseParser, p.GetTokenStream(), 67, p.GetParserRuleContext()) == 1 {
		{
			p.SetState(626)
			p.Expression()
		}

	} else if p.HasError() { // JIM
		goto errorExit
	}
	p.SetState(630)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for ok := true; ok; ok = _la == CypherParserWHEN {
		{
			p.SetState(629)
			p.CaseWhen()
		}

		p.SetState(632)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}
	p.SetState(635)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserELSE {
		{
			p.SetState(634)
			p.CaseElse()
		}

	}
	{
		p.SetState(637)
		p.Match(CypherParserEND)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ICaseWhenContext is an interface to support dynamic dispatch.
type ICaseWhenContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	WHEN() antlr.TerminalNode
	AllExpression() []IExpressionContext
	Expression(i int) IExpressionContext
	THEN() antlr.TerminalNode

	// IsCaseWhenContext differentiates from other interfaces.
	IsCaseWhenContext()
}

type CaseWhenContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyCaseWhenContext() *CaseWhenContext {
	var p = new(CaseWhenContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_caseWhen
	return p
}

func InitEmptyCaseWhenContext(p *CaseWhenContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_caseWhen
}

func (*CaseWhenContext) IsCaseWhenContext() {}

func NewCaseWhenContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *CaseWhenContext {
	var p = new(CaseWhenContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_caseWhen

	return p
}

func (s *CaseWhenContext) GetParser() antlr.Parser { return s.parser }

func (s *CaseWhenContext) WHEN() antlr.TerminalNode {
	return s.GetToken(CypherParserWHEN, 0)
}

func (s *CaseWhenContext) AllExpression() []IExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IExpressionContext); ok {
			len++
		}
	}

	tst := make([]IExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IExpressionContext); ok {
			tst[i] = t.(IExpressionContext)
			i++
		}
	}

	return tst
}

func (s *CaseWhenContext) Expression(i int) IExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *CaseWhenContext) THEN() antlr.TerminalNode {
	return s.GetToken(CypherParserTHEN, 0)
}

func (s *CaseWhenContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *CaseWhenContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *CaseWhenContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitCaseWhen(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) CaseWhen() (localctx ICaseWhenContext) {
	localctx = NewCaseWhenContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 114, CypherParserRULE_caseWhen)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(639)
		p.Match(CypherParserWHEN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(640)
		p.Expression()
	}
	{
		p.SetState(641)
		p.Match(CypherParserTHEN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(642)
		p.Expression()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ICaseElseContext is an interface to support dynamic dispatch.
type ICaseElseContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	ELSE() antlr.TerminalNode
	Expression() IExpressionContext

	// IsCaseElseContext differentiates from other interfaces.
	IsCaseElseContext()
}

type CaseElseContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyCaseElseContext() *CaseElseContext {
	var p = new(CaseElseContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_caseElse
	return p
}

func InitEmptyCaseElseContext(p *CaseElseContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_caseElse
}

func (*CaseElseContext) IsCaseElseContext() {}

func NewCaseElseContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *CaseElseContext {
	var p = new(CaseElseContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_caseElse

	return p
}

func (s *CaseElseContext) GetParser() antlr.Parser { return s.parser }

func (s *CaseElseContext) ELSE() antlr.TerminalNode {
	return s.GetToken(CypherParserELSE, 0)
}

func (s *CaseElseContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *CaseElseContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *CaseElseContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *CaseElseContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitCaseElse(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) CaseElse() (localctx ICaseElseContext) {
	localctx = NewCaseElseContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 116, CypherParserRULE_caseElse)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(644)
		p.Match(CypherParserELSE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(645)
		p.Expression()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IListComprehensionContext is an interface to support dynamic dispatch.
type IListComprehensionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LBRACK() antlr.TerminalNode
	Variable() IVariableContext
	IN() antlr.TerminalNode
	AllExpression() []IExpressionContext
	Expression(i int) IExpressionContext
	RBRACK() antlr.TerminalNode
	WHERE() antlr.TerminalNode
	STICK() antlr.TerminalNode

	// IsListComprehensionContext differentiates from other interfaces.
	IsListComprehensionContext()
}

type ListComprehensionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyListComprehensionContext() *ListComprehensionContext {
	var p = new(ListComprehensionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_listComprehension
	return p
}

func InitEmptyListComprehensionContext(p *ListComprehensionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_listComprehension
}

func (*ListComprehensionContext) IsListComprehensionContext() {}

func NewListComprehensionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ListComprehensionContext {
	var p = new(ListComprehensionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_listComprehension

	return p
}

func (s *ListComprehensionContext) GetParser() antlr.Parser { return s.parser }

func (s *ListComprehensionContext) LBRACK() antlr.TerminalNode {
	return s.GetToken(CypherParserLBRACK, 0)
}

func (s *ListComprehensionContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *ListComprehensionContext) IN() antlr.TerminalNode {
	return s.GetToken(CypherParserIN, 0)
}

func (s *ListComprehensionContext) AllExpression() []IExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IExpressionContext); ok {
			len++
		}
	}

	tst := make([]IExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IExpressionContext); ok {
			tst[i] = t.(IExpressionContext)
			i++
		}
	}

	return tst
}

func (s *ListComprehensionContext) Expression(i int) IExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *ListComprehensionContext) RBRACK() antlr.TerminalNode {
	return s.GetToken(CypherParserRBRACK, 0)
}

func (s *ListComprehensionContext) WHERE() antlr.TerminalNode {
	return s.GetToken(CypherParserWHERE, 0)
}

func (s *ListComprehensionContext) STICK() antlr.TerminalNode {
	return s.GetToken(CypherParserSTICK, 0)
}

func (s *ListComprehensionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ListComprehensionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ListComprehensionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitListComprehension(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) ListComprehension() (localctx IListComprehensionContext) {
	localctx = NewListComprehensionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 118, CypherParserRULE_listComprehension)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(647)
		p.Match(CypherParserLBRACK)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(648)
		p.Variable()
	}
	{
		p.SetState(649)
		p.Match(CypherParserIN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(650)
		p.Expression()
	}
	p.SetState(653)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserWHERE {
		{
			p.SetState(651)
			p.Match(CypherParserWHERE)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(652)
			p.Expression()
		}

	}
	p.SetState(657)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserSTICK {
		{
			p.SetState(655)
			p.Match(CypherParserSTICK)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(656)
			p.Expression()
		}

	}
	{
		p.SetState(659)
		p.Match(CypherParserRBRACK)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPredicateFunctionContext is an interface to support dynamic dispatch.
type IPredicateFunctionContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LPAREN() antlr.TerminalNode
	Variable() IVariableContext
	IN() antlr.TerminalNode
	AllExpression() []IExpressionContext
	Expression(i int) IExpressionContext
	RPAREN() antlr.TerminalNode
	ANY() antlr.TerminalNode
	ALL() antlr.TerminalNode
	NONE() antlr.TerminalNode
	SINGLE() antlr.TerminalNode
	WHERE() antlr.TerminalNode

	// IsPredicateFunctionContext differentiates from other interfaces.
	IsPredicateFunctionContext()
}

type PredicateFunctionContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPredicateFunctionContext() *PredicateFunctionContext {
	var p = new(PredicateFunctionContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_predicateFunction
	return p
}

func InitEmptyPredicateFunctionContext(p *PredicateFunctionContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_predicateFunction
}

func (*PredicateFunctionContext) IsPredicateFunctionContext() {}

func NewPredicateFunctionContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PredicateFunctionContext {
	var p = new(PredicateFunctionContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_predicateFunction

	return p
}

func (s *PredicateFunctionContext) GetParser() antlr.Parser { return s.parser }

func (s *PredicateFunctionContext) LPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserLPAREN, 0)
}

func (s *PredicateFunctionContext) Variable() IVariableContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IVariableContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IVariableContext)
}

func (s *PredicateFunctionContext) IN() antlr.TerminalNode {
	return s.GetToken(CypherParserIN, 0)
}

func (s *PredicateFunctionContext) AllExpression() []IExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IExpressionContext); ok {
			len++
		}
	}

	tst := make([]IExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IExpressionContext); ok {
			tst[i] = t.(IExpressionContext)
			i++
		}
	}

	return tst
}

func (s *PredicateFunctionContext) Expression(i int) IExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *PredicateFunctionContext) RPAREN() antlr.TerminalNode {
	return s.GetToken(CypherParserRPAREN, 0)
}

func (s *PredicateFunctionContext) ANY() antlr.TerminalNode {
	return s.GetToken(CypherParserANY, 0)
}

func (s *PredicateFunctionContext) ALL() antlr.TerminalNode {
	return s.GetToken(CypherParserALL, 0)
}

func (s *PredicateFunctionContext) NONE() antlr.TerminalNode {
	return s.GetToken(CypherParserNONE, 0)
}

func (s *PredicateFunctionContext) SINGLE() antlr.TerminalNode {
	return s.GetToken(CypherParserSINGLE, 0)
}

func (s *PredicateFunctionContext) WHERE() antlr.TerminalNode {
	return s.GetToken(CypherParserWHERE, 0)
}

func (s *PredicateFunctionContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PredicateFunctionContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PredicateFunctionContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPredicateFunction(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) PredicateFunction() (localctx IPredicateFunctionContext) {
	localctx = NewPredicateFunctionContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 120, CypherParserRULE_predicateFunction)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(661)
		_la = p.GetTokenStream().LA(1)

		if !((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&128849018880) != 0) {
			p.GetErrorHandler().RecoverInline(p)
		} else {
			p.GetErrorHandler().ReportMatch(p)
			p.Consume()
		}
	}
	{
		p.SetState(662)
		p.Match(CypherParserLPAREN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(663)
		p.Variable()
	}
	{
		p.SetState(664)
		p.Match(CypherParserIN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(665)
		p.Expression()
	}
	p.SetState(668)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if _la == CypherParserWHERE {
		{
			p.SetState(666)
			p.Match(CypherParserWHERE)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(667)
			p.Expression()
		}

	}
	{
		p.SetState(670)
		p.Match(CypherParserRPAREN)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IParameterContext is an interface to support dynamic dispatch.
type IParameterContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	DOLLAR() antlr.TerminalNode
	SymbolicName() ISymbolicNameContext

	// IsParameterContext differentiates from other interfaces.
	IsParameterContext()
}

type ParameterContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyParameterContext() *ParameterContext {
	var p = new(ParameterContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_parameter
	return p
}

func InitEmptyParameterContext(p *ParameterContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_parameter
}

func (*ParameterContext) IsParameterContext() {}

func NewParameterContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ParameterContext {
	var p = new(ParameterContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_parameter

	return p
}

func (s *ParameterContext) GetParser() antlr.Parser { return s.parser }

func (s *ParameterContext) DOLLAR() antlr.TerminalNode {
	return s.GetToken(CypherParserDOLLAR, 0)
}

func (s *ParameterContext) SymbolicName() ISymbolicNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISymbolicNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISymbolicNameContext)
}

func (s *ParameterContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ParameterContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ParameterContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitParameter(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Parameter() (localctx IParameterContext) {
	localctx = NewParameterContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 122, CypherParserRULE_parameter)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(672)
		p.Match(CypherParserDOLLAR)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(673)
		p.SymbolicName()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ILiteralContext is an interface to support dynamic dispatch.
type ILiteralContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser
	// IsLiteralContext differentiates from other interfaces.
	IsLiteralContext()
}

type LiteralContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyLiteralContext() *LiteralContext {
	var p = new(LiteralContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_literal
	return p
}

func InitEmptyLiteralContext(p *LiteralContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_literal
}

func (*LiteralContext) IsLiteralContext() {}

func NewLiteralContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *LiteralContext {
	var p = new(LiteralContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_literal

	return p
}

func (s *LiteralContext) GetParser() antlr.Parser { return s.parser }

func (s *LiteralContext) CopyAll(ctx *LiteralContext) {
	s.CopyFrom(&ctx.BaseParserRuleContext)
}

func (s *LiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *LiteralContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

type FloatLitContext struct {
	LiteralContext
}

func NewFloatLitContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *FloatLitContext {
	var p = new(FloatLitContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *FloatLitContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *FloatLitContext) FloatLiteral() IFloatLiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IFloatLiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IFloatLiteralContext)
}

func (s *FloatLitContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitFloatLit(s)

	default:
		return t.VisitChildren(s)
	}
}

type TrueLiteralContext struct {
	LiteralContext
}

func NewTrueLiteralContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *TrueLiteralContext {
	var p = new(TrueLiteralContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *TrueLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *TrueLiteralContext) TRUE() antlr.TerminalNode {
	return s.GetToken(CypherParserTRUE, 0)
}

func (s *TrueLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitTrueLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

type ListLitContext struct {
	LiteralContext
}

func NewListLitContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *ListLitContext {
	var p = new(ListLitContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *ListLitContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ListLitContext) ListLiteral() IListLiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IListLiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IListLiteralContext)
}

func (s *ListLitContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitListLit(s)

	default:
		return t.VisitChildren(s)
	}
}

type IntLiteralContext struct {
	LiteralContext
}

func NewIntLiteralContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *IntLiteralContext {
	var p = new(IntLiteralContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *IntLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *IntLiteralContext) IntegerLiteral() IIntegerLiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IIntegerLiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IIntegerLiteralContext)
}

func (s *IntLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitIntLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

type MapLitContext struct {
	LiteralContext
}

func NewMapLitContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *MapLitContext {
	var p = new(MapLitContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *MapLitContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *MapLitContext) MapLiteral() IMapLiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IMapLiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IMapLiteralContext)
}

func (s *MapLitContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitMapLit(s)

	default:
		return t.VisitChildren(s)
	}
}

type NullLiteralContext struct {
	LiteralContext
}

func NewNullLiteralContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *NullLiteralContext {
	var p = new(NullLiteralContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *NullLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *NullLiteralContext) NULL_W() antlr.TerminalNode {
	return s.GetToken(CypherParserNULL_W, 0)
}

func (s *NullLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitNullLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

type FalseLiteralContext struct {
	LiteralContext
}

func NewFalseLiteralContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *FalseLiteralContext {
	var p = new(FalseLiteralContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *FalseLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *FalseLiteralContext) FALSE() antlr.TerminalNode {
	return s.GetToken(CypherParserFALSE, 0)
}

func (s *FalseLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitFalseLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

type StringLitContext struct {
	LiteralContext
}

func NewStringLitContext(parser antlr.Parser, ctx antlr.ParserRuleContext) *StringLitContext {
	var p = new(StringLitContext)

	InitEmptyLiteralContext(&p.LiteralContext)
	p.parser = parser
	p.CopyAll(ctx.(*LiteralContext))

	return p
}

func (s *StringLitContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *StringLitContext) StringLiteral() IStringLiteralContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IStringLiteralContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IStringLiteralContext)
}

func (s *StringLitContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitStringLit(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Literal() (localctx ILiteralContext) {
	localctx = NewLiteralContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 124, CypherParserRULE_literal)
	p.SetState(683)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}

	switch p.GetTokenStream().LA(1) {
	case CypherParserTRUE:
		localctx = NewTrueLiteralContext(p, localctx)
		p.EnterOuterAlt(localctx, 1)
		{
			p.SetState(675)
			p.Match(CypherParserTRUE)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case CypherParserFALSE:
		localctx = NewFalseLiteralContext(p, localctx)
		p.EnterOuterAlt(localctx, 2)
		{
			p.SetState(676)
			p.Match(CypherParserFALSE)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case CypherParserNULL_W:
		localctx = NewNullLiteralContext(p, localctx)
		p.EnterOuterAlt(localctx, 3)
		{
			p.SetState(677)
			p.Match(CypherParserNULL_W)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}

	case CypherParserHEX_INTEGER, CypherParserOCTAL_INTEGER, CypherParserINTEGER_LITERAL:
		localctx = NewIntLiteralContext(p, localctx)
		p.EnterOuterAlt(localctx, 4)
		{
			p.SetState(678)
			p.IntegerLiteral()
		}

	case CypherParserFLOAT_LITERAL:
		localctx = NewFloatLitContext(p, localctx)
		p.EnterOuterAlt(localctx, 5)
		{
			p.SetState(679)
			p.FloatLiteral()
		}

	case CypherParserSTRING_LITERAL:
		localctx = NewStringLitContext(p, localctx)
		p.EnterOuterAlt(localctx, 6)
		{
			p.SetState(680)
			p.StringLiteral()
		}

	case CypherParserLBRACK:
		localctx = NewListLitContext(p, localctx)
		p.EnterOuterAlt(localctx, 7)
		{
			p.SetState(681)
			p.ListLiteral()
		}

	case CypherParserLBRACE:
		localctx = NewMapLitContext(p, localctx)
		p.EnterOuterAlt(localctx, 8)
		{
			p.SetState(682)
			p.MapLiteral()
		}

	default:
		p.SetError(antlr.NewNoViableAltException(p, nil, nil, nil, nil, nil))
		goto errorExit
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IIntegerLiteralContext is an interface to support dynamic dispatch.
type IIntegerLiteralContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	INTEGER_LITERAL() antlr.TerminalNode
	HEX_INTEGER() antlr.TerminalNode
	OCTAL_INTEGER() antlr.TerminalNode

	// IsIntegerLiteralContext differentiates from other interfaces.
	IsIntegerLiteralContext()
}

type IntegerLiteralContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyIntegerLiteralContext() *IntegerLiteralContext {
	var p = new(IntegerLiteralContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_integerLiteral
	return p
}

func InitEmptyIntegerLiteralContext(p *IntegerLiteralContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_integerLiteral
}

func (*IntegerLiteralContext) IsIntegerLiteralContext() {}

func NewIntegerLiteralContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *IntegerLiteralContext {
	var p = new(IntegerLiteralContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_integerLiteral

	return p
}

func (s *IntegerLiteralContext) GetParser() antlr.Parser { return s.parser }

func (s *IntegerLiteralContext) INTEGER_LITERAL() antlr.TerminalNode {
	return s.GetToken(CypherParserINTEGER_LITERAL, 0)
}

func (s *IntegerLiteralContext) HEX_INTEGER() antlr.TerminalNode {
	return s.GetToken(CypherParserHEX_INTEGER, 0)
}

func (s *IntegerLiteralContext) OCTAL_INTEGER() antlr.TerminalNode {
	return s.GetToken(CypherParserOCTAL_INTEGER, 0)
}

func (s *IntegerLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *IntegerLiteralContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *IntegerLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitIntegerLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) IntegerLiteral() (localctx IIntegerLiteralContext) {
	localctx = NewIntegerLiteralContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 126, CypherParserRULE_integerLiteral)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(685)
		_la = p.GetTokenStream().LA(1)

		if !((int64((_la-92)) & ^0x3f) == 0 && ((int64(1)<<(_la-92))&11) != 0) {
			p.GetErrorHandler().RecoverInline(p)
		} else {
			p.GetErrorHandler().ReportMatch(p)
			p.Consume()
		}
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IFloatLiteralContext is an interface to support dynamic dispatch.
type IFloatLiteralContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	FLOAT_LITERAL() antlr.TerminalNode

	// IsFloatLiteralContext differentiates from other interfaces.
	IsFloatLiteralContext()
}

type FloatLiteralContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyFloatLiteralContext() *FloatLiteralContext {
	var p = new(FloatLiteralContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_floatLiteral
	return p
}

func InitEmptyFloatLiteralContext(p *FloatLiteralContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_floatLiteral
}

func (*FloatLiteralContext) IsFloatLiteralContext() {}

func NewFloatLiteralContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *FloatLiteralContext {
	var p = new(FloatLiteralContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_floatLiteral

	return p
}

func (s *FloatLiteralContext) GetParser() antlr.Parser { return s.parser }

func (s *FloatLiteralContext) FLOAT_LITERAL() antlr.TerminalNode {
	return s.GetToken(CypherParserFLOAT_LITERAL, 0)
}

func (s *FloatLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *FloatLiteralContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *FloatLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitFloatLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) FloatLiteral() (localctx IFloatLiteralContext) {
	localctx = NewFloatLiteralContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 128, CypherParserRULE_floatLiteral)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(687)
		p.Match(CypherParserFLOAT_LITERAL)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IStringLiteralContext is an interface to support dynamic dispatch.
type IStringLiteralContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	STRING_LITERAL() antlr.TerminalNode

	// IsStringLiteralContext differentiates from other interfaces.
	IsStringLiteralContext()
}

type StringLiteralContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyStringLiteralContext() *StringLiteralContext {
	var p = new(StringLiteralContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_stringLiteral
	return p
}

func InitEmptyStringLiteralContext(p *StringLiteralContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_stringLiteral
}

func (*StringLiteralContext) IsStringLiteralContext() {}

func NewStringLiteralContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *StringLiteralContext {
	var p = new(StringLiteralContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_stringLiteral

	return p
}

func (s *StringLiteralContext) GetParser() antlr.Parser { return s.parser }

func (s *StringLiteralContext) STRING_LITERAL() antlr.TerminalNode {
	return s.GetToken(CypherParserSTRING_LITERAL, 0)
}

func (s *StringLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *StringLiteralContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *StringLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitStringLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) StringLiteral() (localctx IStringLiteralContext) {
	localctx = NewStringLiteralContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 130, CypherParserRULE_stringLiteral)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(689)
		p.Match(CypherParserSTRING_LITERAL)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IListLiteralContext is an interface to support dynamic dispatch.
type IListLiteralContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LBRACK() antlr.TerminalNode
	RBRACK() antlr.TerminalNode
	ExpressionList() IExpressionListContext

	// IsListLiteralContext differentiates from other interfaces.
	IsListLiteralContext()
}

type ListLiteralContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyListLiteralContext() *ListLiteralContext {
	var p = new(ListLiteralContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_listLiteral
	return p
}

func InitEmptyListLiteralContext(p *ListLiteralContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_listLiteral
}

func (*ListLiteralContext) IsListLiteralContext() {}

func NewListLiteralContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ListLiteralContext {
	var p = new(ListLiteralContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_listLiteral

	return p
}

func (s *ListLiteralContext) GetParser() antlr.Parser { return s.parser }

func (s *ListLiteralContext) LBRACK() antlr.TerminalNode {
	return s.GetToken(CypherParserLBRACK, 0)
}

func (s *ListLiteralContext) RBRACK() antlr.TerminalNode {
	return s.GetToken(CypherParserRBRACK, 0)
}

func (s *ListLiteralContext) ExpressionList() IExpressionListContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionListContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionListContext)
}

func (s *ListLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ListLiteralContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ListLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitListLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) ListLiteral() (localctx IListLiteralContext) {
	localctx = NewListLiteralContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 132, CypherParserRULE_listLiteral)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(691)
		p.Match(CypherParserLBRACK)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(693)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if ((int64(_la) & ^0x3f) == 0 && ((int64(1)<<_la)&-132472832) != 0) || ((int64((_la-64)) & ^0x3f) == 0 && ((int64(1)<<(_la-64))&4294967295) != 0) {
		{
			p.SetState(692)
			p.ExpressionList()
		}

	}
	{
		p.SetState(695)
		p.Match(CypherParserRBRACK)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IMapLiteralContext is an interface to support dynamic dispatch.
type IMapLiteralContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	LBRACE() antlr.TerminalNode
	RBRACE() antlr.TerminalNode
	AllMapPair() []IMapPairContext
	MapPair(i int) IMapPairContext
	AllCOMMA() []antlr.TerminalNode
	COMMA(i int) antlr.TerminalNode

	// IsMapLiteralContext differentiates from other interfaces.
	IsMapLiteralContext()
}

type MapLiteralContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyMapLiteralContext() *MapLiteralContext {
	var p = new(MapLiteralContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_mapLiteral
	return p
}

func InitEmptyMapLiteralContext(p *MapLiteralContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_mapLiteral
}

func (*MapLiteralContext) IsMapLiteralContext() {}

func NewMapLiteralContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *MapLiteralContext {
	var p = new(MapLiteralContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_mapLiteral

	return p
}

func (s *MapLiteralContext) GetParser() antlr.Parser { return s.parser }

func (s *MapLiteralContext) LBRACE() antlr.TerminalNode {
	return s.GetToken(CypherParserLBRACE, 0)
}

func (s *MapLiteralContext) RBRACE() antlr.TerminalNode {
	return s.GetToken(CypherParserRBRACE, 0)
}

func (s *MapLiteralContext) AllMapPair() []IMapPairContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IMapPairContext); ok {
			len++
		}
	}

	tst := make([]IMapPairContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IMapPairContext); ok {
			tst[i] = t.(IMapPairContext)
			i++
		}
	}

	return tst
}

func (s *MapLiteralContext) MapPair(i int) IMapPairContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IMapPairContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IMapPairContext)
}

func (s *MapLiteralContext) AllCOMMA() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOMMA)
}

func (s *MapLiteralContext) COMMA(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOMMA, i)
}

func (s *MapLiteralContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *MapLiteralContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *MapLiteralContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitMapLiteral(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) MapLiteral() (localctx IMapLiteralContext) {
	localctx = NewMapLiteralContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 134, CypherParserRULE_mapLiteral)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(697)
		p.Match(CypherParserLBRACE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	p.SetState(706)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	if (int64((_la-28)) & ^0x3f) == 0 && ((int64(1)<<(_la-28))&9223372036854775807) != 0 {
		{
			p.SetState(698)
			p.MapPair()
		}
		p.SetState(703)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)

		for _la == CypherParserCOMMA {
			{
				p.SetState(699)
				p.Match(CypherParserCOMMA)
				if p.HasError() {
					// Recognition error - abort rule
					goto errorExit
				}
			}
			{
				p.SetState(700)
				p.MapPair()
			}

			p.SetState(705)
			p.GetErrorHandler().Sync(p)
			if p.HasError() {
				goto errorExit
			}
			_la = p.GetTokenStream().LA(1)
		}

	}
	{
		p.SetState(708)
		p.Match(CypherParserRBRACE)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IMapPairContext is an interface to support dynamic dispatch.
type IMapPairContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	PropertyKeyName() IPropertyKeyNameContext
	COLON() antlr.TerminalNode
	Expression() IExpressionContext

	// IsMapPairContext differentiates from other interfaces.
	IsMapPairContext()
}

type MapPairContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyMapPairContext() *MapPairContext {
	var p = new(MapPairContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_mapPair
	return p
}

func InitEmptyMapPairContext(p *MapPairContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_mapPair
}

func (*MapPairContext) IsMapPairContext() {}

func NewMapPairContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *MapPairContext {
	var p = new(MapPairContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_mapPair

	return p
}

func (s *MapPairContext) GetParser() antlr.Parser { return s.parser }

func (s *MapPairContext) PropertyKeyName() IPropertyKeyNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IPropertyKeyNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IPropertyKeyNameContext)
}

func (s *MapPairContext) COLON() antlr.TerminalNode {
	return s.GetToken(CypherParserCOLON, 0)
}

func (s *MapPairContext) Expression() IExpressionContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *MapPairContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *MapPairContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *MapPairContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitMapPair(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) MapPair() (localctx IMapPairContext) {
	localctx = NewMapPairContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 136, CypherParserRULE_mapPair)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(710)
		p.PropertyKeyName()
	}
	{
		p.SetState(711)
		p.Match(CypherParserCOLON)
		if p.HasError() {
			// Recognition error - abort rule
			goto errorExit
		}
	}
	{
		p.SetState(712)
		p.Expression()
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IExpressionListContext is an interface to support dynamic dispatch.
type IExpressionListContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	AllExpression() []IExpressionContext
	Expression(i int) IExpressionContext
	AllCOMMA() []antlr.TerminalNode
	COMMA(i int) antlr.TerminalNode

	// IsExpressionListContext differentiates from other interfaces.
	IsExpressionListContext()
}

type ExpressionListContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyExpressionListContext() *ExpressionListContext {
	var p = new(ExpressionListContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_expressionList
	return p
}

func InitEmptyExpressionListContext(p *ExpressionListContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_expressionList
}

func (*ExpressionListContext) IsExpressionListContext() {}

func NewExpressionListContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *ExpressionListContext {
	var p = new(ExpressionListContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_expressionList

	return p
}

func (s *ExpressionListContext) GetParser() antlr.Parser { return s.parser }

func (s *ExpressionListContext) AllExpression() []IExpressionContext {
	children := s.GetChildren()
	len := 0
	for _, ctx := range children {
		if _, ok := ctx.(IExpressionContext); ok {
			len++
		}
	}

	tst := make([]IExpressionContext, len)
	i := 0
	for _, ctx := range children {
		if t, ok := ctx.(IExpressionContext); ok {
			tst[i] = t.(IExpressionContext)
			i++
		}
	}

	return tst
}

func (s *ExpressionListContext) Expression(i int) IExpressionContext {
	var t antlr.RuleContext
	j := 0
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(IExpressionContext); ok {
			if j == i {
				t = ctx.(antlr.RuleContext)
				break
			}
			j++
		}
	}

	if t == nil {
		return nil
	}

	return t.(IExpressionContext)
}

func (s *ExpressionListContext) AllCOMMA() []antlr.TerminalNode {
	return s.GetTokens(CypherParserCOMMA)
}

func (s *ExpressionListContext) COMMA(i int) antlr.TerminalNode {
	return s.GetToken(CypherParserCOMMA, i)
}

func (s *ExpressionListContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *ExpressionListContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *ExpressionListContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitExpressionList(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) ExpressionList() (localctx IExpressionListContext) {
	localctx = NewExpressionListContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 138, CypherParserRULE_expressionList)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(714)
		p.Expression()
	}
	p.SetState(719)
	p.GetErrorHandler().Sync(p)
	if p.HasError() {
		goto errorExit
	}
	_la = p.GetTokenStream().LA(1)

	for _la == CypherParserCOMMA {
		{
			p.SetState(715)
			p.Match(CypherParserCOMMA)
			if p.HasError() {
				// Recognition error - abort rule
				goto errorExit
			}
		}
		{
			p.SetState(716)
			p.Expression()
		}

		p.SetState(721)
		p.GetErrorHandler().Sync(p)
		if p.HasError() {
			goto errorExit
		}
		_la = p.GetTokenStream().LA(1)
	}

errorExit:
	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IVariableContext is an interface to support dynamic dispatch.
type IVariableContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SymbolicName() ISymbolicNameContext

	// IsVariableContext differentiates from other interfaces.
	IsVariableContext()
}

type VariableContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyVariableContext() *VariableContext {
	var p = new(VariableContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_variable
	return p
}

func InitEmptyVariableContext(p *VariableContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_variable
}

func (*VariableContext) IsVariableContext() {}

func NewVariableContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *VariableContext {
	var p = new(VariableContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_variable

	return p
}

func (s *VariableContext) GetParser() antlr.Parser { return s.parser }

func (s *VariableContext) SymbolicName() ISymbolicNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISymbolicNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISymbolicNameContext)
}

func (s *VariableContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *VariableContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *VariableContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitVariable(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) Variable() (localctx IVariableContext) {
	localctx = NewVariableContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 140, CypherParserRULE_variable)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(722)
		p.SymbolicName()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ILabelNameContext is an interface to support dynamic dispatch.
type ILabelNameContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SymbolicName() ISymbolicNameContext

	// IsLabelNameContext differentiates from other interfaces.
	IsLabelNameContext()
}

type LabelNameContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyLabelNameContext() *LabelNameContext {
	var p = new(LabelNameContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_labelName
	return p
}

func InitEmptyLabelNameContext(p *LabelNameContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_labelName
}

func (*LabelNameContext) IsLabelNameContext() {}

func NewLabelNameContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *LabelNameContext {
	var p = new(LabelNameContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_labelName

	return p
}

func (s *LabelNameContext) GetParser() antlr.Parser { return s.parser }

func (s *LabelNameContext) SymbolicName() ISymbolicNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISymbolicNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISymbolicNameContext)
}

func (s *LabelNameContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *LabelNameContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *LabelNameContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitLabelName(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) LabelName() (localctx ILabelNameContext) {
	localctx = NewLabelNameContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 142, CypherParserRULE_labelName)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(724)
		p.SymbolicName()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IRelTypeNameContext is an interface to support dynamic dispatch.
type IRelTypeNameContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SymbolicName() ISymbolicNameContext

	// IsRelTypeNameContext differentiates from other interfaces.
	IsRelTypeNameContext()
}

type RelTypeNameContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyRelTypeNameContext() *RelTypeNameContext {
	var p = new(RelTypeNameContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relTypeName
	return p
}

func InitEmptyRelTypeNameContext(p *RelTypeNameContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_relTypeName
}

func (*RelTypeNameContext) IsRelTypeNameContext() {}

func NewRelTypeNameContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *RelTypeNameContext {
	var p = new(RelTypeNameContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_relTypeName

	return p
}

func (s *RelTypeNameContext) GetParser() antlr.Parser { return s.parser }

func (s *RelTypeNameContext) SymbolicName() ISymbolicNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISymbolicNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISymbolicNameContext)
}

func (s *RelTypeNameContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *RelTypeNameContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *RelTypeNameContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitRelTypeName(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) RelTypeName() (localctx IRelTypeNameContext) {
	localctx = NewRelTypeNameContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 144, CypherParserRULE_relTypeName)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(726)
		p.SymbolicName()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// IPropertyKeyNameContext is an interface to support dynamic dispatch.
type IPropertyKeyNameContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	SymbolicName() ISymbolicNameContext

	// IsPropertyKeyNameContext differentiates from other interfaces.
	IsPropertyKeyNameContext()
}

type PropertyKeyNameContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptyPropertyKeyNameContext() *PropertyKeyNameContext {
	var p = new(PropertyKeyNameContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_propertyKeyName
	return p
}

func InitEmptyPropertyKeyNameContext(p *PropertyKeyNameContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_propertyKeyName
}

func (*PropertyKeyNameContext) IsPropertyKeyNameContext() {}

func NewPropertyKeyNameContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *PropertyKeyNameContext {
	var p = new(PropertyKeyNameContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_propertyKeyName

	return p
}

func (s *PropertyKeyNameContext) GetParser() antlr.Parser { return s.parser }

func (s *PropertyKeyNameContext) SymbolicName() ISymbolicNameContext {
	var t antlr.RuleContext
	for _, ctx := range s.GetChildren() {
		if _, ok := ctx.(ISymbolicNameContext); ok {
			t = ctx.(antlr.RuleContext)
			break
		}
	}

	if t == nil {
		return nil
	}

	return t.(ISymbolicNameContext)
}

func (s *PropertyKeyNameContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *PropertyKeyNameContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *PropertyKeyNameContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitPropertyKeyName(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) PropertyKeyName() (localctx IPropertyKeyNameContext) {
	localctx = NewPropertyKeyNameContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 146, CypherParserRULE_propertyKeyName)
	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(728)
		p.SymbolicName()
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}

// ISymbolicNameContext is an interface to support dynamic dispatch.
type ISymbolicNameContext interface {
	antlr.ParserRuleContext

	// GetParser returns the parser.
	GetParser() antlr.Parser

	// Getter signatures
	ID() antlr.TerminalNode
	ESC_LITERAL() antlr.TerminalNode
	CALL() antlr.TerminalNode
	YIELD() antlr.TerminalNode
	COUNT() antlr.TerminalNode
	FILTER() antlr.TerminalNode
	EXTRACT() antlr.TerminalNode
	ANY() antlr.TerminalNode
	NONE() antlr.TerminalNode
	SINGLE() antlr.TerminalNode
	ALL() antlr.TerminalNode
	ASC() antlr.TerminalNode
	ASCENDING() antlr.TerminalNode
	BY() antlr.TerminalNode
	CREATE() antlr.TerminalNode
	DELETE() antlr.TerminalNode
	DESC() antlr.TerminalNode
	DESCENDING() antlr.TerminalNode
	DETACH() antlr.TerminalNode
	EXISTS() antlr.TerminalNode
	LIMIT() antlr.TerminalNode
	MATCH() antlr.TerminalNode
	MERGE() antlr.TerminalNode
	ON() antlr.TerminalNode
	OPTIONAL() antlr.TerminalNode
	ORDER() antlr.TerminalNode
	REMOVE() antlr.TerminalNode
	RETURN() antlr.TerminalNode
	SET() antlr.TerminalNode
	SKIP_W() antlr.TerminalNode
	WHERE() antlr.TerminalNode
	WITH() antlr.TerminalNode
	UNION() antlr.TerminalNode
	UNWIND() antlr.TerminalNode
	AND() antlr.TerminalNode
	AS() antlr.TerminalNode
	CONTAINS() antlr.TerminalNode
	DISTINCT() antlr.TerminalNode
	ENDS() antlr.TerminalNode
	IN() antlr.TerminalNode
	IS() antlr.TerminalNode
	NOT() antlr.TerminalNode
	OR() antlr.TerminalNode
	STARTS() antlr.TerminalNode
	XOR() antlr.TerminalNode
	FALSE() antlr.TerminalNode
	TRUE() antlr.TerminalNode
	NULL_W() antlr.TerminalNode
	CONSTRAINT() antlr.TerminalNode
	DO() antlr.TerminalNode
	FOR() antlr.TerminalNode
	REQUIRE() antlr.TerminalNode
	UNIQUE() antlr.TerminalNode
	CASE() antlr.TerminalNode
	WHEN() antlr.TerminalNode
	THEN() antlr.TerminalNode
	ELSE() antlr.TerminalNode
	END() antlr.TerminalNode
	MANDATORY() antlr.TerminalNode
	SCALAR() antlr.TerminalNode
	OF() antlr.TerminalNode
	ADD() antlr.TerminalNode
	DROP() antlr.TerminalNode

	// IsSymbolicNameContext differentiates from other interfaces.
	IsSymbolicNameContext()
}

type SymbolicNameContext struct {
	antlr.BaseParserRuleContext
	parser antlr.Parser
}

func NewEmptySymbolicNameContext() *SymbolicNameContext {
	var p = new(SymbolicNameContext)
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_symbolicName
	return p
}

func InitEmptySymbolicNameContext(p *SymbolicNameContext) {
	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, nil, -1)
	p.RuleIndex = CypherParserRULE_symbolicName
}

func (*SymbolicNameContext) IsSymbolicNameContext() {}

func NewSymbolicNameContext(parser antlr.Parser, parent antlr.ParserRuleContext, invokingState int) *SymbolicNameContext {
	var p = new(SymbolicNameContext)

	antlr.InitBaseParserRuleContext(&p.BaseParserRuleContext, parent, invokingState)

	p.parser = parser
	p.RuleIndex = CypherParserRULE_symbolicName

	return p
}

func (s *SymbolicNameContext) GetParser() antlr.Parser { return s.parser }

func (s *SymbolicNameContext) ID() antlr.TerminalNode {
	return s.GetToken(CypherParserID, 0)
}

func (s *SymbolicNameContext) ESC_LITERAL() antlr.TerminalNode {
	return s.GetToken(CypherParserESC_LITERAL, 0)
}

func (s *SymbolicNameContext) CALL() antlr.TerminalNode {
	return s.GetToken(CypherParserCALL, 0)
}

func (s *SymbolicNameContext) YIELD() antlr.TerminalNode {
	return s.GetToken(CypherParserYIELD, 0)
}

func (s *SymbolicNameContext) COUNT() antlr.TerminalNode {
	return s.GetToken(CypherParserCOUNT, 0)
}

func (s *SymbolicNameContext) FILTER() antlr.TerminalNode {
	return s.GetToken(CypherParserFILTER, 0)
}

func (s *SymbolicNameContext) EXTRACT() antlr.TerminalNode {
	return s.GetToken(CypherParserEXTRACT, 0)
}

func (s *SymbolicNameContext) ANY() antlr.TerminalNode {
	return s.GetToken(CypherParserANY, 0)
}

func (s *SymbolicNameContext) NONE() antlr.TerminalNode {
	return s.GetToken(CypherParserNONE, 0)
}

func (s *SymbolicNameContext) SINGLE() antlr.TerminalNode {
	return s.GetToken(CypherParserSINGLE, 0)
}

func (s *SymbolicNameContext) ALL() antlr.TerminalNode {
	return s.GetToken(CypherParserALL, 0)
}

func (s *SymbolicNameContext) ASC() antlr.TerminalNode {
	return s.GetToken(CypherParserASC, 0)
}

func (s *SymbolicNameContext) ASCENDING() antlr.TerminalNode {
	return s.GetToken(CypherParserASCENDING, 0)
}

func (s *SymbolicNameContext) BY() antlr.TerminalNode {
	return s.GetToken(CypherParserBY, 0)
}

func (s *SymbolicNameContext) CREATE() antlr.TerminalNode {
	return s.GetToken(CypherParserCREATE, 0)
}

func (s *SymbolicNameContext) DELETE() antlr.TerminalNode {
	return s.GetToken(CypherParserDELETE, 0)
}

func (s *SymbolicNameContext) DESC() antlr.TerminalNode {
	return s.GetToken(CypherParserDESC, 0)
}

func (s *SymbolicNameContext) DESCENDING() antlr.TerminalNode {
	return s.GetToken(CypherParserDESCENDING, 0)
}

func (s *SymbolicNameContext) DETACH() antlr.TerminalNode {
	return s.GetToken(CypherParserDETACH, 0)
}

func (s *SymbolicNameContext) EXISTS() antlr.TerminalNode {
	return s.GetToken(CypherParserEXISTS, 0)
}

func (s *SymbolicNameContext) LIMIT() antlr.TerminalNode {
	return s.GetToken(CypherParserLIMIT, 0)
}

func (s *SymbolicNameContext) MATCH() antlr.TerminalNode {
	return s.GetToken(CypherParserMATCH, 0)
}

func (s *SymbolicNameContext) MERGE() antlr.TerminalNode {
	return s.GetToken(CypherParserMERGE, 0)
}

func (s *SymbolicNameContext) ON() antlr.TerminalNode {
	return s.GetToken(CypherParserON, 0)
}

func (s *SymbolicNameContext) OPTIONAL() antlr.TerminalNode {
	return s.GetToken(CypherParserOPTIONAL, 0)
}

func (s *SymbolicNameContext) ORDER() antlr.TerminalNode {
	return s.GetToken(CypherParserORDER, 0)
}

func (s *SymbolicNameContext) REMOVE() antlr.TerminalNode {
	return s.GetToken(CypherParserREMOVE, 0)
}

func (s *SymbolicNameContext) RETURN() antlr.TerminalNode {
	return s.GetToken(CypherParserRETURN, 0)
}

func (s *SymbolicNameContext) SET() antlr.TerminalNode {
	return s.GetToken(CypherParserSET, 0)
}

func (s *SymbolicNameContext) SKIP_W() antlr.TerminalNode {
	return s.GetToken(CypherParserSKIP_W, 0)
}

func (s *SymbolicNameContext) WHERE() antlr.TerminalNode {
	return s.GetToken(CypherParserWHERE, 0)
}

func (s *SymbolicNameContext) WITH() antlr.TerminalNode {
	return s.GetToken(CypherParserWITH, 0)
}

func (s *SymbolicNameContext) UNION() antlr.TerminalNode {
	return s.GetToken(CypherParserUNION, 0)
}

func (s *SymbolicNameContext) UNWIND() antlr.TerminalNode {
	return s.GetToken(CypherParserUNWIND, 0)
}

func (s *SymbolicNameContext) AND() antlr.TerminalNode {
	return s.GetToken(CypherParserAND, 0)
}

func (s *SymbolicNameContext) AS() antlr.TerminalNode {
	return s.GetToken(CypherParserAS, 0)
}

func (s *SymbolicNameContext) CONTAINS() antlr.TerminalNode {
	return s.GetToken(CypherParserCONTAINS, 0)
}

func (s *SymbolicNameContext) DISTINCT() antlr.TerminalNode {
	return s.GetToken(CypherParserDISTINCT, 0)
}

func (s *SymbolicNameContext) ENDS() antlr.TerminalNode {
	return s.GetToken(CypherParserENDS, 0)
}

func (s *SymbolicNameContext) IN() antlr.TerminalNode {
	return s.GetToken(CypherParserIN, 0)
}

func (s *SymbolicNameContext) IS() antlr.TerminalNode {
	return s.GetToken(CypherParserIS, 0)
}

func (s *SymbolicNameContext) NOT() antlr.TerminalNode {
	return s.GetToken(CypherParserNOT, 0)
}

func (s *SymbolicNameContext) OR() antlr.TerminalNode {
	return s.GetToken(CypherParserOR, 0)
}

func (s *SymbolicNameContext) STARTS() antlr.TerminalNode {
	return s.GetToken(CypherParserSTARTS, 0)
}

func (s *SymbolicNameContext) XOR() antlr.TerminalNode {
	return s.GetToken(CypherParserXOR, 0)
}

func (s *SymbolicNameContext) FALSE() antlr.TerminalNode {
	return s.GetToken(CypherParserFALSE, 0)
}

func (s *SymbolicNameContext) TRUE() antlr.TerminalNode {
	return s.GetToken(CypherParserTRUE, 0)
}

func (s *SymbolicNameContext) NULL_W() antlr.TerminalNode {
	return s.GetToken(CypherParserNULL_W, 0)
}

func (s *SymbolicNameContext) CONSTRAINT() antlr.TerminalNode {
	return s.GetToken(CypherParserCONSTRAINT, 0)
}

func (s *SymbolicNameContext) DO() antlr.TerminalNode {
	return s.GetToken(CypherParserDO, 0)
}

func (s *SymbolicNameContext) FOR() antlr.TerminalNode {
	return s.GetToken(CypherParserFOR, 0)
}

func (s *SymbolicNameContext) REQUIRE() antlr.TerminalNode {
	return s.GetToken(CypherParserREQUIRE, 0)
}

func (s *SymbolicNameContext) UNIQUE() antlr.TerminalNode {
	return s.GetToken(CypherParserUNIQUE, 0)
}

func (s *SymbolicNameContext) CASE() antlr.TerminalNode {
	return s.GetToken(CypherParserCASE, 0)
}

func (s *SymbolicNameContext) WHEN() antlr.TerminalNode {
	return s.GetToken(CypherParserWHEN, 0)
}

func (s *SymbolicNameContext) THEN() antlr.TerminalNode {
	return s.GetToken(CypherParserTHEN, 0)
}

func (s *SymbolicNameContext) ELSE() antlr.TerminalNode {
	return s.GetToken(CypherParserELSE, 0)
}

func (s *SymbolicNameContext) END() antlr.TerminalNode {
	return s.GetToken(CypherParserEND, 0)
}

func (s *SymbolicNameContext) MANDATORY() antlr.TerminalNode {
	return s.GetToken(CypherParserMANDATORY, 0)
}

func (s *SymbolicNameContext) SCALAR() antlr.TerminalNode {
	return s.GetToken(CypherParserSCALAR, 0)
}

func (s *SymbolicNameContext) OF() antlr.TerminalNode {
	return s.GetToken(CypherParserOF, 0)
}

func (s *SymbolicNameContext) ADD() antlr.TerminalNode {
	return s.GetToken(CypherParserADD, 0)
}

func (s *SymbolicNameContext) DROP() antlr.TerminalNode {
	return s.GetToken(CypherParserDROP, 0)
}

func (s *SymbolicNameContext) GetRuleContext() antlr.RuleContext {
	return s
}

func (s *SymbolicNameContext) ToStringTree(ruleNames []string, recog antlr.Recognizer) string {
	return antlr.TreesStringTree(s, ruleNames, recog)
}

func (s *SymbolicNameContext) Accept(visitor antlr.ParseTreeVisitor) interface{} {
	switch t := visitor.(type) {
	case CypherParserVisitor:
		return t.VisitSymbolicName(s)

	default:
		return t.VisitChildren(s)
	}
}

func (p *CypherParser) SymbolicName() (localctx ISymbolicNameContext) {
	localctx = NewSymbolicNameContext(p, p.GetParserRuleContext(), p.GetState())
	p.EnterRule(localctx, 148, CypherParserRULE_symbolicName)
	var _la int

	p.EnterOuterAlt(localctx, 1)
	{
		p.SetState(730)
		_la = p.GetTokenStream().LA(1)

		if !((int64((_la-28)) & ^0x3f) == 0 && ((int64(1)<<(_la-28))&9223372036854775807) != 0) {
			p.GetErrorHandler().RecoverInline(p)
		} else {
			p.GetErrorHandler().ReportMatch(p)
			p.Consume()
		}
	}

	if p.HasError() {
		v := p.GetError()
		localctx.SetException(v)
		p.GetErrorHandler().ReportError(p, v)
		p.GetErrorHandler().Recover(p, v)
		p.SetError(nil)
	}
	p.ExitRule()
	return localctx
}
