grammar AppLang;

@header {
package ru.itmo.applang.parser;
}

// ============================================================
// Parser rules
// ============================================================

program
    : topLevelDecl* EOF
    ;

topLevelDecl
    : functionDecl
    | classDecl
    ;

// Класс: только на верхнем уровне; в теле — только поля, конструктор и методы (LANG-01).
classDecl
    : 'class' IDENT '{' classMember* '}'
    ;

classMember
    : fieldDecl
    | constructorDecl
    | functionDecl
    ;

// Тип поля всегда указывается явно (LANG-04); инициализатор необязателен.
fieldDecl
    : ('var' | 'val') IDENT ':' type ('=' expr)? ';'
    ;

constructorDecl
    : 'constructor' '(' paramList? ')' block
    ;

functionDecl
    : 'fun' IDENT '(' paramList? ')' (':' type)? block
    ;

paramList
    : param (',' param)*
    ;

param
    : IDENT ':' type
    ;

type
    : 'Int'
    | 'Bool'
    | 'String'
    | IDENT // пользовательский класс
    ;

block
    : '{' statement* '}'
    ;

statement
    : varDecl
    | assignment
    | fieldAssignment
    | ifStmt
    | whileStmt
    | returnStmt
    | exprStmt
    | block
    ;

varDecl
    : ('var' | 'val') IDENT (':' type)? '=' expr ';'
    ;

assignment
    : IDENT '=' expr ';'
    ;

// Запись поля — только оператор, как и присваивание локальной переменной (LANG-09).
fieldAssignment
    : receiver=expr '.' IDENT '=' value=expr ';'
    ;

ifStmt
    : 'if' '(' expr ')' thenBranch=block ('else' elseBranch=block)?
    ;

whileStmt
    : 'while' '(' expr ')' block
    ;

returnStmt
    : 'return' expr? ';'
    ;

exprStmt
    : expr ';'
    ;

// Приоритеты операторов заданы порядком альтернатив (ANTLR left-recursion).
// Обращение к члену через '.' — самый высокий приоритет: -a.b == -(a.b), a.b.c() == (a.b).c().
expr
    : receiver=expr '.' IDENT '(' argList? ')'         # MethodCall
    | receiver=expr '.' IDENT                          # FieldAccess
    | left=expr op=('*'|'/'|'%') right=expr           # MulDivMod
    | left=expr op=('+'|'-') right=expr                # AddSub
    | left=expr op=('<'|'>'|'<='|'>=') right=expr      # Relational
    | left=expr op=('=='|'!=') right=expr              # Equality
    | left=expr '&&' right=expr                        # LogicalAnd
    | left=expr '||' right=expr                        # LogicalOr
    | '!' operand=expr                                  # LogicalNot
    | '-' operand=expr                                  # UnaryMinus
    | '(' inner=expr ')'                                # Parens
    | IDENT '(' argList? ')'                            # Call
    | IDENT                                              # VarRef
    | INT_LITERAL                                        # IntLiteral
    | STRING_LITERAL                                      # StringLiteral
    | ('true' | 'false')                                  # BoolLiteral
    | 'this'                                              # This
    ;

argList
    : expr (',' expr)*
    ;

// ============================================================
// Lexer rules
// ============================================================

FUN: 'fun';
VAR: 'var';
VAL: 'val';
IF: 'if';
ELSE: 'else';
WHILE: 'while';
RETURN: 'return';
TRUE: 'true';
FALSE: 'false';
CLASS: 'class';
CONSTRUCTOR: 'constructor';
THIS: 'this';

INT_LITERAL
    : [0-9]+
    ;

STRING_LITERAL
    : '"' (ESCAPE_SEQ | ~["\\\r\n])* '"'
    ;

fragment ESCAPE_SEQ
    : '\\' [ntr"\\]
    ;

IDENT
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

WS
    : [ \t\r\n]+ -> skip
    ;
