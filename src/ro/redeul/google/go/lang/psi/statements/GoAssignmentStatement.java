package ro.redeul.google.go.lang.psi.statements;

import org.jetbrains.annotations.Nullable;
import ro.redeul.google.go.lang.psi.GoDocumentedPsiElement;
import ro.redeul.google.go.lang.psi.expressions.GoExpressionList;

public interface GoAssignmentStatement extends GoStatement, GoDocumentedPsiElement {

    enum Op {
        Null,
        Assign,
        PlusEq, MinusEq, BitOrEq, BitXorEq,
        MulEq, QuotientEq, RemainderEq, ShiftLeftEq, ShiftRightEq, BitAndEq, BitClearEq
    }

    @Nullable
    GoExpressionList getLeftSideExpressions();

    @Nullable
    GoExpressionList getRightSideExpressions();

    Op getOperator();
}