// This is a generated file. Not intended for manual editing.
package com.goide.psi.impl;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import static com.goide.GoTypes.*;
import com.goide.psi.*;
import com.intellij.psi.stubs.IStubElementType;

public class GoPointerTypeImpl extends GoTypeImpl implements GoPointerType {

  public GoPointerTypeImpl(ASTNode node) {
    super(node);
  }

  public GoPointerTypeImpl(com.goide.stubs.GoTypeStub stub, IStubElementType nodeType) {
    super(stub, nodeType);
  }

  public void accept(@NotNull GoVisitor visitor) {
    visitor.visitPointerType(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof GoVisitor) accept((GoVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public GoType getType() {
    return findChildByClass(GoType.class);
  }

  @Override
  @NotNull
  public PsiElement getMul() {
    return findNotNullChildByType(MUL);
  }

}
